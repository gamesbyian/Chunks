package io.github.gamesbyian.chunks.engine

import java.io.File
import java.net.InetSocketAddress
import java.util.Locale
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.error_code
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
    val allowMultipleConnectionsPerIp: Boolean = false
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
    val error: String?
)

/** libtorrent applies pause/resume asynchronously; only a matching readback is VERIFIED. */
enum class LocalCommandOutcome { VERIFIED, UNCERTAIN, NOT_FOUND }

data class LocalCommandResult(val outcome: LocalCommandOutcome, val status: LocalTransferStatus?)

sealed class LocalAddResult {
    data class AddedStopped(val status: LocalTransferStatus) : LocalAddResult()
    data class Refused(val reason: String) : LocalAddResult()
}

class LocalTorrentEngine(private val config: LocalEngineConfig = LocalEngineConfig()) : AutoCloseable {
    private val session = SessionManager(false)
    private val handles = LinkedHashMap<String, TorrentHandle>()
    @Volatile private var started = false

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
        session.start(SessionParams(pack))
        started = true
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
        if (peers.isNotEmpty())
            params.peers(peers.map { TcpEndpoint(requireNotNull(it.address?.hostAddress), it.port) })
        val error = error_code()
        val raw = session.swig().add_torrent(params.swig(), error)
        if (error.value() != 0 || !raw.is_valid)
            return LocalAddResult.Refused("The local engine refused this torrent: " + error.message())
        val handle = TorrentHandle(raw)
        handles[hash] = handle
        val status = snapshot(hash, handle)
            ?: return LocalAddResult.Refused("Added torrent could not be read back")
        if (!status.stopped) {
            handle.pause()
            return LocalAddResult.Refused("Engine did not keep the new torrent stopped; it was paused")
        }
        return LocalAddResult.AddedStopped(status)
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
        val handle = handles.remove(key) ?: return false
        session.remove(handle)
        return true
    }

    @Synchronized
    fun status(infoHash: String): LocalTransferStatus? {
        val key = normal(infoHash)
        return snapshot(key, handles[key] ?: return null)
    }

    @Synchronized
    fun list(): List<LocalTransferStatus> = handles.mapNotNull { (hash, handle) -> snapshot(hash, handle) }

    @Synchronized
    override fun close() {
        if (!started) return
        handles.clear()
        session.stop()
        started = false
    }

    private fun snapshot(hash: String, handle: TorrentHandle): LocalTransferStatus? {
        if (!handle.isValid) return null
        val status = handle.status()
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
            savePath = handle.savePath(),
            error = error
        )
    }

    private fun identity(info: TorrentInfo): String {
        val hashes = info.infoHashes()
        return if (hashes.hasV1()) hashes.v1.toHex().lowercase(Locale.ROOT)
        else hashes.best.toHex().lowercase(Locale.ROOT)
    }

    private fun normal(infoHash: String): String {
        require(infoHash.matches(Regex("^[0-9a-fA-F]{40}([0-9a-fA-F]{24})?$"))) { "Invalid torrent identity" }
        return infoHash.lowercase(Locale.ROOT)
    }
}
