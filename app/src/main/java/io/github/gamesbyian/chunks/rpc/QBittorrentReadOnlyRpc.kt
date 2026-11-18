package io.github.gamesbyian.chunks.rpc

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Isolated, read-only qBittorrent WebUI API v2 probe. It cannot perform
 * Add/Start/Stop/Delete, save a Chunks profile or authorize another client.
 *
 * An HTTP 200 from auth/login alone is NOT proof of login: qBittorrent also
 * reports failed logins with HTTP 200. Only "Ok." plus a bounded SID cookie
 * allows subsequent reads, always at this same explicit origin and mount.
 */
internal class QBittorrentReadOnlyRpc(
    credentials: RemoteCredentials,
    private val wire: Wire = HttpWire
) {
    private val auth = credentials
    private val webRoot = validateRoot(credentials.endpoint)
    private val origin = URI(webRoot.scheme, null, webRoot.host, webRoot.port, "/", null, null)
        .toString().removeSuffix("/")

    internal class Request(
        val url: URI, val method: String, val body: ByteArray?,
        val origin: String, val cookie: String?,
        val contentType: String = "application/x-www-form-urlencoded"
    ) {
        override fun toString(): String = "qBittorrent request (<redacted>)"
    }

    internal data class Response(
        val status: Int,
        val body: String,
        val headers: Map<String, List<String>> = emptyMap(),
        /** Exact reply bytes (binary endpoints such as torrents/export). */
        val raw: ByteArray? = null
    ) {
        fun header(name: String): List<String> =
            headers.entries.filter { it.key.equals(name, true) }.flatMap { it.value }
    }

    internal fun interface Wire {
        suspend fun exchange(request: Request): Response
    }

    /** Unknown counters stay unknown; no UI may mistake an absent field for zero. */
    internal data class Torrent(
        val hash: String,
        val name: String,
        val state: String,
        val selectedSizeBytes: Long,
        val totalSizeBytes: Long?,
        val completedBytes: Long?,
        val downloadBytesPerSecond: Long?,
        val uploadBytesPerSecond: Long?,
        /** RPC-reported only, not independent filesystem/Place proof. */
        val reportedSavePath: String?
    )

    internal data class Snapshot(
        val applicationVersion: String,
        val webApiVersion: String,
        val torrents: List<Torrent>
    )

    /** Each inspect authenticates once, then performs only three accessor GETs. */
    suspend fun inspect(): Snapshot = withContext(Dispatchers.IO) {
        val sessionCookie = loginOnce()
        val version = text(read("app/version", sessionCookie), "application version", 128)
        val webApiVersion = text(read("app/webapiVersion", sessionCookie), "WebUI API version", 64)
        val body = read("torrents/info", sessionCookie)
        val rows = try { JSONArray(body) } catch (_: Exception) {
            throw IOException("Invalid qBittorrent inventory response")
        }
        if (rows.length() > 50_000) throw IOException("qBittorrent inventory exceeds safety limit")
        val seen = HashSet<String>()
        val torrents = (0 until rows.length()).map { index ->
            val row = rows.opt(index) as? JSONObject
                ?: throw IOException("Invalid qBittorrent inventory row")
            val hash = requiredText(row, "hash", 64).lowercase()
            if (!HASH.matches(hash) || !seen.add(hash)) {
                throw IOException("Ambiguous qBittorrent torrent identity")
            }
            Torrent(
                hash = hash,
                name = requiredText(row, "name", 2048),
                state = requiredText(row, "state", 128),
                selectedSizeBytes = nonnegative(row, "size")
                    ?: throw IOException("Missing qBittorrent selected size"),
                // Real qBittorrent (4.6.3) reports total_size -1 while a magnet
                // has no metadata yet: that is "not known", not corruption.
                totalSizeBytes = nonnegativeOrUnknown(row, "total_size"),
                completedBytes = nonnegative(row, "completed"),
                downloadBytesPerSecond = nonnegative(row, "dlspeed"),
                uploadBytesPerSecond = nonnegative(row, "upspeed"),
                reportedSavePath = optionalText(row, "save_path", 4096)
            )
        }
        Snapshot(version, webApiVersion, torrents)
    }

    private suspend fun loginOnce(): String {
        if (auth.username.isBlank() || auth.username.length > 256 ||
            auth.username.any { it.isISOControl() } ||
            auth.password.isEmpty()) throw IOException("Missing or invalid qBittorrent login")
        val form = "username=" + URLEncoder.encode(auth.username, "UTF-8") +
            "&password=" + URLEncoder.encode(auth.password, "UTF-8")
        val response = wire.exchange(Request(
            endpoint("auth/login"), "POST", form.toByteArray(Charsets.UTF_8), origin, null
        ))
        // qBittorrent <= 5.0 answers 200 "Ok."; newer releases answer 204 No
        // Content. Either still needs exactly one valid SID cookie below.
        val accepted = (response.status == 200 && response.body.trim() == "Ok.") ||
            (response.status == 204 && response.body.isBlank())
        if (!accepted) {
            // Fixed, credential-free reasons; 5.x answers failed logins 401.
            throw IOException("qBittorrent authentication refused (" + when {
                response.status == 200 -> "credentials rejected"
                response.status == 401 -> "401 unauthorized"
                response.status == 403 -> "403 forbidden, possibly too many failed logins"
                else -> "HTTP " + response.status
            } + ")")
        }
        // Modern qBittorrent names the cookie QBT_SID_<WebUIPort> to
        // distinguish parallel WebUI daemons on one origin; older versions
        // use SID. The internal port may differ from an HTTPS proxy's port.
        // Take exactly one supported cookie from this exact login response,
        // never copy other Set-Cookie values or a browser's cookie jar.
        val sessionCookies = response.header("Set-Cookie").mapNotNull { header ->
            val pair = header.substringBefore(';').trim()
            val name = pair.substringBefore('=', "")
            val value = pair.substringAfter('=', "")
            if (name == "SID" || PORT_COOKIE.matches(name)) name to value else null
        }
        if (sessionCookies.size != 1 || !SID.matches(sessionCookies.single().second)) {
            throw IOException("qBittorrent session cookie missing or invalid")
        }
        return sessionCookies.single().let { (name, value) -> name + "=" + value }
    }

    private suspend fun read(method: String, sessionCookie: String): String {
        val response = wire.exchange(Request(endpoint(method), "GET", null, origin, sessionCookie))
        if (response.status != 200) {
            throw if (response.status in 100..599) RpcHttpStatusException(response.status)
            else IOException("Invalid qBittorrent HTTP response")
        }
        if (response.body.length > MAX_BODY) throw IOException("qBittorrent reply too large")
        return response.body
    }

    private fun endpoint(method: String): URI =
        URI(webRoot.toString() + "api/v2/" + method)

    private fun text(body: String, label: String, maxLength: Int): String {
        val value = body.trim()
        if (value.length !in 1..maxLength || value.any { it.isISOControl() }) {
            throw IOException("Invalid qBittorrent $label")
        }
        return value
    }

    private fun requiredText(row: JSONObject, name: String, maxLength: Int): String =
        optionalText(row, name, maxLength)?.takeIf { it.isNotBlank() }
            ?: throw IOException("Invalid qBittorrent inventory text")

    private fun optionalText(row: JSONObject, name: String, maxLength: Int): String? {
        if (!row.has(name) || row.isNull(name)) return null
        val value = row.opt(name) as? String
            ?: throw IOException("Invalid qBittorrent inventory text")
        if (value.length > maxLength || value.any { it.isISOControl() }) {
            throw IOException("Invalid qBittorrent inventory text")
        }
        return value
    }

    /** qBittorrent's documented -1 means unknown; any other negative stays invalid. */
    private fun nonnegativeOrUnknown(row: JSONObject, name: String): Long? {
        if (row.has(name) && (row.opt(name) as? Number)?.toString() == "-1") return null
        return nonnegative(row, name)
    }

    private fun nonnegative(row: JSONObject, name: String): Long? {
        if (!row.has(name) || row.isNull(name)) return null
        val value = row.opt(name) as? Number
            ?: throw IOException("Invalid qBittorrent inventory counter")
        return value.toString().toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IOException("Invalid qBittorrent inventory counter")
    }

    internal companion object {
        const val MAX_BODY = 4_194_304
        val HASH = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")
        val SID = Regex("[A-Za-z0-9._~+/=-]{8,256}")
        val PORT_COOKIE = Regex("QBT_SID_(?:[1-9][0-9]{0,4})")
        val ESCAPED_SEPARATOR = Regex("(?i)%(?:25)*(?:2f|5c|0[0-9a-f]|1[0-9a-f]|7f)")
        val DOT_SEGMENT = Regex("(?i)(?:^|/)(?:\\.|%(?:25)*2e){1,2}(?:/|$)")

        fun validateRoot(raw: String): URI {
            val uri = try { URI(raw) } catch (_: Exception) {
                throw IllegalArgumentException("Invalid qBittorrent web root")
            }
            val loopback = uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
            val path = uri.rawPath.orEmpty()
            require(uri.scheme.equals("https", true) ||
                (uri.scheme.equals("http", true) && loopback)) {
                "qBittorrent requires HTTPS except for loopback testing"
            }
            require(!uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null &&
                path.startsWith("/") && path.endsWith("/") && path.length in 1..2048 &&
                !ESCAPED_SEPARATOR.containsMatchIn(path) &&
                !DOT_SEGMENT.containsMatchIn(path)) {
                "Invalid or ambiguous qBittorrent web root"
            }
            return uri
        }
    }

    /** Only an explicit root is used; redirects never carry an SID or password. */
    internal object HttpWire : Wire {
        override suspend fun exchange(request: Request): Response {
            val connection = request.url.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = request.method
            // Like other native API clients, send no Origin/Referer: behind a
            // reverse proxy the WebUI's cross-site check compares them with
            // the proxied Host and rejects the login with 401.
            request.cookie?.let { connection.setRequestProperty("Cookie", it) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", request.contentType)
                connection.setFixedLengthStreamingMode(body.size)
            }
            try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                request.body?.let { body -> connection.outputStream.use { it.write(body) } }
                val status = connection.responseCode
                val headers = connection.headerFields.entries
                    .filter { it.key?.equals("Set-Cookie", true) == true }
                    .associate { it.key!! to it.value.orEmpty() }
                val bytes = if (status !in 200..299) ByteArray(0) else connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        out.write(buffer, 0, count)
                        if (out.size() > MAX_BODY) throw IOException("qBittorrent reply too large")
                    }
                    out.toByteArray()
                }
                val body = String(bytes, Charsets.UTF_8)
                return Response(status, body, headers, bytes)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: IOException) {
                throw IOException("qBittorrent connection or response failed")
            } finally {
                connection.disconnect()
            }
        }
    }
}
