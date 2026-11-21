package io.github.gamesbyian.chunks.rpc

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Optional read-only ruTorrent Source-plugin adapter. Unlike rTorrent XML-RPC,
 * this plugin may return a full original v1 .torrent from the exact mounted
 * session. POST is the plugin's *read* protocol; no load, move, delete,
 * arbitrary command or automatic retry is possible here.
 *
 * A missing/disabled plugin, unsupported metainfo, wrong hash, redirect,
 * malformed response or failed authorization yields no definition. Never
 * invent a recoverable magnet from a bare infohash. The caller must obtain
 * separate user consent before exporting potentially private announce URLs.
 */
internal object RuTorrentSourceMetainfo {
    private val hashPattern = Regex("[0-9a-fA-F]{40}")

    fun endpoint(rpc: URI): URI? = RuTorrentDeleteData.endpoint(rpc)?.let {
        URI.create(it.toString().removeSuffix("erasedata/action.php") +
            "source/action.php")
    }

    /** Null means unavailable, not proof that this transfer lacks metainfo. */
    suspend fun read(
        rpc: URI, credentials: RemoteCredentials, expectedInfoHash: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (!hashPattern.matches(expectedInfoHash)) return@withContext null
        val url = endpoint(rpc) ?: return@withContext null
        val hash = expectedInfoHash.lowercase(Locale.ROOT)
        // The upstream plugin uses the posted spelling as a filename. Both
        // legacy and modern rTorrent write uppercase session basenames.
        val sessionHash = hash.uppercase(Locale.ROOT)
        try {
            val first = request(url, sessionHash, authorization = null)
            val response = if (first.status == 401) {
                val requestPath = url.rawPath.orEmpty()
                val authorization = HttpRpcAuthorization.fromChallenge(
                    first.challenges, credentials, "POST", requestPath)
                request(url, sessionHash, authorization)
            } else first
            if (response.status != 200) return@withContext null
            val bytes = response.body ?: return@withContext null
            // PHP can return a login page, noty() JavaScript, a missing-source
            // redirect, or an unrelated session's bytes. Independent bencode,
            // v1 identity and size validation is the only positive witness.
            val preview = TorrentMetainfoReader.preview(bytes)
            if (preview.infoHashV1 != hash) null else bytes
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    private data class Reply(
        val status: Int, val body: ByteArray?, val challenges: List<String>
    )

    private fun request(url: URI, hash: String, authorization: String?): Reply {
        val form = ("hash=" + URLEncoder.encode(hash, Charsets.US_ASCII.name()))
            .toByteArray(Charsets.US_ASCII)
        val connection = url.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 7_000
        connection.readTimeout = 7_000
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.setRequestProperty("Accept", "application/x-bittorrent, application/octet-stream")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        if (authorization != null) connection.setRequestProperty("Authorization", authorization)
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(form.size)
        try {
            connection.outputStream.use { it.write(form) }
            val code = connection.responseCode
            val challenges = if (code == 401) connection.headerFields.entries
                .filter { it.key?.equals("WWW-Authenticate", ignoreCase = true) == true }
                .flatMap { it.value }.take(8).filter { it.length <= 8192 }
                else emptyList()
            if (code != 200) return Reply(code, null, challenges)
            // Never allocate an unbounded response or a ZIP/HTML body from an
            // untrusted plugin. The .torrent parser has the same 2 MiB cap.
            if (connection.contentLengthLong > TorrentMetainfoReader.MAX_BYTES)
                return Reply(code, null, emptyList())
            val data = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (count == 0) continue
                    if (output.size() + count > TorrentMetainfoReader.MAX_BYTES)
                        throw IOException("Oversized Source plugin response")
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            return Reply(code, data, emptyList())
        } finally {
            connection.disconnect()
        }
    }
}
