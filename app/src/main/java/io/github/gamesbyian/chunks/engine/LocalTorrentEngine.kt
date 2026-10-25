package io.github.gamesbyian.chunks.engine

import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.libtorrent4j.AddTorrentParams
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
import org.libtorrent4j.swig.entry
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
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
    val stateDirectory: File? = null
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
    val metadataKnown: Boolean = true
)

/** libtorrent applies pause/resume asynchronously; only a matching readback is VERIFIED. */
enum class LocalCommandOutcome { VERIFIED, UNCERTAIN, NOT_FOUND }

data class LocalCommandResult(val outcome: LocalCommandOutcome, val status: LocalTransferStatus?)

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
    private val pendingSaves = ConcurrentHashMap<String, CountDownLatch>()
    @Volatile private var started = false

    /** Resume files that could not be restored on start; they are left untouched. */
    @Volatile var restoreProblems: List<String> = emptyList()
        private set

    /** Magnets still fetching metadata in upload mode (no payload requests). */
    private val awaitingMetadata: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val engineListener = object : AlertListener {
        override fun types() = intArrayOf(AlertType.SAVE_RESUME_DATA.swig(),
            AlertType.SAVE_RESUME_DATA_FAILED.swig(), AlertType.METADATA_RECEIVED.swig())
        override fun alert(alert: Alert<*>) {
            val handle = (alert as? TorrentAlert<*>)?.handle() ?: return
            val key = synchronized(handles) { handles.entries.firstOrNull { it.value == handle }?.key } ?: return
            if (alert.type() == AlertType.METADATA_RECEIVED) {
                // Metadata only: stop before any payload is requested, then wait for Start.
                if (awaitingMetadata.remove(key)) {
                    handle.pause()
                    handle.unsetFlags(org.libtorrent4j.TorrentFlags.UPLOAD_MODE)
                }
                return
            }
            try {
                // A magnet without metadata has nothing restorable yet; never persist it half-known.
                if (alert is SaveResumeDataAlert && alert.params().torrentInfo?.isValid == true)
                    writeResume(key, AddTorrentParams.writeResumeDataBuf(alert.params()))
            } finally {
                pendingSaves.remove(key)?.countDown()
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
        session.addListener(engineListener)
        session.start(SessionParams(pack))
        started = true
        config.stateDirectory?.let { directory ->
            require(directory.isDirectory || directory.mkdirs()) { "Engine state directory is unavailable" }
            restore(directory)
        }
    }

    /** The bound TCP listen port, or 0 if the session is not listening. */
    fun listenPort(): Int = session.swig()?.listen_port() ?: 0

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
    fun addMagnet(magnet: String, saveDirectory: File, peers: List<InetSocketAddress> = emptyList()): LocalAddResult {
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
        awaitingMetadata += hash
        val handle = TorrentHandle(raw)
        handles[hash] = handle
        // Metadata may already have arrived before we registered interest.
        if (handle.status().hasMetadata() && awaitingMetadata.remove(hash)) {
            handle.pause()
            handle.unsetFlags(org.libtorrent4j.TorrentFlags.UPLOAD_MODE)
        }
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

    fun resume(infoHash: String, readbackTimeoutMs: Long = 5_000): LocalCommandResult =
        command(infoHash, wantStopped = false, readbackTimeoutMs) { it.resume() }

    fun pause(infoHash: String, readbackTimeoutMs: Long = 5_000): LocalCommandResult =
        command(infoHash, wantStopped = true, readbackTimeoutMs) { it.pause() }

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

    /** Remove from the session only. Payload files are never deleted here. */
    @Synchronized
    fun removeKeepingData(infoHash: String): Boolean {
        val key = normal(infoHash)
        awaitingMetadata.remove(key)
        val handle = handles.remove(key) ?: return false
        session.remove(handle)
        config.stateDirectory?.let { File(it, "$key.resume").delete() }
        return true
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
     * Ask libtorrent for fresh resume data for every transfer and write each
     * atomically. Returns how many were confirmed before the timeout.
     */
    fun saveResumeData(timeoutMs: Long = 5_000): Int {
        if (config.stateDirectory == null || !started) return 0
        val targets = synchronized(handles) { handles.toList() }
        val latches = targets.map { (key, handle) ->
            CountDownLatch(1).also { pendingSaves[key] = it; handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT) }
        }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        return latches.count { it.await(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) }
    }

    @Synchronized
    override fun close() {
        if (!started) return
        saveResumeData()
        handles.clear()
        session.stop()
        started = false
    }

    /** Restore every well-formed resume file, always stopped and unmanaged. */
    private fun restore(directory: File) {
        val problems = ArrayList<String>()
        for (file in directory.listFiles().orEmpty().sortedBy { it.name }) {
            val key = file.name.removeSuffix(".resume")
            if (!file.name.endsWith(".resume") || !identityPattern.matches(key)) continue
            val bytes = if (file.isFile && file.length() in 1..MAX_RESUME_BYTES) file.readBytes() else null
            val error = error_code()
            val raw = bytes?.let { libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(it), error) }
            if (raw == null || error.value() != 0) { problems += file.name; continue }
            val params = AddTorrentParams(raw)
            val info = params.torrentInfo
            if (info == null || !info.isValid || identity(info) != key) { problems += file.name; continue }
            params.setFlags(params.flags.or_(org.libtorrent4j.TorrentFlags.PAUSED)
                .and_(org.libtorrent4j.TorrentFlags.AUTO_MANAGED.inv()))
            val addError = error_code()
            val handle = session.swig().add_torrent(params.swig(), addError)
            if (addError.value() != 0 || !handle.is_valid) { problems += file.name; continue }
            handles[key] = TorrentHandle(handle)
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
            savePath = handle.savePath(),
            error = error
        )
    }

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
