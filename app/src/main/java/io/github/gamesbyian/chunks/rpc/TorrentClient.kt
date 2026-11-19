package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.model.RemoteBackendKind
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale

/**
 * The ONE engine-neutral port for every BitTorrent client Chunks drives:
 * the device's own libtorrent engine ([io.github.gamesbyian.chunks.LocalTorrentClient])
 * and authenticated rTorrent ([RTorrentClientAdapter]), Transmission,
 * qBittorrent and Deluge. Adapters isolate wire, auth, native and version
 * dialects; capability differences are explicit [ClientCapability] sets.
 * The shared [ClientOperations] owns the
 * application workflow: exact precondition read, ONE write, exact-hash
 * readback and Verified / Pending / Uncertain / Refused classification.
 *
 * Every mutating method sends at most one logical write. A failure before
 * the write is sent throws [ClientRefusedException]; any failure after it
 * may have been delivered throws [ClientWriteUncertainException]. Callers
 * must reconcile read-only and never replay an uncertain write.
 */
internal interface TorrentClient {
    val kind: TorrentClientKind

    /** Authenticated version/default-folder/capability probe (read-only). */
    suspend fun session(): ClientSessionInfo

    /** Complete inventory or an exception; never a partial list. */
    suspend fun torrents(): List<ClientTorrent>

    /** Exact-hash read: null only when the client verifiably lacks it. */
    suspend fun torrent(hash: String): ClientTorrent?

    suspend fun files(hash: String): List<ClientFile>

    suspend fun add(request: ClientAddRequest)
    suspend fun setStarted(hash: String, started: Boolean)
    suspend fun remove(hash: String, deleteData: Boolean)
    suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>)
    suspend fun setLocation(hash: String, directory: String, moveData: Boolean)
    suspend fun recheck(hash: String)

    /**
     * A source that re-creates this exact job on another client: the job's
     * own metainfo where the API exports it, else a magnet with this job's
     * trackers. Read-only. null when neither can be obtained faithfully.
     * Contains tracker URLs (possibly passkeys): never log or persist it.
     */
    suspend fun transferSource(hash: String): ClientAddSource? = null

    /**
     * Read-only seeding counters and tracker facts for Smart Seeding, or null
     * when this client does not provide them. Tracker URLs of a private (or
     * not-provably-public) transfer are never read: they carry passkeys.
     */
    suspend fun seedingFacts(hash: String): ClientSeedingFacts? = null

    /**
     * Ask the client to contact this job's trackers now (the ordinary
     * "force reannounce"). Changes no job state; unsupported clients ignore it.
     */
    suspend fun reannounce(hash: String) {}

    /**
     * The smallest exactly storable step of this client's own per-transfer
     * upload cap, in bytes per second; null when the client has none (or it
     * cannot be determined). Read-only. Never a shared/global throttle.
     */
    suspend fun uploadCapStepBytes(): Long? = null

    /** This exact transfer's own upload cap in bytes/s: 0 = none; null = unreadable. Read-only. */
    suspend fun uploadCapBytesPerSecond(hash: String): Long? = null

    /**
     * Write ONLY this transfer's own upload cap ([bytesPerSecond] > 0, a
     * multiple of [uploadCapStepBytes]). One write; the caller verifies it by
     * an independent [uploadCapBytesPerSecond] read and never replays it.
     */
    suspend fun setUploadCapBytesPerSecond(hash: String, bytesPerSecond: Long) {
        throw ClientRefusedException("This client has no per-transfer upload cap")
    }
}

/** Which engine an adapter drives; Local is the device's own libtorrent. */
internal enum class TorrentClientKind {
    LOCAL, RTORRENT, TRANSMISSION, QBITTORRENT, DELUGE;

    companion object {
        fun of(remote: RemoteBackendKind): TorrentClientKind = when (remote) {
            RemoteBackendKind.RTORRENT -> RTORRENT
            RemoteBackendKind.TRANSMISSION -> TRANSMISSION
            RemoteBackendKind.QBITTORRENT -> QBITTORRENT
            RemoteBackendKind.DELUGE -> DELUGE
        }
    }
}

/** Explicit, observed operation support; absent means unsupported here. */
internal enum class ClientCapability {
    ADD_TORRENT_FILE, ADD_MAGNET, ADD_STOPPED, ADD_TO_DIRECTORY,
    START_STOP, FILE_PRIORITIES,
    REMOVE, REMOVE_WITH_DATA, SET_LOCATION, RECHECK,
    /** [TorrentClient.transferSource] can re-create a job elsewhere. */
    EXPORT_JOB,
    /**
     * [TorrentClient.add] itself applies [ClientAddRequest.skippedFiles] to the
     * still-stopped job and starts only after the skips are confirmed (one
     * client-side write sequence, e.g. Local's single fenced native Add).
     */
    ADD_WITH_FILE_CHOICES
}

internal data class ClientSessionInfo(
    val kind: TorrentClientKind,
    val version: String,
    /** Client-reported default; presentation and Add default only, not a verified Place. */
    val defaultDownloadDirectory: String?,
    val capabilities: Set<ClientCapability>,
    /** Distinct levels this client stores; others map to the nearest wanted level. */
    val filePriorities: Set<ClientFilePriority> = ClientFilePriority.entries.toSet()
) {
    /** The value this client will actually store for [requested]. */
    fun storable(requested: ClientFilePriority): ClientFilePriority = when {
        requested in filePriorities -> requested
        requested == ClientFilePriority.SKIP -> requested
        else -> ClientFilePriority.NORMAL
    }
}

internal enum class ClientRunState {
    STOPPED, DOWNLOADING, SEEDING, CHECKING, QUEUED, METADATA, MOVING, ERROR, UNKNOWN;

    /** null = the client did not say clearly; never guess Started/Stopped. */
    val started: Boolean? get() = when (this) {
        STOPPED -> false
        DOWNLOADING, SEEDING, METADATA -> true
        else -> null
    }
}

internal data class ClientTorrent(
    val hash: String,
    val name: String,
    val runState: ClientRunState,
    val totalBytes: Long?,
    val completedBytes: Long?,
    val downloadBytesPerSecond: Long?,
    val uploadBytesPerSecond: Long?,
    /** Client-reported only, never independent filesystem proof. */
    val downloadDirectory: String?
)

/** Shared priority semantics. Clients with fewer levels map down explicitly. */
internal enum class ClientFilePriority { SKIP, LOW, NORMAL, HIGH }

internal data class ClientFile(
    /** The client's own file index in metainfo order. */
    val index: Int,
    val path: String,
    val sizeBytes: Long,
    val completedBytes: Long?,
    val priority: ClientFilePriority
)

internal sealed interface ClientAddSource {
    val infoHash: String

    class TorrentFile(val bytes: ByteArray) : ClientAddSource {
        override val infoHash: String = TorrentMetainfoReader.preview(bytes).infoHashV1.lowercase(Locale.ROOT)
        override fun toString(): String = "TorrentFile(<redacted>)"
    }

    class Magnet(val uri: String) : ClientAddSource {
        override val infoHash: String = ClientHashes.magnetInfoHash(uri)
        override fun toString(): String = "Magnet(<redacted>)"
    }
}

internal data class ClientAddRequest(
    val source: ClientAddSource,
    /** null = the client's own default; otherwise an exact absolute directory. */
    val directory: String?,
    val startNow: Boolean,
    /**
     * Files the person unticked in the reviewed .torrent layout: metainfo
     * index → reviewed relative path. Applied by the shared workflow while
     * the job is still stopped, before any requested Start.
     */
    val skippedFiles: Map<Int, String> = emptyMap(),
    /**
     * Wanted files the person raised or lowered in the same review: metainfo
     * index → reviewed path and a non-default level (Low or High). Applied
     * together with [skippedFiles] while stopped, before any requested Start.
     */
    val filePriorities: Map<Int, ReviewedFilePriority> = emptyMap()
) {
    init {
        directory?.let { ClientHashes.requireDirectory(it) }
        require((skippedFiles.isEmpty() && filePriorities.isEmpty()) || source is ClientAddSource.TorrentFile) {
            "File choices need a reviewed .torrent layout"
        }
        require(skippedFiles.keys.all { it >= 0 } && skippedFiles.values.all { it.isNotBlank() })
        require(filePriorities.keys.all { it >= 0 } && filePriorities.keys.none { it in skippedFiles }) {
            "A file is either skipped or given a priority, not both"
        }
    }

    val hasFileChoices: Boolean get() = skippedFiles.isNotEmpty() || filePriorities.isNotEmpty()

    /** Every reviewed choice as the level the client must store: skipped → SKIP. */
    val reviewedChoices: Map<Int, ReviewedFilePriority> get() =
        skippedFiles.mapValues { (_, path) -> ReviewedFilePriority(path, ClientFilePriority.SKIP, allowSkip = true) } +
            filePriorities
}

/** One reviewed file and the level chosen for it at Add (Low or High; SKIP only internally). */
internal data class ReviewedFilePriority(
    val path: String,
    val priority: ClientFilePriority,
    private val allowSkip: Boolean = false
) {
    init {
        require(path.isNotBlank())
        require(priority == ClientFilePriority.LOW || priority == ClientFilePriority.HIGH ||
            (allowSkip && priority == ClientFilePriority.SKIP)) { "Only Low or High is a reviewed priority" }
    }
}

/** Definite refusal: nothing was (or could have been) changed. */
internal class ClientRefusedException(message: String) : IOException(message)

/** A write may have been delivered; reconcile read-only, never replay. */
internal class ClientWriteUncertainException(message: String) : IOException(message)

/** The client accepted a write, but its consequential effect remains unverified. */
internal class ClientPostWritePendingException(message: String) : IOException(message)

internal object ClientMagnets {
    /** magnet:?xt=urn:btih:<hash>&dn=<name>&tr=... (exact hash; trackers as given). */
    fun build(hash: String, name: String?, trackers: List<String>): ClientAddSource.Magnet {
        val exact = ClientHashes.requireHash(hash)
        fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
        val uri = buildString {
            append("magnet:?xt=urn:btih:").append(exact)
            name?.takeIf { it.isNotBlank() }?.let { append("&dn=").append(enc(it.take(512))) }
            trackers.filter { it.length <= 2048 && (it.startsWith("http://") || it.startsWith("https://") ||
                it.startsWith("udp://")) }.distinct().take(50).forEach { append("&tr=").append(enc(it)) }
        }
        return ClientAddSource.Magnet(uri)
    }
}

internal object ClientHashes {
    private val HEX40 = Regex("[0-9a-f]{40}")

    fun requireHash(hash: String): String {
        val lower = hash.lowercase(Locale.ROOT)
        require(HEX40.matches(lower)) { "Invalid v1 torrent hash" }
        return lower
    }

    fun requireDirectory(path: String): String {
        require(path.startsWith("/") && path.length in 1..4096 &&
            path.none { it.isISOControl() } &&
            path.split('/').none { it == "." || it == ".." }) {
            "Choose a specific absolute client directory"
        }
        return path
    }

    /** Equivalent directory spellings: trailing separators are not identity. */
    fun sameDirectory(a: String?, b: String?): Boolean =
        a != null && b != null && a.trimEnd('/').ifEmpty { "/" } == b.trimEnd('/').ifEmpty { "/" }

    /** Exactly one v1 `xt=urn:btih:` (40 hex or 32 base32 characters). */
    fun magnetInfoHash(magnet: String): String {
        require(magnet.startsWith("magnet:?", ignoreCase = true) && magnet.length <= 8192 &&
            magnet.none { it.isISOControl() }) { "Expected a bounded magnet link" }
        val xt = magnet.substringAfter('?').split('&').mapNotNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size != 2 || !pair[0].equals("xt", true)) null
            else URLDecoder.decode(pair[1], "UTF-8")
        }.filter { it.startsWith("urn:btih:", true) }
        require(xt.size == 1) { "Use exactly one v1 magnet identity" }
        val raw = xt.single().substring(9)
        return when (raw.length) {
            40 -> requireHash(raw)
            32 -> base32ToHex(raw)
            else -> throw IllegalArgumentException("Only v1 info hashes are supported")
        }
    }

    private fun base32ToHex(value: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0L
        var bits = 0
        val out = StringBuilder()
        for (c in value.uppercase(Locale.ROOT)) {
            val v = alphabet.indexOf(c)
            require(v >= 0) { "Invalid base32 magnet hash" }
            buffer = (buffer shl 5) or v.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.append("%02x".format(((buffer shr bits) and 0xff).toInt()))
            }
        }
        return requireHash(out.toString())
    }
}

/** The single place a typed profile becomes a write-capable client. */
internal object RemoteTorrentClients {
    fun create(
        kind: RemoteBackendKind,
        credentials: RemoteCredentials,
        delugeDaemonHostId: String?
    ): TorrentClient = when (kind) {
        RemoteBackendKind.TRANSMISSION -> TransmissionClient(credentials)
        RemoteBackendKind.QBITTORRENT -> QBittorrentClient(credentials)
        RemoteBackendKind.DELUGE -> DelugeClient(credentials, requireNotNull(delugeDaemonHostId) {
            "Explicit Deluge daemon ID required"
        })
        RemoteBackendKind.RTORRENT -> throw IllegalArgumentException("rTorrent uses its established adapter")
    }
}

/**
 * One client's own seeding counters for one exact hash. Unknown values stay
 * null (never zero). [publicTrackerUrls] is non-null only when the client
 * reported the transfer as public; never log or persist it.
 */
internal data class ClientSeedingFacts(
    val hash: String,
    val uploadedBytes: Long?,
    val downloadedBytes: Long?,
    /** Accumulated seeding time reported by the client itself. */
    val seedingSeconds: Long?,
    /** Complete and currently seeding (not stopped, checking or queued). */
    val seeding: Boolean?,
    val isPrivate: Boolean?,
    val publicTrackerUrls: List<String>?
) {
    override fun toString() = "ClientSeedingFacts(private transfer)"
}
