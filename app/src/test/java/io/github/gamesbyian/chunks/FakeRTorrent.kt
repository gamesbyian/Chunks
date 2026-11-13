package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A small stateful loopback rTorrent XML-RPC double for JVM UI regressions: it registers jobs
 * from `load.*` (magnet or raw metainfo), answers the exact-hash getters the adapter uses for
 * readback, and toggles `d.start`/`d.stop`. Each response closes its connection. It is a
 * secondary iteration tool only (REAL-TRANSFER-ACCEPTANCE): no peers, pieces or payload.
 */
internal class FakeRTorrent(
    /** Literal `directory.default` reply; null answers with a permission-style fault. */
    @Volatile var directoryDefault: String? = "/srv",
    /** Where a job lands when Add sends no directory and the default is unusable. */
    private val unsetDefaultLandsIn: String = "/home/user",
    private val workingDirectory: String? = null,
    @Volatile var activationBlocked: Boolean = false,
    private val untrustedWorkingDirectory: Boolean = false
) : AutoCloseable {
    data class Job(val hash: String, val name: String, @Volatile var directory: String,
                   @Volatile var started: Boolean)

    val jobs = ConcurrentHashMap<String, Job>()
    val calls = CopyOnWriteArrayList<Pair<String, List<String>>>()
    private val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    val endpoint = "http://127.0.0.1:${socket.localPort}/RPC2"

    init {
        Thread {
            while (!socket.isClosed) try { serve(socket.accept()) } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    val methods: List<String> get() = calls.map { it.first }

    fun loads(): List<String> = methods.filter { it.startsWith("load.") }

    fun seed(hash: String, name: String, directory: String = "/srv", started: Boolean = false) {
        jobs[hash.lowercase()] = Job(hash.lowercase(), name, directory, started)
    }

    override fun close() = socket.close()

    private fun str(s: String) = "<value><string>" +
        s.replace("&", "&amp;").replace("<", "&lt;") + "</string></value>"
    private fun num(n: Long) = "<value><i8>$n</i8></value>"
    private fun array(items: List<String>) = "<value><array><data>" + items.joinToString("") +
        "</data></array></value>"
    private fun fault(code: Int, text: String) = "<?xml version=\"1.0\"?><methodResponse><fault><value><struct>" +
        "<member><name>faultCode</name><value><int>$code</int></value></member>" +
        "<member><name>faultString</name><value><string>$text</string></value></member>" +
        "</struct></value></fault></methodResponse>"

    private fun params(xml: String): List<String> =
        Regex("<(string|base64)>([^<]*)</(?:string|base64)>").findAll(xml)
            .map { (if (it.groupValues[1] == "base64") "base64:" else "") + it.groupValues[2] }.toList()

    private fun answer(xml: String): String {
        val method = Regex("<methodName>([^<]+)</methodName>").find(xml)?.groupValues?.get(1).orEmpty()
        val p = params(xml)
        calls += method to p
        fun job() = p.firstOrNull()?.lowercase()?.let { jobs[it] }
        val value: String = when (method) {
            "system.client_version" -> str("0.9.8")
            "system.listMethods" -> array(listOf("d.open", "d.start", "d.resume", "d.stop", "d.close", "d.erase", "d.hash", "d.state",
                "d.is_active", "d.directory", "load.start", "load.raw_start", "load.verbose",
                "load.raw_verbose", "f.multicall", "f.priority.set", "d.check_hash").map(::str))
            "directory.default" -> directoryDefault?.let(::str)
                ?: return fault(-503, "Permission denied")
            "system.cwd" -> if (untrustedWorkingDirectory)
                return fault(-507, "Command &quot;system.cwd&quot; is not allowed for untrusted connections.")
                else workingDirectory?.let(::str) ?: return fault(-506, "Method not found")
            "d.multicall2" -> {
                val view = p.getOrNull(1) ?: "main"
                val metadata = xml.contains("d.is_meta=")
                val rows = jobs.values.sortedBy { it.name }.filter {
                    when (view) { "started" -> it.started; "stopped" -> !it.started; else -> true }
                }.map { j ->
                    array(listOf(str(j.hash), str(j.name), num(1000), num(0), num(if (j.started) 1 else 0),
                        str(j.directory), num(0), num(0), num(0)) + if (metadata) listOf(num(0)) else emptyList())
                }
                array(rows)
            }
            "d.hash" -> job()?.let { str(it.hash) } ?: return fault(-501, "Could not find info-hash.")
            "d.name" -> job()?.let { str(it.name) } ?: return fault(-501, "Could not find info-hash.")
            "d.directory" -> job()?.let { str(it.directory) } ?: return fault(-501, "Could not find info-hash.")
            "d.state" -> job()?.let { num(if (it.started) 1 else 0) }
                ?: return fault(-501, "Could not find info-hash.")
            "d.is_active", "d.is_open" -> job()?.let { num(if (it.started && !activationBlocked) 1 else 0) }
                ?: return fault(-501, "Could not find info-hash.")
            "d.hashing_failed" -> num(if (activationBlocked) 1 else 0)
            "d.hashing" -> num(0)
            "d.message" -> str("")
            "d.down.total", "d.completed_bytes", "d.peers_connected", "d.down.rate" -> num(0)
            "d.size_bytes" -> num(1000)
            "d.is_multi_file", "d.is_meta", "d.is_hash_checking", "d.is_private" ->
                job()?.let { num(0) } ?: return fault(-501, "Could not find info-hash.")
            "d.tracker_size" -> job()?.let { num(1) } ?: return fault(-501, "Could not find info-hash.")
            "d.start", "d.stop", "d.open", "d.resume", "d.close" -> {
                val j = job() ?: return fault(-501, "Could not find info-hash.")
                when (method) {
                    "d.start", "d.resume" -> j.started = true
                    "d.stop", "d.close" -> j.started = false
                }
                num(0)
            }
            "d.erase" -> { jobs.remove(p.firstOrNull()?.lowercase()); num(0) }
            "f.multicall", "t.multicall" -> array(emptyList())
            "load.start", "load.verbose", "load.raw_start", "load.raw_verbose" -> {
                val source = p.getOrNull(1).orEmpty()
                val (hash, name) = if (source.startsWith("base64:")) {
                    val preview = TorrentMetainfoReader.preview(Base64.getMimeDecoder().decode(source.removePrefix("base64:")))
                    preview.infoHashV1.lowercase() to preview.name
                } else {
                    val h = Regex("btih:([0-9a-fA-F]{40})").find(source)?.groupValues?.get(1)?.lowercase().orEmpty()
                    h to (Regex("[?&]dn=([^&]*)").find(source)?.groupValues?.get(1) ?: h)
                }
                val explicit = p.drop(2).firstOrNull { it.startsWith("d.directory.set=") }
                    ?.removePrefix("d.directory.set=")
                val default = directoryDefault?.takeIf { it.startsWith("/") }
                if (hash.isNotEmpty()) jobs[hash] = Job(hash, name, explicit ?: default ?: unsetDefaultLandsIn,
                    started = method == "load.start" || method == "load.raw_start")
                num(0)
            }
            else -> return fault(-506, "not in fixture")
        }
        return "<?xml version=\"1.0\"?><methodResponse><params><param>$value</param></params></methodResponse>"
    }

    private fun serve(connection: Socket) { connection.use {
        val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
        val request = reader.readLine() ?: return
        var length = 0
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(":").trim().toInt()
        }
        if (request.startsWith("HEAD")) {
            it.getOutputStream().apply { write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            return
        }
        val body = CharArray(length).also { chars ->
            var read = 0
            while (read < length) read += reader.read(chars, read, length - read)
        }
        val result = answer(String(body)).toByteArray()
        it.getOutputStream().apply {
            write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: ${result.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray())
            write(result); flush()
        }
    } }
}
