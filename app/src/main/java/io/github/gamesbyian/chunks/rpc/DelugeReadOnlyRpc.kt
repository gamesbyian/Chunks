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
 * Read-only torrent inventory on an explicitly selected Deluge daemon.
 *
 * Deluge's authenticated Web UI may have multiple registered daemons. Web
 * login is insufficient to grant any torrent operation: inspect() confirms a
 * known host ID and its status before connecting this isolated web session
 * to that exact daemon. web.connect changes only this WebUI session's daemon,
 * never the torrent inventory. No Add/Start/Stop/Remove methods are exposed.
 */
internal class DelugeReadOnlyRpc(
    credentials: RemoteCredentials,
    private val daemonHostId: String,
    private val wire: Wire = HttpWire
) {
    private val auth = credentials
    private val endpoint = validateEndpoint(credentials.endpoint)

    init {
        require(daemonHostId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) {
            "A valid explicit Deluge daemon ID is required"
        }
    }

    internal class Request(val endpoint: URI, val body: ByteArray, val sessionCookie: String?) {
        override fun toString(): String = "Deluge RPC request (<redacted>)"
    }

    internal data class Response(
        val status: Int, val body: String,
        val headers: Map<String, List<String>> = emptyMap()
    ) {
        fun header(name: String): List<String> =
            headers.entries.filter { it.key.equals(name, true) }.flatMap { it.value }
    }

    internal fun interface Wire {
        suspend fun exchange(request: Request): Response
    }

    internal data class Torrent(
        val hash: String, val name: String, val state: String,
        val totalSizeBytes: Long?, val completedBytes: Long?,
        val downloadBytesPerSecond: Long?, val uploadBytesPerSecond: Long?,
        /** Daemon-reported only, never a verified client-side Place. */
        val reportedSavePath: String?
    )

    internal data class Snapshot(
        val selectedDaemonId: String, val daemonVersion: String,
        val torrents: List<Torrent>
    )

    suspend fun inspect(): Snapshot = withContext(Dispatchers.IO) {
        require(auth.password.isNotEmpty()) { "Deluge web password is required" }
        val (authenticated, cookie) = login()
        if (!authenticated) throw IOException("Deluge Web UI login refused")
        val hostList = rpc("web.get_hosts", JSONArray(), 2, cookie) as? JSONArray
            ?: throw IOException("Deluge did not return a host list")
        if (hostList.length() > 1000) throw IOException("Deluge host list exceeds safety limit")
        val matches = (0 until hostList.length()).count { index ->
            val host = hostList.opt(index) as? JSONArray
                ?: throw IOException("Malformed Deluge host list")
            host.length() >= 4 && host.opt(0) == daemonHostId
        }
        if (matches != 1) throw IOException("Deluge daemon ID is missing or ambiguous")
        val status = rpc("web.get_host_status", JSONArray().put(daemonHostId), 3, cookie)
            as? JSONArray ?: throw IOException("Deluge daemon status unavailable")
        if (status.length() < 3 || status.opt(0) != daemonHostId ||
            status.opt(1) !is String || (status.opt(1) as String).lowercase() !in
            setOf("online", "connected")) throw IOException("Selected Deluge daemon is unavailable")
        val version = status.opt(2) as? String
            ?: throw IOException("Deluge daemon version unavailable")
        if (version.length !in 1..128 || version.any { it.isISOControl() }) {
            throw IOException("Invalid Deluge daemon version")
        }

        // The only non-accessor selects a verified daemon in the Web UI's
        // shared slot. The cookie authenticates the request, not the slot.
        val supported = rpc("web.connect", JSONArray().put(daemonHostId), 4, cookie) as? JSONArray
            ?: throw IOException("Selected Deluge daemon refused connection")
        // web.connect returns *daemon* methods; web.update_ui itself is a
        // WebUI-side aggregator and never proves the selected daemon supports
        // full-inventory reads. No arbitrary nonempty method list is enough.
        if (supported.length() > 5000 ||
            !(0 until supported.length()).any { supported.opt(it) == "core.get_torrents_status" }) {
            throw IOException("Selected Deluge daemon lacks inventory capability")
        }
        val bound = rpc("web.get_host_status", JSONArray().put(daemonHostId), 5, cookie) as? JSONArray
            ?: throw IOException("Deluge active daemon status unavailable")
        if (bound.opt(0) != daemonHostId ||
            !(bound.opt(1) as? String).equals("connected", ignoreCase = true)) {
            throw IOException("Deluge Web UI is not connected to the selected daemon")
        }
        val keys = JSONArray(listOf(
            "name", "state", "total_size", "total_done",
            "download_payload_rate", "upload_payload_rate", "save_path"
        ))
        val update = rpc("web.update_ui", JSONArray().put(keys).put(JSONObject()), 6, cookie)
            as? JSONObject ?: throw IOException("Deluge inventory unavailable")
        val transfers = update.opt("torrents") as? JSONObject
            ?: throw IOException("Deluge inventory is not complete")
        val names = transfers.keys().asSequence().toList()
        if (names.size > 50_000) throw IOException("Deluge inventory exceeds safety limit")
        val seen = HashSet<String>()
        val torrents = names.map { rawHash ->
            val hash = rawHash.lowercase()
            if (!HASH.matches(hash) || !seen.add(hash)) {
                throw IOException("Ambiguous Deluge torrent identity")
            }
            val entry = transfers.opt(rawHash) as? JSONObject
                ?: throw IOException("Invalid Deluge inventory entry")
            Torrent(
                hash = hash,
                name = requiredText(entry, "name", 2048),
                state = requiredText(entry, "state", 128),
                totalSizeBytes = number(entry, "total_size"),
                completedBytes = number(entry, "total_done"),
                downloadBytesPerSecond = number(entry, "download_payload_rate"),
                uploadBytesPerSecond = number(entry, "upload_payload_rate"),
                reportedSavePath = optionalText(entry, "save_path", 4096)
            )
        }
        Snapshot(daemonHostId, version, torrents)
    }

    /**
     * Read-only discovery of the daemon host IDs registered in this Web UI.
     * Selection remains explicit: callers may adopt an ID automatically only
     * when exactly one daemon is registered, never by picking among several.
     */
    suspend fun registeredDaemonIds(): List<String> = withContext(Dispatchers.IO) {
        require(auth.password.isNotEmpty()) { "Deluge web password is required" }
        val (authenticated, cookie) = login()
        if (!authenticated) throw IOException("Deluge Web UI login refused")
        val hostList = rpc("web.get_hosts", JSONArray(), 2, cookie) as? JSONArray
            ?: throw IOException("Deluge did not return a host list")
        if (hostList.length() > 1000) throw IOException("Deluge host list exceeds safety limit")
        (0 until hostList.length()).map { index ->
            val host = hostList.opt(index) as? JSONArray
                ?: throw IOException("Malformed Deluge host list")
            (host.opt(0) as? String)?.takeIf { DAEMON_ID.matches(it) }
                ?: throw IOException("Malformed Deluge host identity")
        }
    }

    /** Authenticated web session cookie is scoped to this endpoint, never global. */
    private suspend fun login(): Pair<Boolean, String> {
        val result = call("auth.login", JSONArray().put(auth.password), 1, null)
        if (result.status != 200) throw IOException("Deluge Web UI login refused (HTTP ${result.status})")
        val json = decode(result.body, 1)
        if (json.opt("result") != true) throw IOException("Deluge Web UI login refused (password rejected)")
        val sid = result.header("Set-Cookie").mapNotNull { value ->
            value.substringBefore(';').trim().takeIf { it.startsWith("_session_id=") }
                ?.removePrefix("_session_id=")
        }
        if (sid.size != 1 || !SESSION.matches(sid.single())) {
            throw IOException("Deluge Web UI session cookie unavailable")
        }
        return true to "_session_id=" + sid.single()
    }

    private suspend fun rpc(name: String, args: JSONArray, id: Int, cookie: String): Any? {
        val response = call(name, args, id, cookie)
        if (response.status != 200) {
            throw if (response.status in 100..599) RpcHttpStatusException(response.status)
            else IOException("Invalid Deluge HTTP response")
        }
        return decode(response.body, id, inventory = name == "web.update_ui").opt("result")
    }

    private fun decode(text: String, id: Int, inventory: Boolean = false): JSONObject {
        val obj = try {
            if (inventory) JSONObject(text) else ExactRpcJson.objectBody(text)
        } catch (_: Exception) {
            throw IOException("Malformed Deluge JSON-RPC reply")
        }
        if (exactWireInteger(obj.opt("id")) != id.toLong() ||
            obj.opt("error") != null && !obj.isNull("error") ||
            !obj.has("result")) throw IOException("Deluge RPC result failed validation")
        return obj
    }

    private suspend fun call(name: String, args: JSONArray, id: Int, cookie: String?): Response {
        val body = JSONObject().put("method", name).put("params", args)
            .put("id", id).toString().toByteArray(Charsets.UTF_8)
        val response = wire.exchange(Request(endpoint, body, cookie))
        if (response.body.length > MAX_BODY) throw IOException("Deluge reply too large")
        return response
    }

    private fun requiredText(row: JSONObject, key: String, max: Int): String =
        optionalText(row, key, max)?.takeIf { it.isNotBlank() }
            ?: throw IOException("Missing Deluge inventory text")

    private fun optionalText(row: JSONObject, key: String, max: Int): String? {
        if (!row.has(key) || row.isNull(key)) return null
        val value = row.opt(key) as? String
            ?: throw IOException("Invalid Deluge inventory text")
        if (value.length > max || value.any { it.isISOControl() })
            throw IOException("Invalid Deluge inventory text")
        return value
    }

    private fun number(row: JSONObject, key: String): Long? {
        if (!row.has(key) || row.isNull(key)) return null
        val value = row.opt(key) as? Number
            ?: throw IOException("Invalid Deluge inventory counter")
        return value.toString().toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IOException("Invalid Deluge inventory counter")
    }

    internal companion object {
        const val MAX_BODY = 4_194_304
        val HASH = Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})")
        val DAEMON_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        val SESSION = Regex("[A-Za-z0-9._~%-]{8,256}")
        val ESCAPED = Regex("(?i)%(?:25)*(?:2f|5c|0[0-9a-f]|1[0-9a-f]|7f)")
        val DOT = Regex("(?i)(?:^|/)(?:\\.|%(?:25)*2e){1,2}(?:/|$)")

        fun validateEndpoint(raw: String): URI {
            val uri = try { URI(raw) } catch (_: Exception) {
                throw IllegalArgumentException("Invalid Deluge JSON endpoint")
            }
            val loopback = uri.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
            require(uri.scheme.equals("https", true) ||
                (uri.scheme.equals("http", true) && loopback)) {
                "Deluge requires HTTPS except on loopback"
            }
            val path = uri.rawPath.orEmpty()
            require(!uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null &&
                path.endsWith("/json") && path.length in 5..2048 &&
                !ESCAPED.containsMatchIn(path) && !DOT.containsMatchIn(path)) {
                "Invalid or ambiguous Deluge JSON endpoint"
            }
            return uri
        }
    }

    /** No redirect, auth cookie only to the exact supplied endpoint. */
    internal object HttpWire : Wire {
        override suspend fun exchange(request: Request): Response {
            val connection = request.endpoint.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            request.sessionCookie?.let { connection.setRequestProperty("Cookie", it) }
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(request.body.size)
            try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                connection.outputStream.use { it.write(request.body) }
                val status = connection.responseCode
                val headers = connection.headerFields.entries
                    .filter { it.key?.equals("Set-Cookie", true) == true }
                    .associate { it.key!! to it.value.orEmpty() }
                val text = if (status !in 200..299) "" else
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val builder = StringBuilder()
                        val buffer = CharArray(4096)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            builder.append(buffer, 0, count)
                            if (builder.length > MAX_BODY) throw IOException("Deluge reply too large")
                        }
                        builder.toString()
                    }
                return Response(status, text, headers)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: IOException) {
                throw IOException("Deluge RPC transport failed")
            } finally {
                connection.disconnect()
            }
        }
    }
}
