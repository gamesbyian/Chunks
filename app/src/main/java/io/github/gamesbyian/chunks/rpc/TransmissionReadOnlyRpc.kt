package io.github.gamesbyian.chunks.rpc

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A deliberately read-only first Transmission adapter. It does not register a
 * profile, authorize an Add, or map an unverified server integer to a Chunks job.
 * A caller must still prove the configured account and destination separately.
 */
internal class TransmissionReadOnlyRpc(
    credentials: RemoteCredentials,
    private val wire: Wire = HttpWire
) {
    private val auth = credentials
    private val endpoint: URI = validateEndpoint(credentials.endpoint)
    private val requestTarget = endpoint.rawPath
    private var csrfSession: String? = null
    private var challengedRead = false

    internal data class Request(
        val endpoint: URI,
        val body: ByteArray,
        val authorization: String?,
        val csrfSession: String?
    ) {
        override fun toString(): String = "Transmission request (<redacted>)"
    }

    internal data class Response(
        val status: Int,
        val headers: Map<String, List<String>>,
        val body: String
    ) {
        fun header(name: String): List<String> =
            headers.entries.filter { it.key.equals(name, ignoreCase = true) }.flatMap { it.value }
    }

    internal fun interface Wire {
        suspend fun exchange(request: Request): Response
    }

    internal enum class Dialect { LEGACY, JSON_RPC_2 }

    internal data class Torrent(
        val hash: String,
        /** Native numeric ID is ephemeral and never a portable JobKey. */
        val nativeId: Long,
        val name: String,
        val totalBytes: Long,
        val completedBytes: Long,
        /** Server-reported path is NOT an independently verified Place. */
        val reportedDirectory: String,
        /** Numeric status is left uninterpreted until versioned capabilities exist. */
        val nativeStatus: Int,
        val downloadBytesPerSecond: Long,
        val uploadBytesPerSecond: Long
    )

    internal data class Snapshot(
        val version: String,
        val rpcVersion: Int,
        val dialect: Dialect,
        val torrents: List<Torrent>,
        /** Challenge evidence only; never proof of account ownership by itself. */
        val challengedAuthentication: Boolean
    )

    /**
     * Two read-only requests: legacy session-get first for safe version
     * negotiation, then the version-appropriate torrent-get. Transmission 4.1+
     * deliberately keeps the legacy request scheme for compatibility.
     */
    suspend fun inspect(): Snapshot = withContext(Dispatchers.IO) {
        val session = read("session-get", "session_get", JSONObject(), Dialect.LEGACY, 1)
        val rpcVersion = integer(session, "rpc-version").toInt()
        if (rpcVersion !in 1..1000) throw IOException("Unsupported Transmission RPC version")
        val version = string(session, "version", 128)
        val dialect = if (rpcVersion >= 18) Dialect.JSON_RPC_2 else Dialect.LEGACY
        val fields = if (dialect == Dialect.JSON_RPC_2) listOf(
            "id", "hash_string", "name", "total_size", "left_until_done",
            "download_dir", "status", "rate_download", "rate_upload"
        ) else listOf(
            "id", "hashString", "name", "totalSize", "leftUntilDone",
            "downloadDir", "status", "rateDownload", "rateUpload"
        )
        val params = JSONObject().put("fields", JSONArray(fields))
        val inventory = read("torrent-get", "torrent_get", params, dialect, 2)
        val raw = inventory.opt("torrents") as? JSONArray
            ?: throw IOException("Transmission inventory was incomplete")
        if (raw.length() > 50_000) throw IOException("Transmission inventory exceeded safety limit")
        val hashes = HashSet<String>()
        val ids = HashSet<Long>()
        val torrents = (0 until raw.length()).map { index ->
            val item = raw.opt(index) as? JSONObject
                ?: throw IOException("Invalid Transmission inventory row")
            val hashKey = if (dialect == Dialect.JSON_RPC_2) "hash_string" else "hashString"
            val sizeKey = if (dialect == Dialect.JSON_RPC_2) "total_size" else "totalSize"
            val leftKey = if (dialect == Dialect.JSON_RPC_2) "left_until_done" else "leftUntilDone"
            val dirKey = if (dialect == Dialect.JSON_RPC_2) "download_dir" else "downloadDir"
            val downKey = if (dialect == Dialect.JSON_RPC_2) "rate_download" else "rateDownload"
            val upKey = if (dialect == Dialect.JSON_RPC_2) "rate_upload" else "rateUpload"
            val hash = string(item, hashKey, 64).lowercase()
            if (!HASH.matches(hash) || !hashes.add(hash)) {
                throw IOException("Transmission inventory contains ambiguous torrent identities")
            }
            val id = integer(item, "id")
            if (!ids.add(id)) throw IOException("Transmission inventory contains duplicate native IDs")
            val total = integer(item, sizeKey)
            val remaining = integer(item, leftKey)
            if (remaining > total) throw IOException("Transmission inventory has inconsistent byte counts")
            Torrent(
                hash = hash, nativeId = id, name = string(item, "name", 2048),
                totalBytes = total, completedBytes = total - remaining,
                reportedDirectory = string(item, dirKey, 4096),
                nativeStatus = integer(item, "status").toIntExact(),
                downloadBytesPerSecond = integer(item, downKey),
                uploadBytesPerSecond = integer(item, upKey)
            )
        }
        Snapshot(version, rpcVersion, dialect, torrents, challengedRead)
    }

    /**
     * Only HTTP 409 is a documented pre-execution session rejection. Retry
     * exactly once with its validated token and only on this same URL. All
     * requests here are accessors; this retry policy must not be reused for
     * Add/Remove, where an unknown write outcome must be reconciled read-only.
     */
    private suspend fun read(
        legacy: String, modern: String, params: JSONObject, dialect: Dialect, id: Int
    ): JSONObject {
        val request = if (dialect == Dialect.LEGACY) JSONObject()
            .put("method", legacy).put("arguments", params)
        else JSONObject().put("jsonrpc", "2.0").put("method", modern)
            .put("params", params).put("id", id)
        val body = request.toString().toByteArray(Charsets.UTF_8)
        var authorization: String? = null
        var retriedAuth = false
        var retriedSession = false
        for (attempt in 0..2) {
            val response = wire.exchange(Request(endpoint, body, authorization, csrfSession))
            when (response.status) {
                409 -> {
                    if (retriedSession) throw IOException("Transmission session negotiation failed")
                    val values = response.header("X-Transmission-Session-Id")
                    if (values.size != 1 || !validToken(values.single())) {
                        throw IOException("Invalid Transmission session challenge")
                    }
                    csrfSession = values.single()
                    retriedSession = true
                    continue
                }
                401 -> {
                    if (retriedAuth || auth.username.isEmpty())
                        throw RpcHttpStatusException(401)
                    val challenge = try {
                        HttpRpcAuthorization.fromChallenge(
                            response.header("WWW-Authenticate"), auth, "POST", requestTarget
                        )
                    } catch (_: IOException) {
                        throw RpcHttpStatusException(401)
                    }
                    authorization = challenge
                    retriedAuth = true
                    continue
                }
                in 200..299 -> {
                    if (response.body.length > MAX_BODY) throw IOException("Transmission reply too large")
                    val root = try { JSONObject(response.body) } catch (_: Exception) {
                        throw IOException("Invalid Transmission RPC response")
                    }
                    val values = if (dialect == Dialect.LEGACY) {
                        if (root.opt("result") != "success") {
                            throw IOException("Transmission accessor returned a protocol refusal")
                        }
                        root.opt("arguments") as? JSONObject
                    } else {
                        if (root.opt("jsonrpc") != "2.0" || root.opt("id") !is Number ||
                            root.opt("id").toString() != id.toString() ||
                            root.has("error") && !root.isNull("error")) {
                            throw IOException("Transmission JSON-RPC result failed validation")
                        }
                        root.opt("result") as? JSONObject
                    } ?: throw IOException("Transmission accessor returned no result")
                    if (retriedAuth) challengedRead = true
                    return values
                }
                else -> throw if (response.status in 100..599)
                    RpcHttpStatusException(response.status)
                else IOException("Invalid Transmission HTTP response")
            }
        }
        throw IOException("Transmission RPC challenge budget exhausted")
    }

    private fun Long.toIntExact(): Int {
        if (this !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            throw IOException("Invalid Transmission integer")
        return toInt()
    }

    private fun integer(obj: JSONObject, key: String): Long {
        val raw = obj.opt(key)
        if (raw !is Number) throw IOException("Invalid Transmission numeric field")
        val parsed = raw.toString().toLongOrNull()
            ?: throw IOException("Invalid Transmission numeric field")
        if (parsed < 0) throw IOException("Invalid Transmission numeric field")
        return parsed
    }

    private fun string(obj: JSONObject, key: String, limit: Int): String {
        val value = obj.opt(key) as? String
            ?: throw IOException("Invalid Transmission text field")
        if (value.length !in 1..limit || value.any { it.isISOControl() }) {
            throw IOException("Invalid Transmission text field")
        }
        return value
    }

    private fun validToken(value: String): Boolean = value.length in 1..256 &&
        value.all { it.code in 33..126 } && !value.contains(':') &&
        !value.contains('\\')

    private companion object {
        const val MAX_BODY = 4_194_304
        val HASH = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")
        val ESCAPED_SEPARATOR = Regex("(?i)%(?:25)*(?:2f|5c|0[0-9a-f]|1[0-9a-f]|7f)")
        val DOT_SEGMENT = Regex("(?i)(?:^|/)(?:\\.|%(?:25)*2e){1,2}(?:/|$)")

        fun validateEndpoint(raw: String): URI {
            val uri = try { URI(raw) } catch (_: Exception) {
                throw IllegalArgumentException("Invalid Transmission RPC endpoint")
            }
            val loopback = uri.host?.removePrefix("[")?.removeSuffix("]") in
                setOf("localhost", "127.0.0.1", "::1")
            require(uri.scheme.equals("https", true) ||
                (uri.scheme.equals("http", true) && loopback)) {
                "Transmission requires HTTPS except for device-local testing"
            }
            val path = uri.rawPath.orEmpty()
            require(!uri.host.isNullOrEmpty() && uri.userInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null &&
                path.startsWith("/") && path.length in 2..2048 &&
                !ESCAPED_SEPARATOR.containsMatchIn(path) &&
                !DOT_SEGMENT.containsMatchIn(path)) {
                "Invalid or ambiguous Transmission RPC endpoint"
            }
            return uri
        }
    }

    /** Keep response parsing and credentials isolated from redirect-capable stacks. */
    private object HttpWire : Wire {
        override suspend fun exchange(request: Request): Response {
            val connection = request.endpoint.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            request.authorization?.let { connection.setRequestProperty("Authorization", it) }
            request.csrfSession?.let { connection.setRequestProperty("X-Transmission-Session-Id", it) }
            connection.setFixedLengthStreamingMode(request.body.size)
            try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                connection.outputStream.use { it.write(request.body) }
                val status = connection.responseCode
                val headers = connection.headerFields.entries
                    .filter { it.key?.equals("WWW-Authenticate", true) == true ||
                        it.key?.equals("X-Transmission-Session-Id", true) == true }
                    .associate { it.key!! to it.value.orEmpty() }
                val stream = if (status in 200..299) connection.inputStream else null
                val body = if (stream == null) "" else stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val builder = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count == -1) break
                        builder.append(buffer, 0, count)
                        if (builder.length > MAX_BODY) throw IOException("Transmission reply too large")
                    }
                    builder.toString()
                }
                return Response(status, headers, body)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: IOException) {
                throw IOException("Transmission RPC transport failed")
            } finally {
                connection.disconnect()
            }
        }
    }
}
