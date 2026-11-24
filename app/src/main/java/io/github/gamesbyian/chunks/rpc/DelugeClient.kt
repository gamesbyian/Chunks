package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.model.RemoteBackendKind
import java.io.IOException
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Authenticated Deluge Web JSON-RPC client bound to ONE explicit daemon host
 * ID. Torrent methods are the daemon's own `core.*`, proxied by the Web UI.
 *
 * Deluge's Web UI keeps one daemon connection per web server. The client
 * reuses it when the selected host already reports Connected and otherwise
 * connects only the selected, registered, online host.
 *
 * Pre-execution failures (web session "Not authenticated", code 1) allow one
 * re-login. A daemon error result is a definite refusal; a lost reply on a
 * mutator is uncertain.
 */
internal class DelugeClient(
    credentials: RemoteCredentials,
    private val daemonHostId: String,
    private val wire: DelugeReadOnlyRpc.Wire = DelugeReadOnlyRpc.HttpWire
) : TorrentClient {
    override val kind = TorrentClientKind.DELUGE
    private val auth = credentials
    private val endpoint: URI = DelugeReadOnlyRpc.validateEndpoint(credentials.endpoint)
    private var cookie: String? = null
    private var daemonMethods: Set<String>? = null
    private var daemonVersion: String? = null
    private var nextId = 10

    init {
        require(daemonHostId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "Explicit Deluge daemon ID required" }
    }

    private val legacyPriorities: Boolean
        get() = daemonVersion?.substringBefore('.')?.toIntOrNull()?.let { it < 2 } ?: false

    override suspend fun session(): ClientSessionInfo {
        connect()
        val dir = (call("core.get_config_value", JSONArray().put("download_location")) as? String)
            ?.takeIf { it.startsWith("/") }
        val methods = daemonMethods.orEmpty()
        val caps = buildSet {
            add(ClientCapability.ADD_STOPPED); add(ClientCapability.ADD_TO_DIRECTORY)
            if ("core.add_torrent_file" in methods) add(ClientCapability.ADD_TORRENT_FILE)
            if ("core.add_torrent_magnet" in methods) add(ClientCapability.ADD_MAGNET)
            if (methods.any { it.startsWith("core.pause_torrent") } &&
                methods.any { it.startsWith("core.resume_torrent") }) add(ClientCapability.START_STOP)
            if ("core.set_torrent_options" in methods) add(ClientCapability.FILE_PRIORITIES)
            if ("core.remove_torrent" in methods) { add(ClientCapability.REMOVE); add(ClientCapability.REMOVE_WITH_DATA) }
            if ("core.move_storage" in methods) add(ClientCapability.SET_LOCATION)
            if ("core.force_recheck" in methods) add(ClientCapability.RECHECK)
            add(ClientCapability.EXPORT_JOB)
        }
        val levels = if (legacyPriorities)
            setOf(ClientFilePriority.SKIP, ClientFilePriority.NORMAL, ClientFilePriority.HIGH)
        else ClientFilePriority.entries.toSet()
        return ClientSessionInfo(kind, daemonVersion ?: "unknown", dir, caps, levels)
    }

    override suspend fun transferSource(hash: String): ClientAddSource? {
        val exact = ClientHashes.requireHash(hash)
        connect()
        val row = call("core.get_torrent_status", JSONArray().put(exact)
            .put(JSONArray(listOf("name", "trackers", "private")))) as? JSONObject ?: return null
        if (row.optBoolean("private", false)) return null // a bare magnet cannot carry a private job faithfully
        val trackers = row.optJSONArray("trackers")?.let { list ->
            (0 until list.length()).mapNotNull { (list.opt(it) as? JSONObject)?.optString("url") }
        }.orEmpty()
        return ClientMagnets.build(exact, row.optString("name"), trackers)
    }

    override suspend fun torrents(): List<ClientTorrent> = status(JSONObject())

    override suspend fun torrent(hash: String): ClientTorrent? {
        val exact = ClientHashes.requireHash(hash)
        return exactProtocolRow(status(JSONObject().put("id", JSONArray().put(exact))), exact) { it.hash }
    }

    override suspend fun files(hash: String): List<ClientFile> {
        val exact = ClientHashes.requireHash(hash)
        connect()
        val row = call("core.get_torrent_status", JSONArray().put(exact)
            .put(JSONArray(listOf("files", "file_priorities", "file_progress")))) as? JSONObject
            ?: throw IOException("Deluge file list unavailable")
        val files = row.optJSONArray("files") ?: throw ClientRefusedException("Deluge does not know this transfer")
        if (files.length() == 0) return emptyList()
        val priorities = row.optJSONArray("file_priorities") ?: JSONArray()
        if (priorities.length() != files.length()) throw IOException("Inconsistent Deluge file priorities")
        val progress = row.optJSONArray("file_progress")?.takeIf { it.length() == files.length() }
        return exactIndexedFiles(files).map { (index, file) ->
            val size = file.exactFileInteger("size")?.takeIf { it >= 0 }
                ?: throw IOException("Invalid Deluge file length")
            val priority = exactWireInteger(priorities.opt(index))?.takeIf { it in 0L..7L }
                ?: throw IOException("Invalid Deluge file priority")
            val fraction = exactProgressFraction(progress?.opt(index))
            ClientFile(index, file.exactFilePath("path"), size,
                fraction?.let { (size * it).toLong() },
                fromDeluge(priority.toInt()))
        }
    }

    override suspend fun add(request: ClientAddRequest) {
        connect()
        val options = JSONObject().put("add_paused", !request.startNow)
        request.directory?.let { options.put("download_location", it) }
        val result = when (val source = request.source) {
            is ClientAddSource.TorrentFile -> call("core.add_torrent_file", JSONArray()
                .put(source.infoHash + ".torrent")
                .put(Base64.getEncoder().encodeToString(TorrentMetainfoReader.forNewRemoteAdd(source.bytes)))
                .put(options), mutating = true)
            is ClientAddSource.Magnet -> call("core.add_torrent_magnet",
                JSONArray().put(source.uri).put(options), mutating = true)
        }
        val added = (result as? String)?.lowercase()
            ?: throw ClientRefusedException("Deluge declined the Add (duplicate or invalid)")
        if (added != request.source.infoHash) throw ClientWriteUncertainException("Deluge registered a different identity")
    }

    override suspend fun setStarted(hash: String, started: Boolean) {
        connect()
        val exact = ClientHashes.requireHash(hash)
        val verb = if (started) "resume" else "pause"
        val methods = daemonMethods.orEmpty()
        // Deluge 2 has <verb>_torrents(list) and single-id <verb>_torrent;
        // Deluge 1.3's <verb>_torrent takes a list.
        if ("core.${verb}_torrents" in methods) {
            call("core.${verb}_torrents", JSONArray().put(JSONArray().put(exact)), mutating = true)
        } else if (legacyPriorities) {
            call("core.${verb}_torrent", JSONArray().put(JSONArray().put(exact)), mutating = true)
        } else {
            call("core.${verb}_torrent", JSONArray().put(exact), mutating = true)
        }
    }

    override suspend fun remove(hash: String, deleteData: Boolean) = removeChecked(hash, deleteData) {}

    override suspend fun removeJob(hash: String, beforeWrite: () -> Unit) =
        removeChecked(hash, deleteData = false, beforeWrite = beforeWrite)

    private suspend fun removeChecked(hash: String, deleteData: Boolean, beforeWrite: () -> Unit) {
        connect()
        val removed = call("core.remove_torrent", JSONArray().put(ClientHashes.requireHash(hash)).put(deleteData),
            mutating = true, beforeWrite = beforeWrite)
        if (removed == false) throw ClientRefusedException("Deluge declined the removal")
    }

    override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) =
        setFilePriorities(hash, priorities) { true }

    override suspend fun setFilePriorities(
        hash: String,
        priorities: Map<Int, ClientFilePriority>,
        validateSnapshot: (List<ClientFile>) -> Boolean
    ) {
        val exact = ClientHashes.requireHash(hash)
        // Deluge replaces the whole vector; build it from a fresh read.
        val current = files(exact)
        val positional = current.sortedBy { it.index }
        if (positional.withIndex().any { (index, row) -> row.index != index } ||
            priorities.keys.any { it !in positional.indices } || !validateSnapshot(current)) {
            throw ClientRefusedException("Deluge file list changed; refresh first")
        }
        val vector = JSONArray()
        positional.forEach { file ->
            vector.put(toDeluge(priorities[file.index] ?: file.priority))
        }
        call("core.set_torrent_options", JSONArray().put(JSONArray().put(exact))
            .put(JSONObject().put("file_priorities", vector)), mutating = true)
    }

    override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) {
        if (!moveData) throw ClientRefusedException("Deluge can only change location by moving files")
        connect()
        call("core.move_storage", JSONArray().put(JSONArray().put(ClientHashes.requireHash(hash)))
            .put(ClientHashes.requireDirectory(directory)), mutating = true)
    }

    override suspend fun reannounce(hash: String) {
        connect()
        call("core.force_reannounce", JSONArray().put(JSONArray().put(ClientHashes.requireHash(hash))), mutating = true)
    }

    override suspend fun recheck(hash: String) {
        connect()
        call("core.force_recheck", JSONArray().put(JSONArray().put(ClientHashes.requireHash(hash))), mutating = true)
    }

    private fun toDeluge(p: ClientFilePriority): Int = if (legacyPriorities) when (p) {
        ClientFilePriority.SKIP -> 0
        ClientFilePriority.LOW, ClientFilePriority.NORMAL -> 1
        ClientFilePriority.HIGH -> 2
    } else when (p) {
        ClientFilePriority.SKIP -> 0
        ClientFilePriority.LOW -> 1
        ClientFilePriority.NORMAL -> 4
        ClientFilePriority.HIGH -> 7
    }

    private fun fromDeluge(v: Int): ClientFilePriority = if (legacyPriorities) when (v) {
        0 -> ClientFilePriority.SKIP
        1 -> ClientFilePriority.NORMAL
        else -> ClientFilePriority.HIGH
    } else when (v) {
        0 -> ClientFilePriority.SKIP
        in 1..3 -> ClientFilePriority.LOW
        in 5..7 -> ClientFilePriority.HIGH
        else -> ClientFilePriority.NORMAL
    }

    // Deluge's per-torrent max_upload_speed is KiB/s (a float; -1 = unlimited),
    // set with core.set_torrent_options. 1024-byte steps are stored exactly.
    override suspend fun uploadCapStepBytes(): Long? {
        connect()
        return 1024L.takeIf { "core.set_torrent_options" in daemonMethods.orEmpty() }
    }

    override suspend fun uploadCapBytesPerSecond(hash: String): Long? {
        val exact = ClientHashes.requireHash(hash)
        connect()
        val result = call("core.get_torrents_status",
            JSONArray().put(JSONObject().put("id", JSONArray().put(exact))).put(JSONArray().put("max_upload_speed"))) as? JSONObject
            ?: return null
        if (result.length() != 1) return null
        val row = result.keys().asSequence().firstOrNull { it.equals(exact, ignoreCase = true) }
            ?.let(result::getJSONObject) ?: return null
        if (!row.has("max_upload_speed")) return null
        val kib = row.optDouble("max_upload_speed", Double.NaN).takeIf { !it.isNaN() } ?: return null
        return if (kib <= 0.0) 0L else Math.round(kib * 1024.0)
    }

    override suspend fun setUploadCapBytesPerSecond(hash: String, bytesPerSecond: Long) {
        require(bytesPerSecond > 0 && bytesPerSecond % 1024L == 0L)
        connect()
        if ("core.set_torrent_options" !in daemonMethods.orEmpty())
            throw ClientRefusedException("Deluge cannot set a per-torrent upload limit")
        call("core.set_torrent_options", JSONArray().put(JSONArray().put(ClientHashes.requireHash(hash)))
            .put(JSONObject().put("max_upload_speed", (bytesPerSecond / 1024L).toDouble())), mutating = true)
    }

    override suspend fun seedingFacts(hash: String): ClientSeedingFacts? {
        val exact = ClientHashes.requireHash(hash)
        connect()
        suspend fun row(keys: List<String>): JSONObject? {
            val result = call("core.get_torrents_status",
                JSONArray().put(JSONObject().put("id", JSONArray().put(exact))).put(JSONArray(keys))) as? JSONObject
                ?: throw IOException("Deluge seeding facts unavailable")
            return exactProtocolRow(result.keys().asSequence().toList(), exact) { it }?.let(result::getJSONObject)
        }
        val r = row(listOf("total_uploaded", "all_time_download", "seeding_time", "state", "is_finished", "private"))
            ?: return null
        val private = if (r.has("private")) r.optBoolean("private") else null
        val trackers = if (private == false) row(listOf("trackers", "private"))?.let { t ->
            if (t.optBoolean("private", true)) null
            else t.optJSONArray("trackers")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("url")?.takeIf(String::isNotBlank) } }
        } else null
        fun count(key: String) = if (r.has(key)) r.optLong(key, -1).takeIf { it >= 0 } else null
        return ClientSeedingFacts(exact, count("total_uploaded"), count("all_time_download"), count("seeding_time"),
            seeding = if (r.has("state")) r.optString("state") == "Seeding" && r.optBoolean("is_finished") else null,
            isPrivate = private, publicTrackerUrls = trackers)
    }

    private suspend fun status(filter: JSONObject): List<ClientTorrent> {
        connect()
        val keys = JSONArray(listOf("name", "state", "total_wanted", "total_done",
            "download_payload_rate", "upload_payload_rate", "save_path"))
        val result = call("core.get_torrents_status", JSONArray().put(filter).put(keys)) as? JSONObject
            ?: throw IOException("Deluge inventory unavailable")
        val names = result.keys().asSequence().toList()
        if (names.size > 50_000) throw IOException("Deluge inventory exceeds safety limit")
        val seen = HashSet<String>()
        return names.map { raw ->
            val h = raw.lowercase()
            if (h.length != 40 || !seen.add(h)) throw IOException("Ambiguous Deluge identity")
            val row = result.getJSONObject(raw)
            val total = row.optLong("total_wanted", -1).takeIf { it > 0 }
            val done = row.optLong("total_done", -1).takeIf { it >= 0 }
            ClientTorrent(
                hash = h,
                name = row.optString("name").ifBlank { h },
                runState = when (row.optString("state")) {
                    "Paused" -> ClientRunState.STOPPED
                    "Downloading", "Allocating" -> ClientRunState.DOWNLOADING
                    "Seeding" -> ClientRunState.SEEDING
                    "Checking" -> ClientRunState.CHECKING
                    "Queued" -> ClientRunState.QUEUED
                    "Moving" -> ClientRunState.MOVING
                    "Error" -> ClientRunState.ERROR
                    else -> ClientRunState.UNKNOWN
                },
                totalBytes = total,
                completedBytes = done?.takeIf { total != null && it <= total },
                downloadBytesPerSecond = row.optLong("download_payload_rate", -1).takeIf { it >= 0 },
                uploadBytesPerSecond = row.optLong("upload_payload_rate", -1).takeIf { it >= 0 },
                downloadDirectory = row.optString("save_path").takeIf { it.startsWith("/") }
            )
        }
    }

    /** Login (once), confirm the exact registered host, and connect only it. */
    private suspend fun connect() {
        if (daemonMethods != null && call("web.connected", JSONArray()) == true) return
        val hosts = call("web.get_hosts", JSONArray()) as? JSONArray
            ?: throw IOException("Deluge did not return a host list")
        val matches = (0 until hosts.length()).count { (hosts.opt(it) as? JSONArray)?.opt(0) == daemonHostId }
        if (matches != 1) throw ClientRefusedException("Deluge daemon ID is missing or ambiguous")
        val status = call("web.get_host_status", JSONArray().put(daemonHostId)) as? JSONArray
            ?: throw IOException("Deluge daemon status unavailable")
        if (status.opt(0) != daemonHostId) throw IOException("Deluge host status belonged to another daemon")
        val state = (status.opt(1) as? String)?.lowercase()
        daemonVersion = (status.opt(2) as? String)?.takeIf { it.isNotBlank() && it.length <= 128 }
        val methods = when (state) {
            "connected" -> (call("daemon.get_method_list", JSONArray()) as? JSONArray)
            "online" -> (call("web.connect", JSONArray().put(daemonHostId)) as? JSONArray)
            else -> throw ClientRefusedException("Selected Deluge daemon is unavailable")
        } ?: throw IOException("Deluge daemon method list unavailable")
        if (call("web.connected", JSONArray()) != true) throw IOException("Deluge Web UI is not connected")
        daemonMethods = (0 until methods.length()).mapNotNull { methods.opt(it) as? String }
            .map { if (it.contains('.')) it else "core.$it" }.toSet()
        if (daemonVersion == null) {
            daemonVersion = (call("daemon.info", JSONArray()) as? String)?.take(128)
        }
    }

    private suspend fun call(
        method: String, params: JSONArray, mutating: Boolean = false, beforeWrite: () -> Unit = {}
    ): Any? =
        withContext(Dispatchers.IO) {
            var relogged = false
            while (true) {
                val session = cookie ?: login().also { cookie = it }
                val id = nextId++
                val body = JSONObject().put("method", method).put("params", params).put("id", id)
                    .toString().toByteArray(Charsets.UTF_8)
                if (mutating) RemoteWriteOwnerFence.check()
                beforeWrite()
                val response = try {
                    wire.exchange(DelugeReadOnlyRpc.Request(endpoint, body, session))
                } catch (e: CancellationException) { throw e } catch (_: Exception) {
                    if (mutating) throw ClientWriteUncertainException("Deluge reply lost")
                    throw IOException("Deluge RPC transport failed")
                }
                if (response.status != 200) {
                    if (mutating && response.status >= 500) throw ClientWriteUncertainException("Deluge server error")
                    throw ClientRefusedException("Deluge HTTP ${response.status}")
                }
                val exactRead = method == "core.get_torrent_status" ||
                    (method == "core.get_torrents_status" && params.optJSONObject(0)?.has("id") == true)
                val json = try {
                    if (exactRead) ExactRpcJson.objectBody(response.body) else JSONObject(response.body)
                } catch (_: Exception) {
                    if (mutating) throw ClientWriteUncertainException("Invalid Deluge reply")
                    throw IOException("Malformed Deluge JSON-RPC reply")
                }
                val reply = json.opt("id")
                val replyId = if (exactRead) reply as? Long else (reply as? Number)?.toInt()?.toLong()
                if (replyId != id.toLong()) {
                    if (mutating) throw ClientWriteUncertainException("Mismatched Deluge reply")
                    throw IOException("Mismatched Deluge reply")
                }
                val error = json.opt("error")
                if (error is JSONObject) {
                    if (error.optInt("code") == 1 && !relogged) {
                        // Web session not authenticated: rejected before dispatch.
                        cookie = null
                        relogged = true
                        continue
                    }
                    throw ClientRefusedException("Deluge refused: " +
                        error.optString("message").lineSequence().firstOrNull().orEmpty().take(120))
                }
                return@withContext json.opt("result").takeUnless { it == JSONObject.NULL }
            }
            @Suppress("UNREACHABLE_CODE") null
        }

    private suspend fun login(): String {
        if (auth.password.isEmpty()) throw ClientRefusedException("Deluge web password is required")
        val body = JSONObject().put("method", "auth.login").put("params", JSONArray().put(auth.password))
            .put("id", 1).toString().toByteArray(Charsets.UTF_8)
        val response = try { wire.exchange(DelugeReadOnlyRpc.Request(endpoint, body, null)) }
            catch (e: CancellationException) { throw e } catch (_: Exception) {
                throw IOException("Deluge connection failed")
            }
        val ok = response.status == 200 && try { JSONObject(response.body).opt("result") == true } catch (_: Exception) { false }
        if (!ok) throw ClientRefusedException("Deluge Web UI login refused")
        val sid = response.header("Set-Cookie").mapNotNull { v ->
            v.substringBefore(';').trim().takeIf { it.startsWith("_session_id=") }?.removePrefix("_session_id=")
        }
        if (sid.size != 1 || !Regex("[A-Za-z0-9._~%-]{8,256}").matches(sid.single()))
            throw ClientRefusedException("Deluge Web UI session cookie unavailable")
        return "_session_id=" + sid.single()
    }
}
