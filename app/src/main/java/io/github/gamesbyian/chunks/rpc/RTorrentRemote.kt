package io.github.gamesbyian.chunks.rpc

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RemoteCredentials(val endpoint: String, val username: String, val password: String)

/** Stable identity and observed values from one specific rTorrent server snapshot. */
data class RemoteTransfer(
    val hash: String,
    val name: String,
    val sizeBytes: Long,
    val completedBytes: Long,
    val started: Boolean,
    val directory: String
) {
    val fractionComplete: Float
        get() = if (sizeBytes > 0) (completedBytes.toDouble() / sizeBytes).coerceIn(0.0, 1.0).toFloat() else 0f
}

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

    suspend fun version(): String = call("system.client_version").toString()

    suspend fun listTransfers(): List<RemoteTransfer> {
        val fields = listOf("d.hash=", "d.name=", "d.size_bytes=", "d.completed_bytes=", "d.state=", "d.directory=")
        val response = call("d.multicall2", listOf("", "main") + fields)
        val rows = response as? List<*> ?: error("Unexpected rTorrent list result")
        return rows.map { raw ->
            val row = raw as? List<*> ?: error("Invalid torrent row")
            require(row.size == fields.size) { "Incompatible rTorrent field layout" }
            RemoteTransfer(
                hash = row[0] as? String ?: error("Missing info hash"),
                name = row[1] as? String ?: error("Missing torrent name"),
                sizeBytes = number(row[2]),
                completedBytes = number(row[3]),
                started = number(row[4]) != 0L,
                directory = row[5] as? String ?: error("Missing server path")
            )
        }
    }

    private fun number(value: Any?): Long =
        (value as? Number)?.toLong() ?: error("Expected numeric rTorrent value")

    suspend fun call(method: String, params: List<Any> = emptyList()): Any? =
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
