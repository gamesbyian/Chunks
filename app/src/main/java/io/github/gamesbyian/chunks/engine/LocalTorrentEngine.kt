package io.github.gamesbyian.chunks.engine

import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AnnounceEntry
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Entry
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.address
import org.libtorrent4j.swig.entry
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.ip_filter
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t

/**
 * In-process local BitTorrent engine on Arvid Norberg's libtorrent (via libtorrent4j).
 * This is NOT the rakshasa libTorrent used by rTorrent; nothing here speaks RPC.
 *
 * Deliberately conservative first slice: explicit save directory per Add, Adds
 * start stopped and unmanaged (no queue may auto-start them), exact-infohash
 * targeting for every command, session-only removal that keeps payload bytes.
 * Background lifetime, foreground service and resume data are separate gates.
 */
/** libtorrent4j's built-in disk implementations. This is not an Android SAF or MediaStore adapter. */
enum class LocalDiskIoMode { DEFAULT, POSIX }

data class LocalEngineConfig(
    /** libtorrent listen_interfaces, e.g. "0.0.0.0:6881" or "127.0.0.1:0" for tests. */
    val listenInterfaces: String = "0.0.0.0:0,[::]:0",
    val enableDht: Boolean = false,
    val enableLsd: Boolean = false,
    val enablePortMapping: Boolean = false,
    /** Required for same-host tests where every peer is 127.0.0.1. */
    val allowMultipleConnectionsPerIp: Boolean = false,
    /**
     * App-private directory for per-transfer resume files. When set, transfers
     * survive engine restart and process death; restored transfers come back stopped.
     */
    val stateDirectory: File? = null,
    /**
     * Apply speed limits to local-network and loopback peers too. libtorrent
     * exempts them by default; tests use this to observe throttling over loopback.
     */
    val rateLimitLocalPeers: Boolean = false,
    /** For controlled compatibility tests. The app continues using libtorrent's default backend. */
    val diskIoMode: LocalDiskIoMode = LocalDiskIoMode.DEFAULT
)

enum class LocalTransferState { CHECKING, DOWNLOADING_METADATA, DOWNLOADING, FINISHED, SEEDING, UNKNOWN }

data class LocalTransferStatus(
    /** 40 hex (v1) or 64 hex (v2-only) lower-case identity. */
    val infoHash: String,
    val name: String,
    val state: LocalTransferState,
    val stopped: Boolean,
    val progressPpm: Int,
    val wantedBytes: Long,
    val doneBytes: Long,
    val payloadDownloadedBytes: Long,
    val payloadUploadedBytes: Long,
    val peers: Int,
    val savePath: String,
    val error: String?,
    /** False while a magnet is still fetching its metadata. */
    val metadataKnown: Boolean = true,
    /** libtorrent can be running without requesting payload when in upload-only mode. */
    val uploadOnly: Boolean = false,
    /** Counts only: tracker URLs can embed private passkeys and are never exposed. */
    val trackers: LocalTrackerSummary? = null,
    /** Current native payload rates, sampled by libtorrent in bytes per second. */
    val downloadPayloadRateBytesPerSecond: Int = 0,
    val uploadPayloadRateBytesPerSecond: Int = 0,
    /** When libtorrent first added this job (kept in resume data); null if unreported. */
    val addedAtEpochMillis: Long? = null
)

/** Aggregate tracker health for one transfer, without URLs or tracker messages. */
data class LocalTrackerSummary(val total: Int, val working: Int, val updating: Int, val failing: Int)

/** One file inside a local transfer; `wanted = false` means libtorrent will not download it. */
data class LocalTransferFile(
    val index: Int,
    val path: String,
    val sizeBytes: Long,
    val doneBytes: Long,
    val wanted: Boolean,
    /** Native libtorrent file priority from IGNORE (0) to TOP (7). */
    val priority: Int = if (wanted) 4 else 0
)

/** Bytes per second; 0 is unlimited. */
data class LocalRateLimits(val downloadBytesPerSecond: Int = 0, val uploadBytesPerSecond: Int = 0) {
    init {
        require(downloadBytesPerSecond >= 0 && uploadBytesPerSecond >= 0) { "Speed limits cannot be negative" }
    }
}

/** libtorrent applies pause/resume asynchronously; only a matching readback is VERIFIED. */
enum class LocalCommandOutcome { VERIFIED, UNCERTAIN, NOT_FOUND }

data class LocalCommandResult(val outcome: LocalCommandOutcome, val status: LocalTransferStatus?)

/** Exact v1 identity-gated, stopped-only repair of a legacy transfer with no trackers. */
enum class LocalTrackerRepairOutcome { VERIFIED, REFUSED, UNCERTAIN, NOT_FOUND }

sealed class LocalAddResult {
    data class AddedStopped(val status: LocalTransferStatus) : LocalAddResult()
    /** A magnet is fetching only its metadata; it stops by itself before any payload download. */
    data class FetchingMetadata(val status: LocalTransferStatus) : LocalAddResult()
    data class Refused(val reason: String) : LocalAddResult()
}

class LocalTorrentEngine(private val config: LocalEngineConfig = LocalEngineConfig()) : AutoCloseable {
    private val session = SessionManager(false)
    // Guarded by its own monitor so the alert thread never needs the engine lock.
    private val handles: MutableMap<String, TorrentHandle> = Collections.synchronizedMap(LinkedHashMap())
    // Keep timed-out requests pending until their real alert arrives: a late
    // alert must not confirm a different save generation.
    private val pendingSaves = LocalResumeSaveRequests<TorrentHandle>()

    // Cache flushes need the same handle-bound completion semantics as resume
    // saves. In particular, a late alert for a removed torrent must never
    // confirm the cache of a replacement with the same infohash.
    private val pendingFlushes = LocalCacheFlushGate<TorrentHandle>(this)
    @Volatile private var started = false

    /** Resume files that could not be restored on start; they are left untouched. */
    @Volatile var restoreProblems: List<String> = emptyList()
        private set

    /** Magnets still fetching metadata in upload mode (no payload requests). */
    private val awaitingMetadata: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Explicit Add-and-start consent while magnet metadata is still unresolved. */
    private val startWhenMetadataArrives: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val magnetCollisionProblems = ConcurrentHashMap<String, String>()
    // Metadata-ready upload-only magnets are intentionally persisted paused;
    // native UPLOAD_MODE remains their durable don't-write-payload marker.
    // The next explicit Start rechecks paths, including after process restart.

    private val engineListener = object : AlertListener {
        override fun types() = intArrayOf(AlertType.SAVE_RESUME_DATA.swig(),
            AlertType.SAVE_RESUME_DATA_FAILED.swig(), AlertType.METADATA_RECEIVED.swig(),
            AlertType.CACHE_FLUSHED.swig())
        override fun alert(alert: Alert<*>) {
            val handle = (alert as? TorrentAlert<*>)?.handle() ?: return
            val key = synchronized(handles) { handles.entries.firstOrNull { it.value == handle }?.key } ?: return
            if (alert.type() == AlertType.CACHE_FLUSHED) {
                pendingFlushes.complete(key, handle)
                return
            }
            if (alert.type() == AlertType.METADATA_RECEIVED) {
                // Metadata discovery uses upload-only mode, never payload pieces.
                // A prior reviewed Add-and-start permits downloading AFTER the
                // metadata arrives. Otherwise stop and require manual Start.
                transitionMagnetMetadata(key, handle)
                return
            }
            var persisted = false
            try {
                // A magnet without metadata has nothing restorable yet; never persist it half-known.
                if (alert is SaveResumeDataAlert && alert.params().torrentInfo?.isValid == true) {
                    val bytes = AddTorrentParams.writeResumeDataBuf(alert.params())
                    // The handle may have been removed while the alert was being decoded.
                    // Lock across both identity recheck and disk commit; Remove uses this
                    // same lock before deleting the saved resume file.
                    LocalResumeWriteFence.writeIfCurrent(handles, key, handle) {
                        writeResume(key, bytes)
                        persisted = true
                    }
                }
            } catch (_: Exception) {
                // Corrupt native data or a failed fsync/atomic rename is not a
                // confirmed save. Do not let an exception escape the alert
                // dispatcher or overwrite the previous verified resume file.
            } finally {
                pendingSaves.complete(key, handle, persisted)
            }
        }
    }

    /**
     * Metadata-ready magnets must prove their prospective destination paths
     * are free of unreviewed files BEFORE leaving UPLOAD_MODE. A blocked
     * magnet can retry after the user clears the conflicting file.
     */
    private fun transitionMagnetMetadata(key: String, handle: TorrentHandle) {
        synchronized(this) {
            if (!started || handles[key] != handle || !awaitingMetadata.remove(key)) return
            val autoStart = startWhenMetadataArrives.remove(key)
            val problem = inspectMagnetCollision(handle)
            if (problem != null) magnetCollisionProblems[key] = problem
            else magnetCollisionProblems.remove(key)
            if (problem != null || !autoStart) {
                // Don't clear UPLOAD_MODE while the native pause is still
                // asynchronous. A paused magnet can persist and be revived
                // only by a *fresh* path survey on explicit Start.
                handle.pause()
            } else {
                handle.unsetFlags(org.libtorrent4j.TorrentFlags.UPLOAD_MODE)
                handle.resume()
            }
            requestMetadataResumeSave(key, handle)
        }
    }

    private fun inspectMagnetCollision(handle: TorrentHandle): String? {
        val paths = try {
            val info = handle.torrentFile() ?: return "Metadata file layout is unavailable"
            val files = info.files()
            (0 until files.numFiles()).asSequence()
                .filterNot { files.padFileAt(it) }.map { files.filePath(it) }.toList()
        } catch (_: Exception) { return "Torrent file layout cannot be inspected" }
        if (paths.isEmpty()) return "Torrent has no inspectable payload files"
        return when (val survey = LocalPayloadCollisionSurvey.inspect(File(handle.savePath()), paths)) {
            is LocalPayloadCollisionSurvey.Result.ClearAtObservation -> null
            is LocalPayloadCollisionSurvey.Result.ExistingPaths ->
                "Existing files conflict with this magnet (" + survey.total +
                    " path(s)); choose a clean destination or review existing data"
            is LocalPayloadCollisionSurvey.Result.Unknown ->
                "Cannot verify magnet payload paths: " + survey.problem
        }
    }

    /**
     * Request a non-blocking resume snapshot as soon as magnet metadata exists.
     * The initial Add/metadata callback ordering is asynchronous; both entry
     * points call this, and pendingSaves coalesces duplicates for this handle.
     * Serialize registration against Remove/Close but NEVER await an alert here.
     */
    private fun requestMetadataResumeSave(key: String, handle: TorrentHandle) {
        if (config.stateDirectory == null) return
        synchronized(this) {
            if (!started || handles[key] != handle) return
            pendingSaves.acquire(key, handle) {
                handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
            }
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        val pack = SettingsPack().listenInterfaces(config.listenInterfaces)
        pack.setEnableDht(config.enableDht)
        pack.setEnableLsd(config.enableLsd)
        pack.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), config.enablePortMapping)
        pack.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), config.enablePortMapping)
        pack.setBoolean(settings_pack.bool_types.allow_multiple_connections_per_ip.swigValue(),
            config.allowMultipleConnectionsPerIp)
        val params = SessionParams(pack)
        if (config.diskIoMode == LocalDiskIoMode.POSIX) params.setPosixDiskIO()
        session.addListener(engineListener)
        session.start(params)
        if (config.rateLimitLocalPeers) {
            // Every address in the global peer class only, dropping the exempt local class.
            val filter = ip_filter()
            val none = error_code()
            filter.add_rule(address.from_string("0.0.0.0", none), address.from_string("255.255.255.255", none), 1)
            filter.add_rule(address.from_string("::", none),
                address.from_string("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", none), 1)
            session.swig().set_peer_class_filter(filter)
        }
        started = true
        config.stateDirectory?.let { directory ->
            require(directory.isDirectory || directory.mkdirs()) { "Engine state directory is unavailable" }
            restore(directory)
        }
    }

    /**
     * Opt-in DHT (and local peer discovery stays off): lets trackerless magnets
     * find peers, at the cost of announcing this device's address to the DHT.
     * Applied to the running session; returns whether libtorrent reports DHT on.
     */
    @Synchronized
    fun setDhtEnabled(enabled: Boolean, readbackTimeoutMs: Long = 5_000): Boolean {
        check(started) { "Engine not started" }
        val pack = SettingsPack()
        pack.setEnableDht(enabled)
        session.applySettings(pack)
        val deadline = System.nanoTime() + readbackTimeoutMs * 1_000_000
        while (session.isDhtRunning != enabled && System.nanoTime() < deadline) Thread.sleep(25)
        return session.isDhtRunning
    }

    fun isDhtEnabled(): Boolean = started && session.isDhtRunning

    /**
     * Session-wide speed limits in bytes per second; 0 means unlimited. Returns
     * the limits libtorrent reads back. libtorrent's default peer classes leave
     * local-network peers unthrottled unless [LocalEngineConfig.rateLimitLocalPeers].
     */
    @Synchronized
    fun setRateLimits(limits: LocalRateLimits): LocalRateLimits {
        check(started) { "Engine not started" }
        val pack = SettingsPack()
        pack.downloadRateLimit(limits.downloadBytesPerSecond)
        pack.uploadRateLimit(limits.uploadBytesPerSecond)
        session.applySettings(pack)
        return rateLimits()
    }

    fun rateLimits(): LocalRateLimits {
        check(started) { "Engine not started" }
        val current = session.settings()
        return LocalRateLimits(current.downloadRateLimit(), current.uploadRateLimit())
    }

    /** The bound TCP listen port, or 0 if the session is not listening. */
    fun listenPort(): Int = session.swig()?.listen_port() ?: 0

    /**
     * Production Add: inspect torrent-relative payload paths before registering
     * a job. The raw addStopped method remains for separately reviewed
     * existing-file seeding and native interoperability fixtures.
     *
     * A clean survey is point-in-time only, not a race-free write capability.
     * It does not authorize consuming a matching file merely by name.
     */
    @Synchronized
    fun addStoppedChecked(
        torrentBytes: ByteArray,
        saveDirectory: File,
        peers: List<InetSocketAddress> = emptyList()
    ): LocalAddResult {
        check(started) { "Engine not started" }
        if (!saveDirectory.isAbsolute || !saveDirectory.isDirectory)
            return LocalAddResult.Refused("Choose an existing folder for this download")
        val info = try { TorrentInfo.bdecode(torrentBytes) } catch (_: Exception) { null }
        if (info?.isValid != true)
            return LocalAddResult.Refused("Torrent metadata is invalid")
        val paths = try {
            val files = info.files()
            (0 until files.numFiles()).asSequence()
                .filterNot { files.padFileAt(it) }
                .map { files.filePath(it) }.toList()
        } catch (_: Exception) {
            return LocalAddResult.Refused("Torrent file paths could not be inspected. Nothing added.")
        }
        if (paths.isEmpty()) return LocalAddResult.Refused("Torrent has no inspectable payload")
        when (val survey = LocalPayloadCollisionSurvey.inspect(saveDirectory, paths)) {
            is LocalPayloadCollisionSurvey.Result.ClearAtObservation -> Unit
            is LocalPayloadCollisionSurvey.Result.ExistingPaths ->
                return LocalAddResult.Refused("Existing files conflict with this torrent (" +
                    survey.total + " path(s)). Nothing added. Choose a new folder or use " +
                    "an explicitly reviewed existing-files recovery workflow.")
            is LocalPayloadCollisionSurvey.Result.Unknown ->
                return LocalAddResult.Refused("Cannot verify download file paths: " +
                    survey.problem + ". Nothing added.")
        }
        return addStopped(torrentBytes, saveDirectory, peers)
    }

    /**
     * Add a v1/v2 `.torrent` stopped. The save directory must be an existing,
     * explicitly chosen directory. A duplicate identity is refused, never merged.
     */
    @Synchronized
    fun addStopped(torrentBytes: ByteArray, saveDirectory: File, peers: List<InetSocketAddress> = emptyList()): LocalAddResult {
        check(started) { "Engine not started" }
        if (!saveDirectory.isAbsolute || !saveDirectory.isDirectory)
            return LocalAddResult.Refused("Choose an existing folder for this download")
        val info = try { TorrentInfo.bdecode(torrentBytes) } catch (_: IllegalArgumentException) {
            return LocalAddResult.Refused("Torrent metadata is invalid")
        }
        if (!info.isValid) return LocalAddResult.Refused("Torrent metadata is invalid")
        val hash = identity(info)
        if (handles.containsKey(hash) || session.find(info.infoHash())?.isValid == true)
            return LocalAddResult.Refused("This torrent is already in the local engine")

        val params = AddTorrentParams()
        params.setTorrentInfo(info)
        params.setSavePath(saveDirectory.absolutePath)
        // Stopped and unmanaged: the auto-manager must not start it behind the user.
        params.setFlags(torrent_flags_t().or_(org.libtorrent4j.TorrentFlags.PAUSED)
            .or_(org.libtorrent4j.TorrentFlags.DUPLICATE_IS_ERROR))
        // libtorrent does not adopt a bdecoded torrent's BEP 19 url-list on its own;
        // Internet Archive torrents rely on these HTTP(S) sources.
        val seeds = webSeeds(torrentBytes)
        if (seeds.isNotEmpty()) params.setUrlSeeds(seeds)
        // Likewise libtorrent 2.1's torrent_info no longer carries announce /
        // announce-list: without this the torrent never contacts its trackers
        // and can only reach peers found by DHT or injected directly.
        val (trackers, tiers) = trackerTiers(torrentBytes)
        if (trackers.isNotEmpty()) {
            params.setTrackers(trackers)
            params.setTrackerTiers(tiers)
        }
        if (peers.isNotEmpty())
            params.peers(peers.map { TcpEndpoint(requireNotNull(it.address?.hostAddress), it.port) })
        val error = error_code()
        val raw = session.swig().add_torrent(params.swig(), error)
        if (error.value() != 0 || !raw.is_valid)
            return LocalAddResult.Refused("The local engine refused this torrent: " + error.message())
        val handle = TorrentHandle(raw)
        handles[hash] = handle
        // Persist the explicit Add immediately; later saves refresh piece state.
        if (config.stateDirectory != null) writeResume(hash, AddTorrentParams.writeResumeDataBuf(params))
        val status = snapshot(hash, handle)
            ?: return LocalAddResult.Refused("Added torrent could not be read back")
        if (!status.stopped) {
            handle.pause()
            return LocalAddResult.Refused("Engine did not keep the new torrent stopped; it was paused")
        }
        return LocalAddResult.AddedStopped(status)
    }

    /**
     * Add a v1 magnet link. libtorrent cannot fetch metadata while paused (and
     * stop_when_ready pauses a magnet at once), so the transfer runs in upload
     * mode, which requests no payload pieces, until metadata arrives; it is then
     * paused and waits for Start.
     * DHT is used only if the engine was configured with it.
     */
    @Synchronized
    fun addMagnet(
        magnet: String, saveDirectory: File,
        peers: List<InetSocketAddress> = emptyList(),
        autoStartAfterMetadata: Boolean = false
    ): LocalAddResult {
        check(started) { "Engine not started" }
        if (!saveDirectory.isAbsolute || !saveDirectory.isDirectory)
            return LocalAddResult.Refused("Choose an existing folder for this download")
        val hash = magnetIdentity(magnet) ?: return LocalAddResult.Refused("Use a magnet link with exactly one v1 info hash")
        if (handles.containsKey(hash)) return LocalAddResult.Refused("This torrent is already in the local engine")
        val params = try { AddTorrentParams.parseMagnetUri(magnet) } catch (_: RuntimeException) {
            return LocalAddResult.Refused("This magnet link could not be read")
        }
        params.setSavePath(saveDirectory.absolutePath)
        params.setFlags(torrent_flags_t().or_(org.libtorrent4j.TorrentFlags.UPLOAD_MODE)
            .or_(org.libtorrent4j.TorrentFlags.DUPLICATE_IS_ERROR))
        if (peers.isNotEmpty())
            params.peers(peers.map { TcpEndpoint(requireNotNull(it.address?.hostAddress), it.port) })
        val error = error_code()
        val raw = session.swig().add_torrent(params.swig(), error)
        if (error.value() != 0 || !raw.is_valid)
            return LocalAddResult.Refused("The local engine refused this magnet: " + error.message())
        if (autoStartAfterMetadata) startWhenMetadataArrives += hash
        awaitingMetadata += hash
        val handle = TorrentHandle(raw)
        handles[hash] = handle
        // Metadata may already have arrived before we registered interest.
        // In that case an early callback may not have found the registered
        // handle, so persist through the same native coalesced request path.
        if (handle.status().hasMetadata()) transitionMagnetMetadata(hash, handle)
        val status = snapshot(hash, handle) ?: return LocalAddResult.Refused("Added magnet could not be read back")
        return LocalAddResult.FetchingMetadata(status)
    }

    /** Exactly one `xt=urn:btih:` with a 40-hex v1 identity; bounded and control-free. */
    private fun magnetIdentity(magnet: String): String? {
        if (!magnet.startsWith("magnet:?", ignoreCase = true) || magnet.length > 8192 ||
            magnet.any { it.isISOControl() }) return null
        val identities = magnet.substringAfter('?').split('&').mapNotNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 2 && pair[0].equals("xt", ignoreCase = true)) pair[1] else null
        }
        val btih = identities.filter { it.startsWith("urn:btih:", ignoreCase = true) }
        if (btih.size != 1) return null
        val hash = btih.single().substring(9)
        return if (hash.matches(Regex("^[0-9a-fA-F]{40}$"))) hash.lowercase(Locale.ROOT) else null
    }

    fun resume(infoHash: String, readbackTimeoutMs: Long = 5_000): LocalCommandResult {
        val key = normal(infoHash)
        synchronized(this) {
            val handle = handles[key]
                ?: return LocalCommandResult(LocalCommandOutcome.NOT_FOUND, null)
            // Upload-only is also preserved in the native resume file. That
            // makes the collision gate survive process death without relying
            // on this object's in-memory collision map.
            val flags = handle.getFlags()
            // The no-argument status() caches metadata for 500 ms. Use the
            // uncached overload, as snapshot() does, so a just-resolved magnet
            // cannot bypass this gate through an old metadata=false result.
            if (handle.status(TorrentHandle.QUERY_NAME).hasMetadata() &&
                flags.and_(org.libtorrent4j.TorrentFlags.UPLOAD_MODE).non_zero()) {
                val problem = inspectMagnetCollision(handle)
                if (problem != null) {
                    magnetCollisionProblems[key] = problem
                    return LocalCommandResult(LocalCommandOutcome.UNCERTAIN, snapshot(key, handle))
                }
                magnetCollisionProblems.remove(key)
                handle.unsetFlags(org.libtorrent4j.TorrentFlags.UPLOAD_MODE)
                requestMetadataResumeSave(key, handle)
            }
        }
        return command(infoHash, wantStopped = false, readbackTimeoutMs) {
            // A magnet initially runs in upload-only mode so it can fetch
            // metadata without downloading payload. If the user manually
            // presses Start while that metadata is pending, preserve that
            // explicit intent across the METADATA_RECEIVED callback. Otherwise
            // the callback would pause the torrent even though Start was
            // acknowledged earlier. A later explicit Stop clears this intent.
            val key = normal(infoHash)
            if (awaitingMetadata.contains(key)) startWhenMetadataArrives.add(key)
            it.resume()
        }
    }

    fun pause(infoHash: String, readbackTimeoutMs: Long = 5_000): LocalCommandResult =
        command(infoHash, wantStopped = true, readbackTimeoutMs) {
            // User Stop overrides any earlier Add-and-start consent while
            // magnet metadata is in flight.
            startWhenMetadataArrives.remove(normal(infoHash))
            it.pause()
        }

    private fun command(
        infoHash: String,
        wantStopped: Boolean,
        readbackTimeoutMs: Long,
        action: (TorrentHandle) -> Unit
    ): LocalCommandResult {
        require(readbackTimeoutMs in 0..60_000)
        val key = normal(infoHash)
        synchronized(this) {
            val handle = handles[key] ?: return LocalCommandResult(LocalCommandOutcome.NOT_FOUND, null)
            action(handle)
        }
        val deadline = System.nanoTime() + readbackTimeoutMs * 1_000_000
        var latest: LocalTransferStatus? = null
        while (true) {
            latest = status(key) ?: return LocalCommandResult(LocalCommandOutcome.NOT_FOUND, null)
            if (latest.stopped == wantStopped) return LocalCommandResult(LocalCommandOutcome.VERIFIED, latest)
            if (System.nanoTime() >= deadline) return LocalCommandResult(LocalCommandOutcome.UNCERTAIN, latest)
            Thread.sleep(25)
        }
    }

    /**
     * A complete .torrent for this transfer: its exact info dictionary (so the
     * infohash is unchanged) plus the trackers and web seeds the session holds
     * now. Null while magnet metadata is unknown. Contains tracker URLs, which
     * for private torrents may embed a passkey: callers must disclose that
     * before sending it anywhere. Read-only; never changes the transfer.
     */
    @Synchronized
    fun exportTorrentFile(infoHash: String): ByteArray? {
        val handle = handles[normal(infoHash)]?.takeIf { it.isValid } ?: return null
        val info = handle.torrentFile()?.takeIf { it.isValid } ?: return null
        val params = AddTorrentParams()
        params.setTorrentInfo(info)
        val trackers = handle.trackers()
        params.setTrackers(trackers.map { it.url() })
        params.setTrackerTiers(trackers.map { it.tier() })
        val seeds = handle.urlSeeds()
        if (seeds.isNotEmpty()) params.setUrlSeeds(seeds)
        val bytes = Vectors.byte_vector2bytes(libtorrent.write_torrent_file_buf_ex(params.swig()))
        // Never hand out a document whose identity drifted from this transfer.
        val check = TorrentInfo.bdecode(bytes)
        return bytes.takeIf { check.isValid && identity(check) == normal(infoHash) }
    }

    /**
     * Repair the missing announce list in a PRE-#392 local .torrent already in
     * this engine, without removing, resuming, rechecking, moving, or replacing
     * any payload. The caller must separately review a user-chosen, strictly
     * validated original .torrent; this method independently verifies its v1
     * identity and refuses any transfer that is running or already has trackers.
     *
     * An uncertain native write or resume-data flush MUST NOT be retried
     * automatically. Subsequent list/status will reveal any applied trackers.
     * Neither URLs nor native exception messages are ever returned.
     */
    @Synchronized
    fun repairMissingTrackers(
        infoHash: String,
        originalTorrent: ByteArray
    ): LocalTrackerRepairOutcome {
        check(started) { "Engine not started" }
        val key = normal(infoHash)
        val handle = handles[key]?.takeIf { it.isValid }
            ?: return LocalTrackerRepairOutcome.NOT_FOUND
        if (!originalTorrent.size.let { it in 1..TorrentMetainfoReader.MAX_BYTES })
            return LocalTrackerRepairOutcome.REFUSED
        val verified = try { TorrentMetainfoReader.preview(originalTorrent) }
            catch (_: Exception) { return LocalTrackerRepairOutcome.REFUSED }
        if (verified.infoHashV1 != key || verified.trackerCount == 0)
            return LocalTrackerRepairOutcome.REFUSED
        val before = try { snapshot(key, handle) }
            catch (_: Exception) { null }
            ?: return LocalTrackerRepairOutcome.UNCERTAIN
        if (!before.stopped || !before.metadataKnown)
            return LocalTrackerRepairOutcome.REFUSED
        val current = try { handle.trackers() } catch (_: Exception) {
            return LocalTrackerRepairOutcome.UNCERTAIN
        }
        if (current.isNotEmpty()) return LocalTrackerRepairOutcome.REFUSED

        // Use the same bounded, tier-preserving extraction as a fresh Add.
        val (urls, tiers) = trackerTiers(originalTorrent)
        if (urls.isEmpty() || urls.size != tiers.size)
            return LocalTrackerRepairOutcome.REFUSED
        val proposed = urls.mapIndexed { index, url ->
            AnnounceEntry(url).also { it.tier(tiers[index].toShort()) }
        }
        try { handle.replaceTrackers(proposed) } catch (_: Exception) {
            return LocalTrackerRepairOutcome.UNCERTAIN
        }
        val readback = try { handle.trackers() } catch (_: Exception) {
            return LocalTrackerRepairOutcome.UNCERTAIN
        }
        val expected = proposed.map { it.url() to it.tier() }.toSet()
        if (readback.size != proposed.size ||
            readback.map { it.url() to it.tier() }.toSet() != expected)
            return LocalTrackerRepairOutcome.UNCERTAIN
        val after = try { snapshot(key, handle) } catch (_: Exception) { null }
        if (after?.stopped != true || after.savePath != before.savePath)
            return LocalTrackerRepairOutcome.UNCERTAIN

        // A fresh in-memory tracker list does not establish crash-safe repair.
        // Require the exact stopped torrent's persisted resume data to contain
        // the same tracker list before declaring completion.
        val state = config.stateDirectory ?: return LocalTrackerRepairOutcome.UNCERTAIN
        val saved = try { saveResumeData() } catch (_: Exception) {
            return LocalTrackerRepairOutcome.UNCERTAIN
        }
        if (saved != handles.size) return LocalTrackerRepairOutcome.UNCERTAIN
        val onDisk = try {
            val bytes = LocalResumeFileReader.read(File(state, "$key.resume"), MAX_RESUME_BYTES)
            bytes?.let {
                val error = error_code()
                val params = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(it), error)
                if (error.value() != 0 || params == null) null
                else AddTorrentParams(params)
            }
        } catch (_: Exception) { null }
        val persisted = onDisk?.getTrackers()?.toSet()
        return if (persisted == urls.toSet())
            LocalTrackerRepairOutcome.VERIFIED
        else LocalTrackerRepairOutcome.UNCERTAIN
    }

    /** Files with progress and selection, or null if unknown or metadata is not yet known. */
    @Synchronized
    fun files(infoHash: String, limit: Int = 10_000): List<LocalTransferFile>? {
        val handle = handles[normal(infoHash)] ?: return null
        if (!handle.isValid) return null
        val info = handle.torrentFile() ?: return null
        val storage = info.files()
        val progress = handle.fileProgress()
        val priorities = handle.filePriorities()
        // Pad files are internal alignment padding (BEP 47), never user content.
        return (0 until storage.numFiles()).asSequence().filter { !storage.padFileAt(it) }.take(limit).map { index ->
            LocalTransferFile(index, storage.filePath(index), storage.fileSize(index),
                progress.getOrElse(index) { 0L },
                priorities.getOrNull(index)?.let { it != org.libtorrent4j.Priority.IGNORE } ?: true,
                priorities.getOrNull(index)?.swig()?.toInt() ?: 4)
        }.toList()
    }

    /**
     * Select or skip one file. libtorrent applies priorities asynchronously, so
     * the result is VERIFIED only once readback shows the requested selection.
     * Skipping never deletes bytes already written.
     */
    fun setFileWanted(infoHash: String, index: Int, wanted: Boolean, readbackTimeoutMs: Long = 5_000): LocalCommandOutcome {
        require(readbackTimeoutMs in 0..60_000)
        val key = normal(infoHash)
        synchronized(this) {
            val handle = handles[key] ?: return LocalCommandOutcome.NOT_FOUND
            val files = handle.torrentFile()?.files() ?: return LocalCommandOutcome.NOT_FOUND
            require(index in 0 until files.numFiles() && !files.padFileAt(index)) { "No such file" }
            handle.filePriority(index, if (wanted) org.libtorrent4j.Priority.DEFAULT else org.libtorrent4j.Priority.IGNORE)
        }
        val deadline = System.nanoTime() + readbackTimeoutMs * 1_000_000
        while (true) {
            val observed = synchronized(this) {
                handles[key]?.filePriorities()?.getOrNull(index)
            } ?: return LocalCommandOutcome.NOT_FOUND
            if ((observed != org.libtorrent4j.Priority.IGNORE) == wanted) return LocalCommandOutcome.VERIFIED
            if (System.nanoTime() >= deadline) return LocalCommandOutcome.UNCERTAIN
            Thread.sleep(25)
        }
    }

    /**
     * Change one already-selected file's native piece scheduling priority.
     * The full reviewed file identity, existing priority and enabled selection
     * must still match before ANY write. No file is implicitly unskipped.
     * Readback uncertainty is surfaced, never blindly retried or rolled back.
     */
    internal fun setFilePriority(
        infoHash: String,
        reviewed: LocalTransferFile,
        requestedPriority: Int,
        readbackTimeoutMs: Long = 5_000L
    ): LocalFileBatchOutcome {
        require(requestedPriority in setOf(1, 4, 7)) {
            "Only explicit Low, Normal and High native priorities are supported"
        }
        require(readbackTimeoutMs in 0L..60_000L)
        val key = normal(infoHash)
        synchronized(this) {
            val handle = handles[key]?.takeIf { it.isValid }
                ?: return LocalFileBatchOutcome.NOT_FOUND
            val actual = files(key)?.singleOrNull { it.index == reviewed.index }
                ?: return LocalFileBatchOutcome.STALE_REVIEW
            if (!reviewed.wanted || !actual.wanted ||
                actual.path != reviewed.path ||
                actual.sizeBytes != reviewed.sizeBytes ||
                actual.priority != reviewed.priority)
                return LocalFileBatchOutcome.STALE_REVIEW
            if (actual.priority == requestedPriority)
                return LocalFileBatchOutcome.UNCHANGED
            val priority = when (requestedPriority) {
                1 -> org.libtorrent4j.Priority.LOW
                4 -> org.libtorrent4j.Priority.DEFAULT
                else -> org.libtorrent4j.Priority.TOP_PRIORITY
            }
            try { handle.filePriority(reviewed.index, priority) }
            catch (_: Exception) { return LocalFileBatchOutcome.UNCERTAIN }
        }
        val deadline = System.nanoTime() + readbackTimeoutMs * 1_000_000L
        while (true) {
            val observed = synchronized(this) {
                try {
                    handles[key]?.takeIf { it.isValid }?.filePriorities()
                        ?.getOrNull(reviewed.index)?.swig()?.toInt()
                } catch (_: Exception) { null }
            }
            if (observed == requestedPriority) return LocalFileBatchOutcome.VERIFIED
            if (System.nanoTime() >= deadline) return LocalFileBatchOutcome.UNCERTAIN
            Thread.sleep(25)
        }
    }

    /**
     * One Finish in Order observation. Completion counts only pieces that
     * passed their hash check (piece granularity), never written-but-unverified
     * blocks. A file is [FinishInOrderPlanner.Observed.blocked] only on positive
     * evidence: the torrent is running with a non-empty peer availability map
     * and some still-missing piece of that file is held by no connected peer.
     */
    internal fun observeFinishInOrder(infoHash: String): List<FinishInOrderPlanner.Observed>? {
        val key = normal(infoHash)
        synchronized(this) {
            val handle = handles[key]?.takeIf { it.isValid } ?: return null
            val current = files(key) ?: return null
            val info = handle.torrentFile() ?: return null
            val storage = info.files()
            val verified = handle.fileProgress(TorrentHandle.PIECE_GRANULARITY)
            val status = handle.status()
            // With no connected peer every missing piece reads as zero: that is
            // "no peers", not evidence that one particular file is starved.
            val evidence = !status.flags().and_(org.libtorrent4j.TorrentFlags.PAUSED).non_zero() &&
                status.numPeers() > 0
            val availability = if (evidence) handle.pieceAvailability() else IntArray(0)
            val pieceLength = info.pieceLength().toLong()
            return current.map { file ->
                val done = verified.getOrElse(file.index) { 0L }
                val blocked = availability.isNotEmpty() && pieceLength > 0 &&
                    file.sizeBytes > 0 && done < file.sizeBytes && run {
                        val offset = storage.fileOffset(file.index)
                        val first = (offset / pieceLength).toInt()
                        val last = ((offset + file.sizeBytes - 1) / pieceLength).toInt()
                        (first..last).any { piece ->
                            piece < availability.size && availability[piece] == 0 &&
                                !handle.havePiece(piece)
                        }
                    }
                FinishInOrderPlanner.Observed(file, done, blocked)
            }
        }
    }

    /**
     * Apply one rolling Finish in Order batch. Every change's file identity
     * (as reviewed in [record]), selection and *expected* current priority
     * must match a fresh observation before the first JNI write. Skipped files
     * are never targeted. Partial readback is UNCERTAIN and never retried.
     */
    internal fun applyFinishInOrder(
        infoHash: String,
        record: FinishInOrderRecord,
        changes: List<FinishInOrderPlanner.Change>,
        readbackTimeoutMs: Long = 5_000L
    ): LocalFileBatchOutcome {
        require(readbackTimeoutMs in 0L..60_000L)
        if (changes.isEmpty()) return LocalFileBatchOutcome.UNCHANGED
        val key = normal(infoHash)
        val reviewed = record.policy.order.associateBy { it.index }
        synchronized(this) {
            val handle = handles[key]?.takeIf { it.isValid }
                ?: return LocalFileBatchOutcome.NOT_FOUND
            val byIndex = (files(key) ?: return LocalFileBatchOutcome.STALE_REVIEW)
                .associateBy { it.index }
            if (changes.size > FinishInOrderPlanner.MAX_FILES ||
                changes.map { it.index }.toSet().size != changes.size ||
                changes.any { change ->
                    val fresh = byIndex[change.index]
                    val identity = reviewed[change.index]
                    fresh == null || identity == null || !fresh.wanted ||
                        fresh.path != identity.path || fresh.sizeBytes != identity.sizeBytes ||
                        fresh.priority != change.expectedPriority ||
                        change.requestedPriority !in setOf(FinishInOrderPlanner.LOW,
                            FinishInOrderPlanner.NORMAL, FinishInOrderPlanner.HIGH)
                }) return LocalFileBatchOutcome.STALE_REVIEW
            try {
                changes.forEach { change ->
                    handle.filePriority(change.index, when (change.requestedPriority) {
                        FinishInOrderPlanner.LOW -> org.libtorrent4j.Priority.LOW
                        FinishInOrderPlanner.NORMAL -> org.libtorrent4j.Priority.DEFAULT
                        else -> org.libtorrent4j.Priority.TOP_PRIORITY
                    })
                }
            } catch (_: Exception) { return LocalFileBatchOutcome.UNCERTAIN }
        }
        val deadline = System.nanoTime() + readbackTimeoutMs * 1_000_000L
        while (true) {
            val actual = try {
                synchronized(this) { handles[key]?.takeIf { it.isValid }?.filePriorities() }
            } catch (_: Exception) { null }
            if (actual != null && changes.all { change ->
                    actual.getOrNull(change.index)?.swig()?.toInt() == change.requestedPriority
                }) return LocalFileBatchOutcome.VERIFIED
            if (System.nanoTime() >= deadline) return LocalFileBatchOutcome.UNCERTAIN
            Thread.sleep(25)
        }
    }

    /** Fresh read of the transfer's in-order piece picking flag, or null. */
    /** This transfer's own upload cap in bytes per second; 0 = no per-transfer cap. Null when unknown. */
    internal fun uploadLimit(infoHash: String): Int? = try {
        synchronized(this) { handles[normal(infoHash)]?.takeIf { it.isValid }?.uploadLimit?.coerceAtLeast(0) }
    } catch (_: Exception) { null }

    /**
     * Smart Seeding per-transfer upload cap: null or 0 removes it. True only
     * after a readback shows exactly the requested value. Never pauses, removes
     * or reprioritises anything.
     */
    internal fun setUploadLimit(infoHash: String, bytesPerSecond: Long?): Boolean {
        val wanted = (bytesPerSecond ?: 0L).also { require(it in 0..Int.MAX_VALUE) }.toInt()
        try {
            synchronized(this) {
                val handle = handles[normal(infoHash)]?.takeIf { it.isValid } ?: return false
                handle.uploadLimit = wanted
            }
        } catch (_: Exception) { return false }
        return uploadLimit(infoHash) == wanted
    }

    /**
     * Smart Seeding facts for every transfer: when it was added, its tracker URLs
     * (null until metadata is known) and the private flag. Tracker URLs stay in
     * memory for matching only; callers must never store or log them.
     */
    internal fun seedingFacts(): List<io.github.gamesbyian.chunks.seeding.SeedingController.Facts> = try {
        synchronized(this) {
            handles.entries.toList().mapNotNull { (key, handle) ->
                if (!handle.isValid) return@mapNotNull null
                val status = handle.status()
                val info = handle.torrentFile()?.takeIf { it.isValid && status.hasMetadata() }
                io.github.gamesbyian.chunks.seeding.SeedingController.Facts(key, status.addedTime() * 1000L,
                    info?.let { handle.trackers().map { t -> t.url() } }, info?.isPrivate)
            }
        }
    } catch (_: Exception) { emptyList() }

    /** Counters for Smart Seeding decisions, read from one status snapshot. */
    internal fun seedingObservation(infoHash: String): io.github.gamesbyian.chunks.seeding.SeedingObservation? = try {
        synchronized(this) {
            val status = handles[normal(infoHash)]?.takeIf { it.isValid }?.status() ?: return null
            io.github.gamesbyian.chunks.seeding.SeedingObservation(
                uploadedBytes = status.allTimeUpload(), downloadedBytes = status.allTimeDownload(),
                seedingSeconds = status.seedingDuration(), seeding = status.isSeeding)
        }
    } catch (_: Exception) { null }

    internal fun sequentialDownload(infoHash: String): Boolean? = try {
        synchronized(this) {
            handles[normal(infoHash)]?.takeIf { it.isValid }?.status()?.flags()
                ?.and_(org.libtorrent4j.TorrentFlags.SEQUENTIAL_DOWNLOAD)?.non_zero()
        }
    } catch (_: Exception) { null }

    /**
     * Turn in-order piece picking on or off for one transfer (Finish in Order
     * Sequential Playback). True only once a fresh status readback shows the
     * requested state; touches no file priority, selection or payload.
     */
    internal fun setSequentialDownload(infoHash: String, on: Boolean, readbackTimeoutMs: Long = 5_000L): Boolean {
        require(readbackTimeoutMs in 0L..60_000L)
        val key = normal(infoHash)
        try {
            synchronized(this) {
                val handle = handles[key]?.takeIf { it.isValid } ?: return false
                if (on) handle.setFlags(org.libtorrent4j.TorrentFlags.SEQUENTIAL_DOWNLOAD)
                else handle.unsetFlags(org.libtorrent4j.TorrentFlags.SEQUENTIAL_DOWNLOAD)
            }
        } catch (_: Exception) { return false }
        val deadline = System.nanoTime() + readbackTimeoutMs * 1_000_000L
        while (true) {
            if (sequentialDownload(infoHash) == on) return true
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(25)
        }
    }

    /**
     * Apply a single explicitly confirmed episode-first rolling-window plan.
     * Validate ALL original file identities and priorities before the first
     * JNI write, then confirm complete readback. A partially applied batch is
     * UNCERTAIN, never automatically rerun, since a prefix may have landed.
     * Existing skipped files and downloaded payload are never touched.
     */
    internal fun focusEpisodesOnce(
        infoHash: String,
        reviewedPlan: LocalEpisodeFocusPlanner.Plan,
        readbackTimeoutMs: Long = 5_000L
    ): LocalFileBatchOutcome {
        require(readbackTimeoutMs in 0L..60_000L)
        val key = normal(infoHash)
        val changes = reviewedPlan.changes
        if (changes.isEmpty()) return LocalFileBatchOutcome.UNCHANGED
        val handle: TorrentHandle
        synchronized(this) {
            handle = handles[key]?.takeIf { it.isValid }
                ?: return LocalFileBatchOutcome.NOT_FOUND
            val current = files(key) ?: return LocalFileBatchOutcome.STALE_REVIEW
            val planned = LocalEpisodeFocusPlanner.plan(current)
                ?: return LocalFileBatchOutcome.STALE_REVIEW
            if (planned.firstEpisode.index != reviewedPlan.firstEpisode.index ||
                planned.orderedEpisodes.map { it.index } !=
                    reviewedPlan.orderedEpisodes.map { it.index } ||
                planned.changes.map { it.reviewed.index to it.requestedPriority } !=
                    changes.map { it.reviewed.index to it.requestedPriority })
                return LocalFileBatchOutcome.STALE_REVIEW
            // One full immutable snapshot of every proposed native mutation
            // closes the stale-review boundary before any single change.
            val byIndex = current.associateBy { it.index }
            if (changes.size > 256 || changes.map { it.reviewed.index }.toSet().size != changes.size ||
                changes.any { change ->
                    val fresh = byIndex[change.reviewed.index]
                    fresh == null || !fresh.wanted ||
                        fresh.path != change.reviewed.path ||
                        fresh.sizeBytes != change.reviewed.sizeBytes ||
                        fresh.priority != change.reviewed.priority
                }) return LocalFileBatchOutcome.STALE_REVIEW
            try {
                changes.forEach { change ->
                    val priority = when (change.requestedPriority) {
                        1 -> org.libtorrent4j.Priority.LOW
                        4 -> org.libtorrent4j.Priority.DEFAULT
                        7 -> org.libtorrent4j.Priority.TOP_PRIORITY
                        else -> return LocalFileBatchOutcome.STALE_REVIEW
                    }
                    handle.filePriority(change.reviewed.index, priority)
                }
            } catch (_: Exception) { return LocalFileBatchOutcome.UNCERTAIN }
        }
        val deadline = System.nanoTime() + readbackTimeoutMs * 1_000_000L
        while (true) {
            val actual = try {
                synchronized(this) {
                    handles[key]?.takeIf { it.isValid }?.filePriorities()
                }
            } catch (_: Exception) { null }
            if (actual != null && changes.all { change ->
                    actual.getOrNull(change.reviewed.index)?.swig()?.toInt() ==
                        change.requestedPriority
                }) return LocalFileBatchOutcome.VERIFIED
            if (System.nanoTime() >= deadline) return LocalFileBatchOutcome.UNCERTAIN
            Thread.sleep(25)
        }
    }

    /**
     * Apply a reviewed page-level file selection without deleting any bytes.
     * The entire preflight is validated against a fresh JNI file observation
     * before the first priority change. If any command/readback later fails,
     * report UNCERTAIN: never retry the batch automatically, because a prefix
     * of native writes could already have landed.
     */
    internal fun setFilesWantedBatch(
        infoHash: String,
        reviewed: List<LocalTransferFile>,
        wanted: Boolean,
        readbackTimeoutMs: Long = 5_000L
    ): LocalFileBatchOutcome {
        require(readbackTimeoutMs in 0L..60_000L)
        val key = normal(infoHash)
        val selected: List<Int>
        synchronized(this) {
            val handle = handles[key] ?: return LocalFileBatchOutcome.NOT_FOUND
            val current = files(key) ?: return LocalFileBatchOutcome.NOT_FOUND
            val plan = LocalFileBatchPlanner.prepare(current, reviewed, wanted)
                ?: return LocalFileBatchOutcome.STALE_REVIEW
            selected = plan.indicesToChange
            if (selected.isEmpty()) return LocalFileBatchOutcome.UNCHANGED
            val priority = if (wanted) org.libtorrent4j.Priority.DEFAULT
                else org.libtorrent4j.Priority.IGNORE
            try {
                for (index in selected) handle.filePriority(index, priority)
            } catch (_: Exception) {
                // A partial prefix may have applied. Do not roll back blindly.
                return LocalFileBatchOutcome.UNCERTAIN
            }
        }
        val deadline = System.nanoTime() + readbackTimeoutMs * 1_000_000L
        while (true) {
            val matched = synchronized(this) {
                val handle = handles[key] ?: return LocalFileBatchOutcome.UNCERTAIN
                try {
                    val priority = handle.filePriorities()
                    selected.all { index ->
                        priority.getOrNull(index)?.let { (it !=
                            org.libtorrent4j.Priority.IGNORE) == wanted } == true
                    }
                } catch (_: Exception) { false }
            }
            if (matched) return LocalFileBatchOutcome.VERIFIED
            if (System.nanoTime() >= deadline) return LocalFileBatchOutcome.UNCERTAIN
            Thread.sleep(25)
        }
    }

    /** Ask libtorrent to connect to one explicit peer (tests and user-supplied peers). */
    @Synchronized
    internal fun connectPeer(infoHash: String, peer: InetSocketAddress): Boolean {
        val handle = handles[normal(infoHash)] ?: return false
        handle.swig().connect_peer(TcpEndpoint(requireNotNull(peer.address?.hostAddress), peer.port).swig())
        return true
    }

    /** Remove from the session only. Payload files are never deleted here. */
    @Synchronized
    fun removeKeepingData(infoHash: String): Boolean {
        val key = normal(infoHash)
        val handle = handles[key] ?: return false
        // Do not lose the only tracked handle before the native session accepts
        // the removal request. A thrown JNI call must leave it inspectable.
        session.remove(handle)
        // Must use the same monitor held across an asynchronous resume write.
        // Any writer already admitted finishes before we delete the file; any
        // late alert for this handle is refused by LocalResumeWriteFence.
        synchronized(handles) { handles.remove(key) }
        awaitingMetadata.remove(key)
        startWhenMetadataArrives.remove(key)
        magnetCollisionProblems.remove(key)
        pendingFlushes.cancel(key, handle)
        pendingSaves.cancel(key, handle)
        val saved = config.stateDirectory?.let { File(it, "$key.resume") } ?: return true
        // A removed session with an undeletable .resume file can reappear on
        // restart. Do not report that as a fully confirmed removal.
        return try { !saved.exists() || saved.delete() } catch (_: Exception) { false }
    }

    @Synchronized
    fun status(infoHash: String): LocalTransferStatus? {
        val key = normal(infoHash)
        return snapshot(key, handles[key] ?: return null)
    }

    @Synchronized
    fun list(): List<LocalTransferStatus> =
        synchronized(handles) { handles.toList() }.mapNotNull { (hash, handle) -> snapshot(hash, handle) }

    /**
     * Snapshot-restore *absence* gate. Unlike list(), a failed or unobservable
     * native handle cannot be silently dropped and mistaken for a missing
     * torrent. Null means this is NOT a complete proof of the phone inventory.
     */
    @Synchronized
    fun completeIdentityInventory(): List<String>? {
        if (!started) return null
        val current = synchronized(handles) { handles.toList() }
        val observed = ArrayList<String>(current.size)
        for ((key, handle) in current) {
            val status = try { snapshot(key, handle) } catch (_: Exception) { null }
                ?: return null
            if (status.infoHash != key) return null
            observed += key
        }
        return observed.takeIf { it.toSet().size == it.size }
    }

    /**
     * Completion status alone does not mean libtorrent's asynchronous disk
     * cache has been flushed. Before an independent SAF library publication,
     * wait for the native cache_flushed_alert for THIS exact infohash.
     *
     * A timeout, missing callback, incomplete transfer or any other error
     * blocks publication. The method never pauses/changes sharing or deletes
     * source bytes. It must be called off the Android UI thread.
     */
    fun flushCompletedForLibraryRead(infoHash: String, timeoutMs: Long = 20_000L): Boolean {
        val key = normal(infoHash)
        return pendingFlushes.flush(
            key, timeoutMs,
            currentHandle = { if (started) handles[key] else null },
            isComplete = { handle ->
                val status = try { snapshot(key, handle) } catch (_: Exception) { null }
                status?.metadataKnown == true && status.progressPpm == 1_000_000 &&
                    status.state in setOf(LocalTransferState.SEEDING, LocalTransferState.FINISHED)
            },
            invokeNative = { it.flushCache() }
        )
    }

    /**
     * Ask libtorrent for fresh resume data for every transfer and write each
     * atomically. Returns how many were confirmed before the timeout.
     */
    fun saveResumeData(timeoutMs: Long = 5_000): Int {
        // Register under the same engine monitor as Remove/Close so a save
        // cannot be issued on a handle already retired from the session.
        // Release the monitor before awaiting native alerts.
        val requests = synchronized(this) {
            if (config.stateDirectory == null || !started) return 0
            val targets = synchronized(handles) { handles.toList() }
            targets.map { (key, handle) ->
                pendingSaves.acquire(key, handle) {
                    handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
                }
            }
        }
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0L) * 1_000_000
        return requests.count {
            try {
                it.awaitConfirmed(maxOf(0, deadline - System.nanoTime()))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }
    }

    @Synchronized
    override fun close() {
        if (!started) return
        saveResumeData()
        pendingSaves.cancelAll()
        pendingFlushes.cancelAll()
        handles.clear()
        awaitingMetadata.clear()
        startWhenMetadataArrives.clear()
        magnetCollisionProblems.clear()
        session.stop()
        started = false
    }

    /** Restore every well-formed resume file, always stopped and unmanaged. */
    private fun restore(directory: File) {
        val problems = ArrayList<String>()
        for (file in directory.listFiles().orEmpty().sortedBy { it.name }) {
            val key = file.name.removeSuffix(".resume")
            if (!file.name.endsWith(".resume") || !identityPattern.matches(key)) continue
            val bytes = LocalResumeFileReader.read(file, MAX_RESUME_BYTES)
            val params = try {
                bytes?.let {
                    val error = error_code()
                    val raw = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(it), error)
                    if (raw == null || error.value() != 0) null else AddTorrentParams(raw)
                }
            } catch (_: Exception) { null }
            if (params == null) { problems += file.name; continue }
            // A single invalid native record must not abort restoration of
            // unrelated, valid transfers. Preserve every failed input on disk.
            val restored = try {
                val info = params.torrentInfo
                if (info == null || !info.isValid || identity(info) != key) null
                else {
                    params.setFlags(params.flags.or_(org.libtorrent4j.TorrentFlags.PAUSED)
                        .and_(org.libtorrent4j.TorrentFlags.AUTO_MANAGED.inv()))
                    val addError = error_code()
                    val handle = session.swig().add_torrent(params.swig(), addError)
                    if (addError.value() != 0 || !handle.is_valid) null
                    else TorrentHandle(handle)
                }
            } catch (_: Exception) { null }
            if (restored == null) { problems += file.name; continue }
            handles[key] = restored
        }
        restoreProblems = problems
    }

    /** Atomic replace of our own resume file: temp file, fsync, rename. */
    private fun writeResume(key: String, bytes: ByteArray) {
        val directory = config.stateDirectory ?: return
        val temporary = File(directory, "$key.resume.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        Files.move(temporary.toPath(), File(directory, "$key.resume").toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun snapshot(hash: String, handle: TorrentHandle): LocalTransferStatus? {
        if (!handle.isValid) return null
        val status = handle.status(TorrentHandle.QUERY_NAME)
        val paused = status.flags().and_(org.libtorrent4j.TorrentFlags.PAUSED).non_zero()
        val error = status.errorCode()?.takeIf { it.isError }?.message
        return LocalTransferStatus(
            infoHash = hash,
            name = status.name(),
            state = when (status.state()) {
                TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA -> LocalTransferState.CHECKING
                TorrentStatus.State.DOWNLOADING_METADATA -> LocalTransferState.DOWNLOADING_METADATA
                TorrentStatus.State.DOWNLOADING -> LocalTransferState.DOWNLOADING
                TorrentStatus.State.FINISHED -> LocalTransferState.FINISHED
                TorrentStatus.State.SEEDING -> LocalTransferState.SEEDING
                else -> LocalTransferState.UNKNOWN
            },
            stopped = paused,
            progressPpm = status.progressPpm(),
            wantedBytes = status.totalWanted(),
            doneBytes = status.totalWantedDone(),
            payloadDownloadedBytes = status.allTimeDownload(),
            payloadUploadedBytes = status.allTimeUpload(),
            peers = status.numPeers(),
            metadataKnown = status.hasMetadata(),
            uploadOnly = status.flags().and_(org.libtorrent4j.TorrentFlags.UPLOAD_MODE).non_zero(),
            savePath = handle.savePath(),
            error = magnetCollisionProblems[hash] ?: error,
            trackers = trackerSummary(handle),
            downloadPayloadRateBytesPerSecond = status.downloadPayloadRate().coerceAtLeast(0),
            uploadPayloadRateBytesPerSecond = status.uploadPayloadRate().coerceAtLeast(0),
            addedAtEpochMillis = status.addedTime().takeIf { it > 0 }
        )
    }

    private fun trackerSummary(handle: TorrentHandle): LocalTrackerSummary? = try {
        val entries = handle.trackers()
        var working = 0
        var updating = 0
        var failing = 0
        for (entry in entries) {
            val states = entry.endpoints().filter { it.enabled() }.map { it.infohashV1() }
            when {
                states.any { it.isWorking } -> working++
                states.any { it.updating() } -> updating++
                states.any { it.fails() > 0 } -> failing++
            }
        }
        LocalTrackerSummary(entries.size, working, updating, failing)
    } catch (_: RuntimeException) { null }

    /**
     * BEP 12 announce-list tiers (else BEP 3 announce) as parallel lists of
     * URL and tier index. Only http(s)/udp trackers, bounded and de-duplicated.
     */
    internal fun trackerTiers(torrentBytes: ByteArray): Pair<List<String>, List<Int>> = try {
        val root = Entry.bdecode(torrentBytes).dictionary()
        fun usable(url: String) = url.length <= 2048 && url.none { it.isISOControl() || it == ' ' } &&
            (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("udp://"))
        val tiers: List<List<String>> = root["announce-list"]
            ?.takeIf { it.swig().type() == entry.data_type.list_t }
            ?.list()?.mapNotNull { tier ->
                if (tier.swig().type() != entry.data_type.list_t) null
                else tier.list().filter { it.swig().type() == entry.data_type.string_t }.map { it.string() }
            }?.filter { it.isNotEmpty() }.orEmpty()
            .ifEmpty {
                root["announce"]?.takeIf { it.swig().type() == entry.data_type.string_t }
                    ?.let { listOf(listOf(it.string())) }.orEmpty()
            }
        val urls = ArrayList<String>()
        val indexes = ArrayList<Int>()
        tiers.take(16).forEachIndexed { tier, members ->
            members.filter(::usable).forEach { url ->
                if (url !in urls && urls.size < 64) { urls += url; indexes += tier }
            }
        }
        urls to indexes
    } catch (_: RuntimeException) { emptyList<String>() to emptyList() }

    /** Only plain http(s) web seeds, bounded in number and length. */
    private fun webSeeds(torrentBytes: ByteArray): List<String> = try {
        val value = Entry.bdecode(torrentBytes).dictionary()["url-list"]
        val urls = when {
            value == null -> emptyList()
            value.swig().type() == entry.data_type.list_t -> value.list().map { it.string() }
            value.swig().type() == entry.data_type.string_t -> listOf(value.string())
            else -> emptyList()
        }
        urls.filter { url ->
            url.length <= 2048 && url.none { it.isISOControl() || it == ' ' } &&
                (url.startsWith("https://") || url.startsWith("http://"))
        }.distinct().take(32)
    } catch (_: RuntimeException) { emptyList() }

    private fun identity(info: TorrentInfo): String {
        val hashes = info.infoHashes()
        return if (hashes.hasV1()) hashes.v1.toHex().lowercase(Locale.ROOT)
        else hashes.best.toHex().lowercase(Locale.ROOT)
    }

    private fun normal(infoHash: String): String {
        require(infoHash.matches(Regex("^[0-9a-fA-F]{40}([0-9a-fA-F]{24})?$"))) { "Invalid torrent identity" }
        return infoHash.lowercase(Locale.ROOT)
    }

    private companion object {
        val identityPattern = Regex("^[0-9a-f]{40}([0-9a-f]{24})?$")
        const val MAX_RESUME_BYTES = 16L * 1024 * 1024
    }
}
