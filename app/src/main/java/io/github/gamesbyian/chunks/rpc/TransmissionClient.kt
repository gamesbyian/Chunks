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
 * Authenticated Transmission client over its documented JSON RPC.
 *
 * Uses the legacy request scheme, which Transmission 4.1+ deliberately keeps
 * for compatibility, so one tested dialect covers 2.x-4.x. Torrents are
 * always addressed by hash string, never by the server's ephemeral integer.
 *
 * Retry policy: only the protocol-defined pre-execution rejections (409
 * session renewal, 401 challenge) are answered and resent, once each. Any
 * transport failure on a mutator is [ClientWriteUncertainException].
 */
internal class TransmissionClient(
    credentials: RemoteCredentials,
    private val wire: TransmissionReadOnlyRpc.Wire = TransmissionReadOnlyRpc.HttpWire
) : TorrentClient {
    override val kind = TorrentClientKind.TRANSMISSION
    private val auth = credentials
    private val endpoint: URI = TransmissionReadOnlyRpc.validateEndpoint(credentials.endpoint)
    private var csrfSession: String? = null
    private var authorization: String? = null

    override suspend fun session(): ClientSessionInfo {
        val args = call("session-get", JSONObject(), mutating = false)
        val version = args.optString("version").takeIf { it.isNotBlank() && it.length <= 128 }
            ?: throw IOException("Transmission version unavailable")
        val dir = args.optString("download-dir").takeIf { it.startsWith("/") }
        return ClientSessionInfo(kind, version, dir, ClientCapability.entries.toSet() - ClientCapability.ADD_WITH_FILE_CHOICES)
    }

    override suspend fun transferSource(hash: String): ClientAddSource? {
        val exact = ClientHashes.requireHash(hash)
        val rows = call("torrent-get", JSONObject().put("ids", JSONArray().put(exact))
            .put("fields", JSONArray(listOf("hashString", "magnetLink"))), mutating = false)
            .optJSONArray("torrents") ?: return null
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }
            .singleOrNull { it.optString("hashString").equals(exact, true) } ?: return null
        val magnet = row.optString("magnetLink").takeIf { it.startsWith("magnet:?") } ?: return null
        // Transmission's own magnet carries this job's trackers and name.
        return runCatching { ClientAddSource.Magnet(magnet) }.getOrNull()?.takeIf { it.infoHash == exact }
    }

    override suspend fun torrents(): List<ClientTorrent> = get(null).map(::torrentOf)

    override suspend fun torrent(hash: String): ClientTorrent? {
        val exact = ClientHashes.requireHash(hash)
        return exactProtocolRow(get(listOf(exact)), exact) { it.optString("hashString") }?.let(::torrentOf)
    }

    override suspend fun files(hash: String): List<ClientFile> {
        val exact = ClientHashes.requireHash(hash)
        val args = call("torrent-get", JSONObject()
            .put("ids", JSONArray().put(exact))
            .put("fields", JSONArray(listOf("hashString", "files", "fileStats"))), mutating = false)
        val rows = args.optJSONArray("torrents") ?: throw IOException("Transmission file list unavailable")
        val row = exactProtocolRow((0 until rows.length()).map { rows.getJSONObject(it) }, exact) { it.optString("hashString") }
            ?: throw IOException("Transmission transfer not found")
        val files = row.optJSONArray("files") ?: throw IOException("Transmission file list unavailable")
        val stats = row.optJSONArray("fileStats") ?: throw IOException("Transmission file list unavailable")
        if (files.length() != stats.length()) throw IOException("Inconsistent Transmission file list")
        return (0 until files.length()).map { i ->
            val file = files.getJSONObject(i)
            val stat = stats.getJSONObject(i)
            ClientFile(
                index = i,
                path = file.getString("name"),
                sizeBytes = file.getLong("length"),
                completedBytes = file.optLong("bytesCompleted", -1).takeIf { it >= 0 },
                priority = if (!stat.optBoolean("wanted", true)) ClientFilePriority.SKIP
                    else when (stat.optInt("priority", 0)) {
                        -1 -> ClientFilePriority.LOW
                        1 -> ClientFilePriority.HIGH
                        else -> ClientFilePriority.NORMAL
                    }
            )
        }
    }

    override suspend fun add(request: ClientAddRequest) {
        val args = JSONObject().put("paused", !request.startNow)
        when (val source = request.source) {
            is ClientAddSource.TorrentFile -> args.put("metainfo",
                Base64.getEncoder().encodeToString(TorrentMetainfoReader.forNewRemoteAdd(source.bytes)))
            is ClientAddSource.Magnet -> args.put("filename", source.uri)
        }
        request.directory?.let { args.put("download-dir", it) }
        val result = call("torrent-add", args, mutating = true)
        if (result.has("torrent-duplicate")) throw ClientRefusedException("Already in this client")
        val added = result.optJSONObject("torrent-added")
            ?: throw ClientWriteUncertainException("Transmission did not confirm the Add")
        if (!added.optString("hashString").equals(request.source.infoHash, true)) {
            throw ClientWriteUncertainException("Transmission registered a different identity")
        }
    }

    override suspend fun setStarted(hash: String, started: Boolean) {
        call(if (started) "torrent-start" else "torrent-stop", ids(hash), mutating = true)
    }

    override suspend fun remove(hash: String, deleteData: Boolean) = removeChecked(hash, deleteData) {}

    override suspend fun removeJob(hash: String, beforeWrite: () -> Unit) =
        removeChecked(hash, deleteData = false, beforeWrite = beforeWrite)

    private suspend fun removeChecked(hash: String, deleteData: Boolean, beforeWrite: () -> Unit) {
        call("torrent-remove", ids(hash).put("delete-local-data", deleteData), mutating = true, beforeWrite = beforeWrite)
    }

    override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) {
        val args = ids(hash)
        fun indices(p: (ClientFilePriority) -> Boolean) =
            JSONArray(priorities.filterValues(p).keys.sorted())
        val wanted = indices { it != ClientFilePriority.SKIP }
        val unwanted = indices { it == ClientFilePriority.SKIP }
        if (wanted.length() > 0) args.put("files-wanted", wanted)
        if (unwanted.length() > 0) args.put("files-unwanted", unwanted)
        indices { it == ClientFilePriority.HIGH }.takeIf { it.length() > 0 }?.let { args.put("priority-high", it) }
        indices { it == ClientFilePriority.LOW }.takeIf { it.length() > 0 }?.let { args.put("priority-low", it) }
        indices { it == ClientFilePriority.NORMAL }.takeIf { it.length() > 0 }?.let { args.put("priority-normal", it) }
        call("torrent-set", args, mutating = true)
    }

    override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) {
        call("torrent-set-location", ids(hash)
            .put("location", ClientHashes.requireDirectory(directory)).put("move", moveData), mutating = true)
    }

    override suspend fun reannounce(hash: String) {
        call("torrent-reannounce", ids(hash), mutating = true)
    }

    override suspend fun recheck(hash: String) {
        call("torrent-verify", ids(hash), mutating = true)
    }

    override suspend fun seedingFacts(hash: String): ClientSeedingFacts? {
        val exact = ClientHashes.requireHash(hash)
        suspend fun row(fields: List<String>): JSONObject? {
            val rows = call("torrent-get", JSONObject().put("fields", JSONArray(fields + "hashString"))
                .put("ids", JSONArray().put(exact)), mutating = false).optJSONArray("torrents")
                ?: throw IOException("Transmission seeding facts unavailable")
            return exactProtocolRow((0 until rows.length()).map { rows.getJSONObject(it) }, exact) { it.optString("hashString") }
        }
        val r = row(listOf("uploadedEver", "downloadedEver", "secondsSeeding", "status", "leftUntilDone", "isPrivate"))
            ?: return null
        val private = if (r.has("isPrivate")) r.optBoolean("isPrivate") else null
        // Tracker URLs only for a reported-public transfer, re-checked after the read.
        val trackers = if (private == false) row(listOf("trackers", "isPrivate"))?.let { t ->
            if (t.optBoolean("isPrivate", true)) null
            else t.optJSONArray("trackers")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("announce")?.takeIf(String::isNotBlank) } }
        } else null
        fun count(key: String) = if (r.has(key)) r.optLong(key, -1).takeIf { it >= 0 } else null
        return ClientSeedingFacts(exact, count("uploadedEver"), count("downloadedEver"), count("secondsSeeding"),
            seeding = if (r.has("status")) r.optInt("status") == 6 && r.optLong("leftUntilDone", 1) == 0L else null,
            isPrivate = private, publicTrackerUrls = trackers)
    }

    private fun ids(hash: String) = JSONObject().put("ids", JSONArray().put(ClientHashes.requireHash(hash)))

    // Transmission's per-torrent uploadLimit is in its speed unit ("units"."speed-bytes",
    // 1000 by default), enabled by uploadLimited.
    override suspend fun uploadCapStepBytes(): Long? {
        val units = call("session-get", JSONObject().put("fields", JSONArray().put("units")), mutating = false)
            .optJSONObject("units") ?: return null
        return units.optLong("speed-bytes", 0L).takeIf { it == 1000L || it == 1024L }
    }

    override suspend fun uploadCapBytesPerSecond(hash: String): Long? {
        val exact = ClientHashes.requireHash(hash)
        val step = uploadCapStepBytes() ?: return null
        val rows = call("torrent-get", JSONObject().put("fields", JSONArray(listOf("hashString", "uploadLimit", "uploadLimited")))
            .put("ids", JSONArray().put(exact)), mutating = false).optJSONArray("torrents") ?: return null
        if (rows.length() != 1) return null
        val r = rows.getJSONObject(0).takeIf { it.optString("hashString").equals(exact, ignoreCase = true) } ?: return null
        if (!r.has("uploadLimited") || !r.has("uploadLimit")) return null
        return if (!r.optBoolean("uploadLimited")) 0L else r.optLong("uploadLimit") * step
    }

    override suspend fun setUploadCapBytesPerSecond(hash: String, bytesPerSecond: Long) {
        val step = uploadCapStepBytes() ?: throw ClientRefusedException("Transmission speed unit unknown")
        require(bytesPerSecond > 0 && bytesPerSecond % step == 0L)
        call("torrent-set", ids(hash).put("uploadLimit", bytesPerSecond / step).put("uploadLimited", true), mutating = true)
    }

    private suspend fun get(hashes: List<String>?): List<JSONObject> {
        val args = JSONObject().put("fields", JSONArray(FIELDS))
        hashes?.let { args.put("ids", JSONArray(it)) }
        val rows = call("torrent-get", args, mutating = false).optJSONArray("torrents")
            ?: throw IOException("Transmission inventory was incomplete")
        if (rows.length() > 50_000) throw IOException("Transmission inventory exceeded safety limit")
        val list = (0 until rows.length()).map {
            rows.optJSONObject(it) ?: throw IOException("Invalid Transmission inventory row")
        }
        val seen = HashSet<String>()
        list.forEach {
            val h = it.optString("hashString").lowercase()
            if (h.length != 40 || !seen.add(h)) throw IOException("Ambiguous Transmission identities")
        }
        return list
    }

    private fun torrentOf(row: JSONObject): ClientTorrent {
        val total = row.optLong("totalSize", -1).takeIf { it >= 0 }
        val left = row.optLong("leftUntilDone", -1).takeIf { it >= 0 }
        val metadataDone = row.optDouble("metadataPercentComplete", 1.0) >= 1.0
        val state = when (row.optInt("status", -1)) {
            0 -> ClientRunState.STOPPED
            1, 2 -> ClientRunState.CHECKING
            3, 5 -> ClientRunState.QUEUED
            4 -> if (metadataDone) ClientRunState.DOWNLOADING else ClientRunState.METADATA
            6 -> ClientRunState.SEEDING
            else -> ClientRunState.UNKNOWN
        }
        return ClientTorrent(
            hash = row.getString("hashString").lowercase(),
            name = row.optString("name").ifBlank { row.getString("hashString") },
            runState = if (row.optInt("error", 0) == 3 && state != ClientRunState.STOPPED)
                ClientRunState.ERROR else state,
            totalBytes = total?.takeIf { metadataDone },
            completedBytes = if (total != null && left != null && left <= total && metadataDone) total - left else null,
            downloadBytesPerSecond = row.optLong("rateDownload", -1).takeIf { it >= 0 },
            uploadBytesPerSecond = row.optLong("rateUpload", -1).takeIf { it >= 0 },
            downloadDirectory = row.optString("downloadDir").takeIf { it.startsWith("/") }
        )
    }

    /**
     * One logical request. Pre-execution 401/409 are answered once each;
     * a mutator's transport failure or unparseable 2xx is uncertain.
     */
    private suspend fun call(
        method: String, args: JSONObject, mutating: Boolean, beforeWrite: () -> Unit = {}
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("method", method).put("arguments", args)
                .toString().toByteArray(Charsets.UTF_8)
            var answeredAuth = false
            var answeredSession = false
            while (true) {
                beforeWrite()
                val response = try {
                    wire.exchange(TransmissionReadOnlyRpc.Request(endpoint, body, authorization, csrfSession))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (mutating) throw ClientWriteUncertainException("Transmission reply lost")
                    throw IOException("Transmission RPC transport failed")
                }
                when (response.status) {
                    409 -> {
                        if (answeredSession) throw ClientRefusedException("Transmission session negotiation failed")
                        val token = response.header("X-Transmission-Session-Id").singleOrNull()
                        if (token == null || token.length !in 1..256 || token.any { it.code !in 33..126 })
                            throw ClientRefusedException("Invalid Transmission session challenge")
                        csrfSession = token
                        answeredSession = true
                    }
                    401 -> {
                        if (answeredAuth || auth.username.isEmpty()) throw ClientRefusedException("Transmission login refused")
                        authorization = try {
                            HttpRpcAuthorization.fromChallenge(
                                response.header("WWW-Authenticate"), auth, "POST", endpoint.rawPath)
                        } catch (_: IOException) {
                            throw ClientRefusedException("Transmission login refused")
                        }
                        answeredAuth = true
                    }
                    in 200..299 -> {
                        val root = try { JSONObject(response.body) } catch (_: Exception) {
                            if (mutating) throw ClientWriteUncertainException("Invalid Transmission reply")
                            throw IOException("Invalid Transmission RPC response")
                        }
                        val result = root.optString("result")
                        if (result != "success") {
                            // The server parsed and executed the request and
                            // reported a definite refusal (e.g. invalid metainfo).
                            throw ClientRefusedException("Transmission refused: " + result.take(120))
                        }
                        return@withContext root.optJSONObject("arguments") ?: JSONObject()
                    }
                    403 -> throw ClientRefusedException("Transmission denied this request")
                    else -> {
                        if (mutating && response.status >= 500)
                            throw ClientWriteUncertainException("Transmission server error")
                        throw ClientRefusedException("Transmission HTTP " + response.status)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") JSONObject()
        }

    private companion object {
        val FIELDS = listOf(
            "hashString", "name", "status", "error", "totalSize", "leftUntilDone",
            "metadataPercentComplete", "rateDownload", "rateUpload", "downloadDir"
        )
    }
}
