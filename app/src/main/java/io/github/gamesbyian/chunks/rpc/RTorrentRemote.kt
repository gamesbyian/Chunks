package io.github.gamesbyian.chunks.rpc

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RemoteCredentials(val endpoint: String, val username: String, val password: String)

/**
 * Method enumeration is nullable: legacy or restricted installations may
 * reject system.listMethods even when ordinary RPC commands work.
 */
data class RemoteProbe(val version: String, val advertisedMethods: Set<String>?) {
    val multicallAdvertised: Boolean? get() = advertisedMethods?.contains("d.multicall2")
}

/** Stable identity and observed values from one specific rTorrent server snapshot. */
data class RemoteTransfer(
    val hash: String,
    val name: String,
    val sizeBytes: Long,
    val completedBytes: Long,
    val started: Boolean,
    val directory: String,
    val downBytesPerSecond: Long? = null,
    val upBytesPerSecond: Long? = null,
    val ratioThousandths: Long? = null
) {
    val fractionComplete: Float
        get() = if (sizeBytes > 0) (completedBytes.toDouble() / sizeBytes).coerceIn(0.0, 1.0).toFloat() else 0f
}

data class RemoteFile(val path: String, val sizeBytes: Long, val priority: Int)

/**
 * Initial *read-only* XML-RPC adapter. No automatic retries or mutations.
 * HTTPS only, except HTTP on literal loopback used for controlled development fixtures.
 */
class RTorrentRemote(private val auth: RemoteCredentials) {
    private val endpoint: URI = URI(auth.endpoint.trim()).also { uri ->
        require(uri.scheme?.lowercase() == "https" ||
            (uri.scheme?.lowercase() == "http" && uri.host in listOf("localhost", "127.0.0.1", "[::1]"))) {
            "Use an HTTPS reverse proxy to connect securely to rTorrent"
        }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "Invalid rTorrent endpoint"
        }
    }

    suspend fun version(): String =
        call("system.client_version") as? String ?: error("Unexpected rTorrent version reply")

    suspend fun probe(): RemoteProbe {
        val serverVersion = version()
        val methods = try {
            (call("system.listMethods") as? List<*>)?.map { item ->
                item as? String ?: error("Non-string RPC method name")
            }?.toSet()
        } catch (_: RpcFault) {
            // Missing introspection does not imply a missing torrent-list method.
            null
        }
        return RemoteProbe(serverVersion, methods)
    }

    /** Only server-owned view names, never XML-RPC command fragments. */
    suspend fun listTransfers(view: String = "main"): List<RemoteTransfer> {
        require(Regex("[A-Za-z][A-Za-z0-9_-]{0,39}").matches(view)) { "Invalid view" }
        val minimum = listOf("d.hash=", "d.name=", "d.size_bytes=", "d.completed_bytes=",
            "d.state=", "d.directory=")
        val extended = minimum + listOf("d.down.rate=", "d.up.rate=", "d.ratio=")
        // Optional fields vary across rTorrent builds. Fall back only for
        // an explicit server-side command fault, not on timeouts or network loss.
        val rows: List<*> 
        val rich: Boolean
        try {
            rows = extractRows(call("d.multicall2", listOf("", view) + extended))
            rich = true
        } catch (_: RpcFault) {
            rows = extractRows(call("d.multicall2", listOf("", view) + minimum))
            rich = false
        }
        require(rows.size <= 12000) { "Unexpectedly large torrent list" }
        return rows.map { raw ->
            val row = raw as? List<*> ?: error("Invalid torrent row")
            require(row.size == if (rich) extended.size else minimum.size) {
                "Incompatible rTorrent field layout"
            }
            val hash = row[0] as? String ?: error("Missing info hash")
            require(hash.isNotBlank() && hash.length <= 128) { "Invalid info hash" }
            RemoteTransfer(
                hash = hash,
                name = (row[1] as? String ?: error("Missing torrent name")).take(2048),
                sizeBytes = number(row[2]).coerceAtLeast(0),
                completedBytes = number(row[3]).coerceAtLeast(0),
                started = number(row[4]) != 0L,
                directory = (row[5] as? String ?: error("Missing server path")).take(4096),
                downBytesPerSecond = if (rich) number(row[6]).coerceAtLeast(0) else null,
                upBytesPerSecond = if (rich) number(row[7]).coerceAtLeast(0) else null,
                ratioThousandths = if (rich) number(row[8]).coerceAtLeast(0) else null
            )
        }
    }

    /** A per-torrent read-only file inspector; never changes file priorities. */
    suspend fun listFiles(hash: String): List<RemoteFile> {
        require(Regex("[0-9a-fA-F]{40}([0-9a-fA-F]{24})?").matches(hash)) {
            "Invalid info hash"
        }
        val response = call("f.multicall", listOf(hash, "", "f.path=", "f.size_bytes=", "f.priority="))
        val rows = extractRows(response)
        require(rows.size <= 12000) { "Too many files in torrent" }
        return rows.map { item ->
            val columns = item as? List<*> ?: error("Invalid remote file")
            require(columns.size == 3) { "Invalid remote file fields" }
            RemoteFile(
                path = (columns[0] as? String ?: error("Missing file path")).take(4096),
                sizeBytes = number(columns[1]).coerceAtLeast(0),
                priority = number(columns[2]).toInt()
            )
        }
    }

    private fun extractRows(response: Any?): List<*> =
        response as? List<*> ?: error("Unexpected rTorrent array reply")

    private fun number(value: Any?): Long =
        (value as? Number)?.toLong() ?: error("Expected numeric rTorrent value")

    private suspend fun call(method: String, params: List<Any> = emptyList()): Any? =
        withContext(Dispatchers.IO) {
            val connection = endpoint.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("Accept", "text/xml")
            if (auth.username.isNotEmpty()) {
                val token = Base64.getEncoder().encodeToString(
                    "${auth.username}:${auth.password}".toByteArray(Charsets.UTF_8))
                connection.setRequestProperty("Authorization", "Basic $token")
            }
            connection.doOutput = true
            try {
                connection.outputStream.use { stream ->
                    stream.write(XmlRpcCodec.request(method, params).toByteArray(Charsets.UTF_8))
                }
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw IOException("rTorrent gateway returned HTTP $status")
                }
                val length = connection.contentLengthLong
                if (length > 4_194_304L) throw IOException("RPC response exceeded 4 MiB")
                val xml = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val chars = CharArray(4096)
                    val sb = StringBuilder()
                    while (true) {
                        val read = reader.read(chars)
                        if (read < 0) break
                        sb.append(chars, 0, read)
                        if (sb.length > 4_194_304) throw IOException("RPC response exceeded 4 MiB")
                    }
                    sb.toString()
                }
                XmlRpcCodec.response(xml)
            } finally {
                connection.disconnect()
            }
        }
}
