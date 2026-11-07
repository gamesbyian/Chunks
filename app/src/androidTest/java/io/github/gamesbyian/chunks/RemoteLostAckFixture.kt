package io.github.gamesbyian.chunks

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android-device localhost XML-RPC fixture for a *post-load* reply loss.
 *
 * A refused connection is proven pre-write, so it cannot exercise uncertain
 * mutation recovery. This fixture first accepts exactly one load.start RPC,
 * records an applied job, then drops the reply with HTTP 503. All subsequent
 * exact-session reads are possible, including a job hidden from the main view.
 *
 * This is negative installed UI evidence only. The independent disposable
 * rTorrent/real-peer suite qualifies actual remote engine + payload behavior.
 */
internal class RemoteLostAckFixture : AutoCloseable {
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    private val hash = "ab".repeat(20)
    private val place = "/home/dev/chunks-public-test"
    @Volatile private var loaded = false
    val methods = CopyOnWriteArrayList<String>()
    val failures = CopyOnWriteArrayList<String>()

    val endpoint: String = "http://127.0.0.1:" + listener.localPort + "/RPC2"

    private val worker = Thread({
        while (running.get()) {
            try {
                listener.accept().use { serve(it) }
            } catch (_: SocketException) {
                if (!running.get()) break
                failures += "socket_failure"
            } catch (_: Exception) {
                if (running.get()) failures += "fixture_request_failure"
            }
        }
    }, "chunks-remote-lost-ack-fixture").apply {
        isDaemon = true
        start()
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 12_000
        val input = BufferedInputStream(socket.getInputStream())
        val header = ByteArrayOutputStream()
        var marker = 0
        while (true) {
            val byte = input.read()
            require(byte >= 0 && header.size() < 16_384) { "Truncated HTTP header" }
            header.write(byte)
            marker = when {
                marker == 0 && byte == 13 -> 1
                marker == 1 && byte == 10 -> 2
                marker == 2 && byte == 13 -> 3
                marker == 3 && byte == 10 -> 4
                byte == 13 -> 1
                else -> 0
            }
            if (marker == 4) break
        }
        val headerText = header.toString(Charsets.US_ASCII.name())
        val head = headerText.substringBefore("\r\n")
        if (head.startsWith("HEAD ")) {
            respond(socket, 200, "")
            return
        }
        require(head.startsWith("POST /RPC2 ")) { "Unexpected fixture request" }
        val length = headerText.lines().firstOrNull {
            it.startsWith("Content-Length:", ignoreCase = true)
        }?.substringAfter(':')?.trim()?.toIntOrNull() ?: error("Missing content length")
        require(length in 0..1_000_000) { "Unbounded fixture body" }
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < bytes.size) {
            val n = input.read(bytes, offset, bytes.size - offset)
            require(n > 0) { "Truncated XML-RPC body" }
            offset += n
        }
        val method = Regex("<methodName>([^<]+)</methodName>")
            .find(bytes.toString(Charsets.UTF_8))?.groupValues?.get(1)
            ?: error("Missing RPC method")
        methods += method
        if (method == "load.start") {
            check(!loaded) { "Duplicate non-idempotent Add" }
            loaded = true
            respond(socket, 503, "")
            return
        }
        val value: String = when (method) {
            "directory.default" -> "<value><string>/downloads</string></value>"
            "d.multicall2" -> "<value><array><data></data></array></value>"
            "d.name" -> {
                if (!loaded) {
                    val fault = """
                        <methodResponse><fault><value><struct>
                        <member><name>faultCode</name><value><int>-501</int></value></member>
                        <member><name>faultString</name><value><string>Could not find info-hash.</string></value></member>
                        </struct></value></fault></methodResponse>
                    """.trimIndent()
                    respond(socket, 200, fault)
                    return
                }
                "<value><string>Public test</string></value>"
            }
            "d.hash" -> "<value><string>$hash</string></value>"
            "d.directory" -> "<value><string>$place</string></value>"
            "d.is_multi_file", "d.is_meta" -> "<value><int>0</int></value>"
            "d.state", "d.is_active" -> "<value><int>1</int></value>"
            "d.size_bytes", "d.completed_bytes", "d.down.rate", "d.up.rate" ->
                "<value><i8>0</i8></value>"
            else -> {
                if (method.startsWith("load.") || method in setOf("d.start", "d.open", "d.resume")) {
                    failures += "unexpected_mutation"
                    respond(socket, 503, "")
                    return
                }
                "<value><int>0</int></value>"
            }
        }
        respond(socket, 200,
            "<methodResponse><params><param>$value</param></params></methodResponse>")
    }

    private fun respond(socket: Socket, status: Int, body: String) {
        val content = body.toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else "Service Unavailable"
        val headers = ("HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/xml; charset=utf-8\r\n" +
            "Content-Length: " + content.size + "\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        socket.getOutputStream().apply {
            write(headers)
            write(content)
            flush()
        }
    }

    override fun close() {
        running.set(false)
        listener.close()
        worker.join(2_000L)
    }
}
