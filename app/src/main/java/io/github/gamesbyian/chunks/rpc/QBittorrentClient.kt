package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.model.RemoteBackendKind
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Authenticated qBittorrent WebUI API v2 client (4.x and 5.x).
 *
 * Login success is only "Ok." plus one bounded SID cookie. qBittorrent
 * answers 403 before executing a handler when the session is missing or
 * expired, so a single re-login and resend after 403 is pre-execution and
 * safe; any transport failure on a mutator is uncertain. Version dialects:
 * WebAPI >= 2.11 (qBittorrent 5) uses start/stop, older pause/resume.
 */
internal class QBittorrentClient(
    credentials: RemoteCredentials,
    private val wire: QBittorrentReadOnlyRpc.Wire = QBittorrentReadOnlyRpc.HttpWire
) : TorrentClient {
    override val kind = TorrentClientKind.QBITTORRENT
    private val auth = credentials
    private val webRoot: URI = QBittorrentReadOnlyRpc.validateRoot(credentials.endpoint)
    private val origin = URI(webRoot.scheme, null, webRoot.host, webRoot.port, "/", null, null)
        .toString().removeSuffix("/")
    private var cookie: String? = null
    private var webApi: List<Int>? = null

    override suspend fun session(): ClientSessionInfo {
        val version = text(request("GET", "app/version"), 128)
        val api = text(request("GET", "app/webapiVersion"), 64)
        webApi = api.split('.').map { it.toIntOrNull() ?: 0 }
        val dir = request("GET", "app/defaultSavePath").trim().takeIf { it.startsWith("/") }
        return ClientSessionInfo(
            kind, version, dir,
            ClientCapability.entries.toSet() - ClientCapability.ADD_WITH_FILE_CHOICES,
            filePriorities = setOf(ClientFilePriority.SKIP, ClientFilePriority.NORMAL, ClientFilePriority.HIGH)
        )
    }

    override suspend fun transferSource(hash: String): ClientAddSource? {
        val exact = ClientHashes.requireHash(hash)
        // WebAPI >= 2.8.14 exports the job's exact .torrent (binary).
        val exported = try {
            withContext(Dispatchers.IO) {
                val session = cookie ?: login().also { cookie = it }
                wire.exchange(QBittorrentReadOnlyRpc.Request(
                    URI(webRoot.toString() + "api/v2/torrents/export?hash=$exact"), "GET", null, origin, session))
            }.takeIf { it.status == 200 }?.raw
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (exported != null) {
            val file = runCatching { ClientAddSource.TorrentFile(exported) }.getOrNull()
            if (file?.infoHash == exact) return file
        }
        val rows = optionalRead { JSONArray(request("GET", "torrents/info?hashes=$exact")) } ?: return null
        val row = exactProtocolRow((0 until rows.length()).map { rows.getJSONObject(it) }, exact) { it.optString("hash") } ?: return null
        val magnet = row.optString("magnet_uri").takeIf { it.startsWith("magnet:?") } ?: return null
        return runCatching { ClientAddSource.Magnet(magnet) }.getOrNull()?.takeIf { it.infoHash == exact }
    }

    override suspend fun torrents(): List<ClientTorrent> = info(null)

    override suspend fun torrent(hash: String): ClientTorrent? {
        val exact = ClientHashes.requireHash(hash)
        return exactProtocolRow(info(exact), exact) { it.hash }
    }

    override suspend fun files(hash: String): List<ClientFile> {
        val exact = ClientHashes.requireHash(hash)
        val rows = try { JSONArray(request("GET", "torrents/files?hash=$exact")) } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (_: Exception) { throw IOException("Invalid qBittorrent file list") }
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val size = row.getLong("size")
            val progress = row.optDouble("progress", -1.0)
            ClientFile(
                index = row.optInt("index", i),
                path = row.getString("name"),
                sizeBytes = size,
                completedBytes = if (progress in 0.0..1.0) (size * progress).toLong() else null,
                priority = when (row.optInt("priority", 1)) {
                    0 -> ClientFilePriority.SKIP
                    6, 7 -> ClientFilePriority.HIGH
                    else -> ClientFilePriority.NORMAL
                }
            )
        }
    }

    override suspend fun add(request: ClientAddRequest) {
        val boundary = "chunks-" + UUID.randomUUID().toString().replace("-", "")
        val out = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                .toByteArray(Charsets.UTF_8))
        }
        when (val source = request.source) {
            is ClientAddSource.Magnet -> field("urls", source.uri)
            is ClientAddSource.TorrentFile -> {
                out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"torrents\"; " +
                    "filename=\"${source.infoHash}.torrent\"\r\nContent-Type: application/x-bittorrent\r\n\r\n")
                    .toByteArray(Charsets.UTF_8))
                out.write(TorrentMetainfoReader.forNewRemoteAdd(source.bytes))
                out.write("\r\n".toByteArray())
            }
        }
        request.directory?.let {
            // An explicit folder must never be overridden by category/automatic
            // torrent management, which can relocate payload files.
            field("savepath", it)
            field("autoTMM", "false")
        }
        // 4.x reads "paused"; 5.x reads "stopped". Send both, consistently.
        field("paused", (!request.startNow).toString())
        field("stopped", (!request.startNow).toString())
        out.write("--$boundary--\r\n".toByteArray())
        val reply = request("POST", "torrents/add", out.toByteArray(),
            "multipart/form-data; boundary=$boundary", mutating = true).trim()
        if (reply == "Ok.") return // The legacy single-source success response.
        if (reply == "Fails.") throw ClientRefusedException("qBittorrent declined the Add (duplicate or invalid)")

        // The structured response can name the registered torrent. Missing,
        // pending, or conflicting evidence cannot be upgraded by inventory.
        val result = try { JSONObject(reply) } catch (_: Exception) { null }
        val added = result?.optJSONArray("added_torrent_ids")
        val pending = result?.opt("pending_count")
        val successes = result?.opt("success_count")
        val failures = result?.opt("failure_count")
        if (pending == 0 && successes == 0 && failures == 1 && added?.length() == 0) {
            throw ClientRefusedException("qBittorrent declined the Add (duplicate or invalid)")
        }
        if (pending == 0 && successes == 1 && failures == 0 && added?.length() == 1 &&
            added.optString(0).equals(request.source.infoHash, ignoreCase = true)) return
        throw ClientWriteUncertainException("qBittorrent did not identify the Add result")
    }

    override suspend fun setStarted(hash: String, started: Boolean) {
        val modern = (webApi ?: session().let { webApi!! }).let { compare(it, listOf(2, 11)) >= 0 }
        val method = when {
            modern && started -> "torrents/start"
            modern -> "torrents/stop"
            started -> "torrents/resume"
            else -> "torrents/pause"
        }
        form(method, "hashes" to ClientHashes.requireHash(hash))
    }

    override suspend fun remove(hash: String, deleteData: Boolean) = removeChecked(hash, deleteData) {}

    override suspend fun removeJob(hash: String, beforeWrite: () -> Unit) =
        removeChecked(hash, deleteData = false, beforeWrite = beforeWrite)

    private suspend fun removeChecked(hash: String, deleteData: Boolean, beforeWrite: () -> Unit) {
        form("torrents/delete", "hashes" to ClientHashes.requireHash(hash), "deleteFiles" to deleteData.toString(),
            beforeWrite = beforeWrite)
    }

    override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) {
        val exact = ClientHashes.requireHash(hash)
        val groups = priorities.entries.groupBy({ it.value }, { it.key })
        var sent = 0
        for ((priority, indices) in groups) {
            val value = when (priority) {
                ClientFilePriority.SKIP -> 0
                ClientFilePriority.LOW, ClientFilePriority.NORMAL -> 1
                ClientFilePriority.HIGH -> 6
            }
            try {
                form("torrents/filePrio", "hash" to exact,
                    "id" to indices.sorted().joinToString("|"), "priority" to value.toString())
            } catch (e: ClientRefusedException) {
                // A later group's refusal after an applied group is partial.
                if (sent > 0) throw ClientWriteUncertainException("Some file priorities may have changed")
                throw e
            }
            sent++
        }
    }

    override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) {
        // qBittorrent's setLocation always relocates payload files.
        if (!moveData) throw ClientRefusedException("qBittorrent can only change location by moving files")
        form("torrents/setLocation", "hashes" to ClientHashes.requireHash(hash),
            "location" to ClientHashes.requireDirectory(directory))
    }

    override suspend fun reannounce(hash: String) {
        form("torrents/reannounce", "hashes" to ClientHashes.requireHash(hash))
    }

    override suspend fun recheck(hash: String) {
        form("torrents/recheck", "hashes" to ClientHashes.requireHash(hash))
    }

    override suspend fun seedingFacts(hash: String): ClientSeedingFacts? {
        val exact = ClientHashes.requireHash(hash)
        val rows = try { JSONArray(request("GET", "torrents/info?hashes=$exact")) } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw IOException("Invalid qBittorrent seeding facts")
        }
        val r = exactProtocolRow((0 until rows.length()).map { rows.getJSONObject(it) }, exact) { it.optString("hash") } ?: return null
        // The private flag: in torrents/info on newer Web APIs, else torrents/properties.
        val private: Boolean? = when {
            r.has("private") -> r.optBoolean("private")
            else -> optionalRead { JSONObject(request("GET", "torrents/properties?hash=$exact")) }
                ?.let { p -> if (p.has("is_private")) p.optBoolean("is_private") else null }
        }
        val trackers = if (private == false) optionalRead {
            JSONArray(request("GET", "torrents/trackers?hash=$exact"))
        }?.let { a ->
            // DHT/PeX/LSD pseudo-rows are not announce URLs.
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("url") }
                .filter { it.contains("://") }
        } else null
        fun count(key: String) = if (r.has(key)) r.optLong(key, -1).takeIf { it >= 0 } else null
        val state = r.optString("state")
        return ClientSeedingFacts(exact, count("uploaded"), count("downloaded"), count("seeding_time"),
            seeding = if (state.isBlank()) null else state in setOf("uploading", "stalledUP", "forcedUP"),
            isPrivate = private, publicTrackerUrls = trackers)
    }

    // Optional reads may be unavailable, but cancellation must remain cancellation.
    private suspend fun <T> optionalRead(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun info(hash: String?): List<ClientTorrent> {
        val body = request("GET", "torrents/info" + (hash?.let { "?hashes=$it" } ?: ""))
        val rows = try { JSONArray(body) } catch (_: Exception) {
            throw IOException("Invalid qBittorrent inventory response")
        }
        if (rows.length() > 50_000) throw IOException("qBittorrent inventory exceeds safety limit")
        val seen = HashSet<String>()
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val h = row.getString("hash").lowercase()
            if (h.length != 40 || !seen.add(h)) throw IOException("Ambiguous qBittorrent identity")
            val size = row.optLong("size", -1).takeIf { it >= 0 }
            val done = row.optLong("completed", -1).takeIf { it >= 0 }
            ClientTorrent(
                hash = h,
                name = row.optString("name").ifBlank { h },
                runState = stateOf(row.optString("state")),
                totalBytes = size?.takeIf { it > 0 },
                completedBytes = done?.takeIf { size != null && it <= size },
                downloadBytesPerSecond = row.optLong("dlspeed", -1).takeIf { it >= 0 },
                uploadBytesPerSecond = row.optLong("upspeed", -1).takeIf { it >= 0 },
                downloadDirectory = row.optString("save_path").takeIf { it.startsWith("/") }
            )
        }
    }

    // qBittorrent stores a per-torrent upload limit in exact bytes/s (torrents/uploadLimit).
    override suspend fun uploadCapStepBytes(): Long = 1L

    override suspend fun uploadCapBytesPerSecond(hash: String): Long? {
        val exact = ClientHashes.requireHash(hash)
        val body = request("POST", "torrents/uploadLimit", "hashes=$exact".toByteArray(Charsets.UTF_8))
        val limits = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val key = limits.keys().asSequence().singleOrNull { it.equals(exact, ignoreCase = true) } ?: return null
        val value = limits.optLong(key, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } ?: return null
        return if (value <= 0) 0L else value
    }

    override suspend fun setUploadCapBytesPerSecond(hash: String, bytesPerSecond: Long) {
        require(bytesPerSecond > 0)
        form("torrents/setUploadLimit", "hashes" to ClientHashes.requireHash(hash), "limit" to bytesPerSecond.toString())
    }

    private suspend fun form(method: String, vararg fields: Pair<String, String>, beforeWrite: () -> Unit = {}) {
        val body = fields.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        request("POST", method, body.toByteArray(Charsets.UTF_8),
            "application/x-www-form-urlencoded", mutating = true, beforeWrite = beforeWrite)
    }

    private suspend fun request(
        method: String, path: String, body: ByteArray? = null,
        contentType: String = "application/x-www-form-urlencoded", mutating: Boolean = false, beforeWrite: () -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        var relogged = false
        while (true) {
            val session = cookie ?: login().also { cookie = it }
            if (mutating) RemoteWriteOwnerFence.check()
            beforeWrite()
            val response = try {
                wire.exchange(QBittorrentReadOnlyRpc.Request(
                    URI(webRoot.toString() + "api/v2/" + path), method, body, origin, session, contentType))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (mutating) throw ClientWriteUncertainException("qBittorrent reply lost")
                throw IOException("qBittorrent connection or response failed")
            }
            when (response.status) {
                200, 204 -> return@withContext response.body
                403 -> {
                    // Unauthenticated: rejected before the handler ran.
                    cookie = null
                    if (relogged) throw ClientRefusedException("qBittorrent session was refused")
                    relogged = true
                }
                404 -> throw ClientRefusedException("qBittorrent does not know this transfer or method")
                400, 409, 415 -> throw ClientRefusedException("qBittorrent rejected the request (HTTP ${response.status})")
                else -> {
                    if (mutating && response.status >= 500)
                        throw ClientWriteUncertainException("qBittorrent server error")
                    throw ClientRefusedException("qBittorrent HTTP ${response.status}")
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

    private suspend fun login(): String {
        if (auth.username.isBlank() || auth.password.isEmpty()) throw ClientRefusedException("Missing qBittorrent login")
        val form = "username=" + URLEncoder.encode(auth.username, "UTF-8") +
            "&password=" + URLEncoder.encode(auth.password, "UTF-8")
        val response = try {
            wire.exchange(QBittorrentReadOnlyRpc.Request(
                URI(webRoot.toString() + "api/v2/auth/login"), "POST",
                form.toByteArray(Charsets.UTF_8), origin, null))
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            throw IOException("qBittorrent connection failed")
        }
        // qBittorrent <= 5.0 answers 200 "Ok."; newer releases answer 204 No
        // Content. Either still needs exactly one valid SID cookie below.
        val accepted = (response.status == 200 && response.body.trim() == "Ok.") ||
            (response.status == 204 && response.body.isBlank())
        if (!accepted) {
            throw ClientRefusedException("qBittorrent authentication refused (" + when {
                response.status == 200 -> "credentials rejected"
                response.status == 401 -> "401 unauthorized"
                response.status == 403 -> "403 forbidden, possibly too many failed logins"
                else -> "HTTP " + response.status
            } + ")")
        }
        val cookies = response.header("Set-Cookie").mapNotNull { header ->
            val pair = header.substringBefore(';').trim()
            val name = pair.substringBefore('=', "")
            val value = pair.substringAfter('=', "")
            if (name == "SID" || Regex("QBT_SID_[1-9][0-9]{0,4}").matches(name)) name to value else null
        }
        if (cookies.size != 1 || !QBittorrentReadOnlyRpc.SID.matches(cookies.single().second)) {
            throw ClientRefusedException("qBittorrent session cookie missing or invalid")
        }
        return cookies.single().let { it.first + "=" + it.second }
    }

    private fun text(body: String, max: Int): String = body.trim().takeIf {
        it.length in 1..max && it.none { c -> c.isISOControl() }
    } ?: throw IOException("Invalid qBittorrent version text")

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    internal companion object {
        fun stateOf(state: String): ClientRunState = when (state) {
            "pausedDL", "pausedUP", "stoppedDL", "stoppedUP" -> ClientRunState.STOPPED
            "downloading", "stalledDL", "forcedDL", "allocating" -> ClientRunState.DOWNLOADING
            "uploading", "stalledUP", "forcedUP" -> ClientRunState.SEEDING
            "checkingDL", "checkingUP", "checkingResumeData" -> ClientRunState.CHECKING
            "queuedDL", "queuedUP" -> ClientRunState.QUEUED
            "metaDL", "forcedMetaDL" -> ClientRunState.METADATA
            "moving" -> ClientRunState.MOVING
            "error", "missingFiles" -> ClientRunState.ERROR
            else -> ClientRunState.UNKNOWN
        }
    }
}
