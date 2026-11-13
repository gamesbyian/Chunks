package io.github.gamesbyian.chunks.engine

import java.io.File
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.LinkOption
import java.net.InetSocketAddress
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
import org.libtorrent4j.alerts.TorrentDeletedAlert
import org.libtorrent4j.alerts.TorrentDeleteFailedAlert
import java.util.concurrent.atomic.AtomicReference
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
import org.libtorrent4j.swig.session_handle

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
    /**
     * POSIX is the shipping backend (Android 15 FUSE aborts with mmap), so it
     * is also the default: real-byte engine tests exercise what the app ships.
     * DEFAULT (libtorrent's mmap) remains only for explicit comparison; a
     * persistent zero block after CACHE_FLUSHED was reproduced only with it.
     */
    val diskIoMode: LocalDiskIoMode = LocalDiskIoMode.POSIX
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
    val addedAtEpochMillis: Long? = null,
    /** Ephemeral exact native Add/restore generation; never persisted or reused for a new handle. */
    val nativeReviewToken: String = "",
    /**
     * libtorrent is running only to hash-check the files already on disk and
     * will stop the job by itself when the check ends (stop_when_ready), before
     * any peer download or upload. Not a payload Start.
     */
    val stopsAfterCheck: Boolean = false
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

/** Native lifecycle outcomes: verified state, explicit preflight refusal, uncertain mutation, or absence. */
enum class LocalCommandOutcome { VERIFIED, REFUSED, UNCERTAIN, NOT_FOUND }

data class LocalCommandResult(val outcome: LocalCommandOutcome, val status: LocalTransferStatus?)

/** Exact v1 identity-gated, stopped-only repair of a legacy transfer with no trackers. */
enum class LocalTrackerRepairOutcome { VERIFIED, REFUSED, UNCERTAIN, NOT_FOUND }

/**
 * A verified exact-identity user request, not a claim that asynchronous native
 * deletion has finished. Separate readback must inspect the real payload files.
 */
enum class LocalDeleteDataDispatch { REQUESTED, REFUSED, UNCERTAIN, NOT_FOUND }
enum class LocalDeleteDataReadback { ABSENT, STILL_PRESENT, UNAVAILABLE, NATIVE_FAILED }

data class LocalDeleteDataReceipt(
    val dispatch: LocalDeleteDataDispatch,
    val targets: List<File> = emptyList(),
    val reason: String? = null,
    internal val nativeCompletion: AtomicReference<Boolean?>? = null
) {
    /** Independent read-only witness, including pre-existing ordinary payload files. */
    fun payloadReadback(): LocalDeleteDataReadback {
        if (targets.isEmpty() || dispatch !in setOf(
                LocalDeleteDataDispatch.REQUESTED, LocalDeleteDataDispatch.UNCERTAIN))
            return LocalDeleteDataReadback.UNAVAILABLE
        // JNI removal is asynchronous. Mere absence before a matching native
        // deletion alert cannot certify this delete-data operation.
        when (nativeCompletion?.get()) {
            true -> Unit
            false -> return LocalDeleteDataReadback.NATIVE_FAILED
            null -> return LocalDeleteDataReadback.UNAVAILABLE
        }
        for (target in targets) {
            val path = target.toPath()
            try {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
                    return LocalDeleteDataReadback.STILL_PRESENT
                if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
                    return LocalDeleteDataReadback.UNAVAILABLE
            } catch (_: Exception) { return LocalDeleteDataReadback.UNAVAILABLE }
        }
        return LocalDeleteDataReadback.ABSENT
    }
}

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
    private val reviewSessionId = java.util.UUID.randomUUID().toString()
    private var reviewSequence = 0L
    private val nativeReviewTokens = ConcurrentHashMap<String, String>()
    private fun mintReviewToken(): String = "$reviewSessionId:${++reviewSequence}"
    // Keep timed-out requests pending until their real alert arrives: a late
    // alert must not confirm a different save generation.
    private val pendingSaves = LocalResumeSaveRequests<TorrentHandle>()
    @Volatile private var resumeEvents: LocalResumeStore? = null

    // Cache flushes need the same handle-bound completion semantics as resume
    // saves. In particular, a late alert for a removed torrent must never
    // confirm the cache of a replacement with the same infohash.
    private val pendingFlushes = LocalCacheFlushGate<TorrentHandle>(this)
    // libtorrent 2.0 hashes a piece from its in-memory store buffer, so a piece
    // can pass (progress 100%, SEEDING) while its disk write FAILED. For
    // ENOSPC/EACCES/EROFS/EFBIG upstream handle_disk_error() only sets upload
    // mode, with no torrent error; the fenced cache flush still succeeds over
    // a hole that reads back as zeros. Any file_error_alert on this exact
    // native handle therefore revokes "complete on disk" for its lifetime.
    private val storageFileErrors = ConcurrentHashMap<String, Pair<TorrentHandle, String>>()
    @Volatile private var started = false

    /** Resume files that could not be restored on start; they are left untouched. */
    @Volatile var restoreProblems: List<String> = emptyList()
        private set

    /** Magnets still fetching metadata in upload mode (no payload requests). */
    private val awaitingMetadata: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Explicit Add-and-start consent while magnet metadata is still unresolved. */
    private val startWhenMetadataArrives: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val magnetCollisionProblems = ConcurrentHashMap<String, String>()
    // A checked .torrent Add is stopped; another writer may plant a foreign
    // payload file before the FIRST Start. Keep that admission pending until
    // native Start is actually submitted. This is session-only and does NOT
    // prove post-Start/Stop or process-restart ownership.
    private data class CheckedFirstStart(val saveDirectory: File, val paths: List<String>)
    private val checkedFirstStarts = ConcurrentHashMap<String, CheckedFirstStart>()
    private val checkedStartProblems = ConcurrentHashMap<String, String>()
    // Native remove is asynchronous: hold identity until session.stop joins I/O.
    private val retiredThisSession = ConcurrentHashMap.newKeySet<String>()
    // Keep the removed native handles until libtorrent confirms they are
    // invalid. Its API documents that a removed handle remains valid while
    // asynchronous native tasks (including disk I/O) retain references.
    // Only after invalidation may its same-session path claim be released.
    // Both additions and removals hold this engine's monitor.
    private val retiredNativeHandles = HashMap<String, TorrentHandle>()
    // Asynchronous native confirmation is never inferred from a vanished
    // session row. Each receipt owns the exact pending flag installed before
    // submitting its single delete_files request.
    private val pendingNativeDeletions = ConcurrentHashMap<String, AtomicReference<Boolean?>>()
    private val deleteAlertsObserved = java.util.concurrent.atomic.AtomicInteger()
    private val deleteAlertsUnmatched = java.util.concurrent.atomic.AtomicInteger()
    /** Diagnostic counts only: no hashes, names, paths or credentials. */
    internal fun nativeDeletionAlertCounts(): Pair<Int, Int> =
        deleteAlertsObserved.get() to deleteAlertsUnmatched.get()
    // Restored stopped jobs must reclaim their declared paths before another
    // checked torrent can reserve the same backing files in this process.
    private val restoredNativeJobs = ConcurrentHashMap.newKeySet<String>()
    // A different stopped checked job may target the same EMPTY native paths.
    // Reserve declared paths through Start/Stop, not just until the first Start.
    // This in-session exclusion is NOT a durable native ownership boundary.
    private val checkedPayloadReservations = LocalCheckedPayloadReservations()
    // Metadata-ready upload-only magnets are intentionally persisted paused;
    // native UPLOAD_MODE remains their durable don't-write-payload marker.
    // The next explicit Start rechecks paths, including after process restart.

    private val engineListener = object : AlertListener {
        override fun types() = intArrayOf(AlertType.SAVE_RESUME_DATA.swig(),
            AlertType.SAVE_RESUME_DATA_FAILED.swig(), AlertType.METADATA_RECEIVED.swig(),
            AlertType.CACHE_FLUSHED.swig(), AlertType.TORRENT_DELETED.swig(),
            AlertType.TORRENT_DELETE_FAILED.swig(), AlertType.TORRENT_FINISHED.swig(),
            AlertType.FILE_ERROR.swig())
        override fun alert(alert: Alert<*>) {
            // Removed handles disappear from handles[] before the async alert.
            // Consume exactly the matching v1 infohash without acquiring the
            // engine monitor (close() may be joining the dispatcher).
            when (alert) {
                is TorrentDeletedAlert -> {
                    deleteAlertsObserved.incrementAndGet()
                    val ids = listOf(alert.infoHashes.getV1().toHex(),
                        alert.infoHashes.getBest().toHex()).map { it.lowercase(Locale.ROOT) }.distinct()
                    val matched = ids.firstNotNullOfOrNull { pendingNativeDeletions.remove(it) }
                    if (matched == null) deleteAlertsUnmatched.incrementAndGet()
                    matched?.set(true)
                    return
                }
                is TorrentDeleteFailedAlert -> {
                    deleteAlertsObserved.incrementAndGet()
                    val ids = listOf(alert.infoHashes.getV1().toHex(),
                        alert.infoHashes.getBest().toHex()).map { it.lowercase(Locale.ROOT) }.distinct()
                    val matched = ids.firstNotNullOfOrNull { pendingNativeDeletions.remove(it) }
                    if (matched == null) deleteAlertsUnmatched.incrementAndGet()
                    matched?.set(false)
                    return
                }
            }
            val handle = (alert as? TorrentAlert<*>)?.handle() ?: return
            val key = synchronized(handles) { handles.entries.firstOrNull { it.value == handle }?.key } ?: return
            if (alert.type() == AlertType.FILE_ERROR) {
                // Keep the first failure; it is the one that left bytes unwritten.
                storageFileErrors.putIfAbsent(key, handle to
                    "Storage error: ${runCatching { alert.message() }.getOrNull() ?: "file operation failed"}")
                return
            }
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
            if (alert.type() == AlertType.TORRENT_FINISHED) {
                // Upstream libtorrent guidance (and qBittorrent / client_test):
                // persist resume data when a torrent finishes. Otherwise the
                // newest durable snapshot can be the zero-piece Add/metadata
                // record, and a process death before the post-Stop checkpoint
                // restores a complete payload as 0% (libtorrent trusts resume
                // pieces and does not recheck). Never await in the dispatcher.
                requestCompletionResumeSave(key, handle)
                return
            }
            var persisted = false
            try {
                // A magnet without metadata has nothing restorable yet; never persist it half-known.
                if (alert is SaveResumeDataAlert && alert.params().torrentInfo?.isValid == true) {
                    val bytes = AddTorrentParams.writeResumeDataBuf(alert.params())
                    // The handle may have been removed while the alert was being decoded.
                    // Lock across both identity recheck and disk commit; Remove uses this
                    // same fence before recording durable retirement state.
                    LocalResumeWriteFence.writeIfCurrent(handles, key, handle) {
                        writeResume(key, bytes)
                        persisted = true
                    }
                }
            } catch (_: Exception) {
                // Corrupt native data or a failed durable transaction is not a
                // confirmed save. Do not let an exception escape the alert
                // dispatcher or overwrite the previous verified resume file.
            } finally {
                pendingSaves.complete(key, handle, persisted)
                // A completion that coalesced into an OLDER in-flight request
                // must not be satisfied by that pre-completion snapshot.
                if (completionResaveWanted.remove(key)) requestCompletionResumeSave(key, handle)
            }
        }
    }

    // Keys whose TORRENT_FINISHED arrived while an earlier save was pending.
    private val completionResaveWanted = ConcurrentHashMap.newKeySet<String>()

    /**
     * Non-blocking, handle-fenced completion checkpoint; safe on the alert
     * dispatcher. [alertHandle] only identifies the job: an alert's handle
     * wraps native memory owned by that alert, which is released with its
     * batch, so the pending request must hold the session's own handle.
     */
    private fun requestCompletionResumeSave(key: String, alertHandle: TorrentHandle) {
        if (config.stateDirectory == null) return
        synchronized(handles) {
            val handle = handles[key] ?: return
            if (!started || handle != alertHandle || !handle.isValid) return
            if (pendingSaves.pendingFor(key, handle) != null) {
                completionResaveWanted += key
                return
            }
            pendingSaves.acquire(key, handle) {
                handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT.or_(
                    TorrentHandle.FLUSH_DISK_CACHE))
            }
        }
    }

    /**
     * Metadata-ready magnets must verify their prospective native paths stay
     * inside the chosen Place. Existing regular files may be rechecked/replaced
     * as normal torrent payload, including after deferred metadata arrival.
     */
    private fun transitionMagnetMetadata(key: String, handle: TorrentHandle) {
        // The alert dispatcher must never acquire the engine monitor: close()
        // holds it while joining that dispatcher. Share only the handle lock
        // with command submission and handle retirement (engine -> handles).
        synchronized(handles) {
            if (!started || handles[key] != handle || !handle.isValid || !awaitingMetadata.remove(key)) return
            val autoStart = startWhenMetadataArrives.remove(key)
            val problem = inspectMagnetCollision(key, handle)
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

    private fun inspectMagnetCollision(key: String, handle: TorrentHandle): String? {
        val paths = try {
            val info = handle.torrentFile() ?: return "Metadata file layout is unavailable"
            val files = info.files()
            val count = files.numFiles()
            if (!LocalPayloadCollisionSurvey.supportsNativeFileCount(count))
                return "Torrent payload path count is unsupported"
            (0 until count).asSequence()
                .filterNot { files.padFileAt(it) }.map { files.filePath(it) }.toList()
        } catch (_: Exception) { return "Torrent file layout cannot be inspected" }
        if (paths.isEmpty()) return "Torrent has no inspectable payload files"
        val root = File(handle.savePath())
        when (val survey = LocalPayloadCollisionSurvey.inspect(root, paths)) {
            is LocalPayloadCollisionSurvey.Result.ClearAtObservation -> Unit
            is LocalPayloadCollisionSurvey.Result.ExistingPaths -> Unit
            is LocalPayloadCollisionSurvey.Result.Unknown ->
                return "Cannot verify magnet payload paths: " + survey.problem
        }
        // Metadata becomes available asynchronously. The first metadata
        // callback or explicit Start MUST reserve prospective writer paths
        // before removing UPLOAD_MODE. Otherwise a checked .torrent can hold
        // the same currently empty names and both native handles may write.
        // Rechecking an already reserved magnet at Start must not block itself.
        return when (val admission = checkedPayloadReservations.reserve(
            key, root, paths, allowExistingOwner = true
        )) {
            LocalCheckedPayloadReservations.Result.Reserved -> null
            is LocalCheckedPayloadReservations.Result.Refused -> admission.problem
        }
    }

    /**
     * Resume records reconstruct native handles, not in-process reservations.
     * Reject unsupported/escaping paths and mutually exclusive torrent writers,
     * while allowing ordinary existing regular files at the selected destination.
     * A returned reservation is only a session-local conflict exclusion, not an
     * atomic filesystem ownership or cross-process race guarantee.
     */
    /**
     * A survey is a point-in-time pathname observation, not an access grant.
     * Recheck the surveyed existing regular-file sample before native Start.
     *
     * O_NOFOLLOW blocks a swapped leaf symlink; O_NONBLOCK prevents a swapped
     * FIFO from hanging the command; fstat validates the *opened descriptor*.
     * Check parents again without following symlinks as best-effort containment.
     * As with the survey, this is not an atomic guarantee against external
     * replacement of a parent or loss of access after the check.
     *
     * Internal visibility permits an installed Android negative regression to
     * exercise the exact production probe against disposable FIFO/link paths.
     */
    internal fun canReadExistingPayload(root: File, relative: String): Boolean {
        if (relative.isBlank() || relative.startsWith('/') || relative.contains('\\')) return false
        val parts = relative.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
        return try {
            val base = root.toPath().toAbsolutePath().normalize()
            if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(base)) return false
            var path = base
            parts.forEachIndexed { index, segment ->
                path = path.resolve(segment)
                if (!path.startsWith(base)) return false
                if (index < parts.lastIndex &&
                    (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ||
                     Files.isSymbolicLink(path))) return false
            }
            if (Build.VERSION.SDK_INT == 0) {
                // JVM/libtorrent4j native regression tests execute with Android
                // stubs. They still require a real no-follow read, but cannot
                // call android.system.Os. Shipping minSdk=29 always uses the
                // nonblocking kernel descriptor branch below.
                Files.newByteChannel(
                    path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                ).use { channel -> channel.read(ByteBuffer.allocate(1)) }
                true
            } else {
                val flags = OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or
                    OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW
                val descriptor = Os.open(path.toString(), flags, 0)
                try {
                    if (!OsConstants.S_ISREG(Os.fstat(descriptor).st_mode)) return false
                    Os.read(descriptor, ByteArray(1), 0, 1)
                    true
                } finally {
                    Os.close(descriptor)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun unreadableExistingPayload(
        root: File, survey: LocalPayloadCollisionSurvey.Result
    ): String? {
        val observed = (survey as? LocalPayloadCollisionSurvey.Result.ExistingPaths)?.paths
            ?: return null
        for (relative in observed) {
            if (!canReadExistingPayload(root, relative)) {
                return "Existing payload cannot be opened for reading. " +
                    "Check access to its download folder before starting again"
            }
        }
        return null
    }

    /**
     * The checked first-Start survey of the exact recorded destination: the
     * observed survey (null if not reached) and a refusal reason, if any.
     */
    private fun firstStartSurvey(
        pending: CheckedFirstStart, handle: TorrentHandle
    ): Pair<LocalPayloadCollisionSurvey.Result?, String?> {
        if (File(handle.savePath()).absolutePath != pending.saveDirectory.absolutePath)
            return null to "Download destination changed since Add; Start refused"
        val survey = LocalPayloadCollisionSurvey.inspect(pending.saveDirectory, pending.paths)
        return survey to when (survey) {
            is LocalPayloadCollisionSurvey.Result.ClearAtObservation -> null
            is LocalPayloadCollisionSurvey.Result.ExistingPaths ->
                unreadableExistingPayload(pending.saveDirectory, survey)
            is LocalPayloadCollisionSurvey.Result.Unknown ->
                "Cannot verify file paths before Start: " + survey.problem
        }
    }

    /**
     * "Stopped after verification" for a checked Add that was never started:
     * when readable existing files sit at its exact torrent paths, queue
     * libtorrent's own check with stop_when_ready and return at once. libtorrent
     * reads every piece and pauses itself as it leaves checking, before any peer
     * download or upload; [LocalTransferStatus.stopsAfterCheck] reports the run.
     * Stop overrides it as usual. False (nothing changed) when there is nothing
     * to verify or the first-Start survey refuses the paths. Never retried.
     */
    fun beginStoppedVerification(infoHash: String): Boolean = try {
        synchronized(this) {
            synchronized(handles) {
                val key = normal(infoHash)
                val handle = handles[key]?.takeIf { started && it.isValid } ?: return false
                val pending = checkedFirstStarts[key] ?: return false
                val state = handle.status(TorrentHandle.QUERY_NAME)
                if (!state.flags().and_(org.libtorrent4j.TorrentFlags.PAUSED).non_zero() ||
                    !state.hasMetadata() || awaitingMetadata.contains(key)) return false
                val (survey, problem) = firstStartSurvey(pending, handle)
                if (problem != null || survey !is LocalPayloadCollisionSurvey.Result.ExistingPaths)
                    return false
                val pendingCheck = state.state() == TorrentStatus.State.CHECKING_FILES ||
                    state.state() == TorrentStatus.State.CHECKING_RESUME_DATA
                try {
                    // Same order as recheckStopped: check queued, then auto-stop, then run.
                    if (!pendingCheck) handle.forceRecheck()
                    handle.setFlags(org.libtorrent4j.TorrentFlags.STOP_WHEN_READY)
                    handle.resume()
                    true
                } catch (_: Exception) {
                    runCatching { handle.unsetFlags(org.libtorrent4j.TorrentFlags.STOP_WHEN_READY); handle.pause() }
                    false
                }
            }
        }
    } catch (_: Exception) { false }

    private fun reserveRestoredJobPaths(key: String, handle: TorrentHandle): String? {
        val paths = try {
            val info = handle.torrentFile() ?: return "Restored torrent metadata is unavailable"
            val files = info.files()
            val count = files.numFiles()
            if (!LocalPayloadCollisionSurvey.supportsNativeFileCount(count))
                return "Restored torrent file count is unsupported"
            (0 until count).asSequence().filterNot { files.padFileAt(it) }
                .map { files.filePath(it) }.toList()
        } catch (_: Exception) {
            return "Restored torrent file paths cannot be inspected"
        }
        if (paths.isEmpty()) return "Restored torrent has no inspectable files"
        val root = File(handle.savePath())
        when (val survey = LocalPayloadCollisionSurvey.inspect(root, paths)) {
            is LocalPayloadCollisionSurvey.Result.Unknown ->
                return "Restored download paths are unsafe: " + survey.problem
            else -> unreadableExistingPayload(root, survey)?.let { return it }
        }
        return when (val reserved = checkedPayloadReservations.reserve(
            key, root, paths, allowExistingOwner = true
        )) {
            LocalCheckedPayloadReservations.Result.Reserved -> null
            is LocalCheckedPayloadReservations.Result.Refused -> reserved.problem
        }
    }

    /**
     * Request a non-blocking resume snapshot as soon as magnet metadata exists.
     * The initial Add/metadata callback ordering is asynchronous; both entry
     * points call this, and pendingSaves coalesces duplicates for this handle.
     * Serialize registration against Remove/Close but NEVER await an alert here.
     */
    private fun requestMetadataResumeSave(key: String, candidate: TorrentHandle) {
        if (config.stateDirectory == null) return
        synchronized(handles) {
            // The metadata callback passes an alert-owned handle wrapper whose
            // native storage is freed with its alert batch. A pending request
            // that kept it could never match its completion later, leaving a
            // permanent "prior" save that makes the post-Stop checkpoint time
            // out (#5). Register only the session's own handle.
            val handle = handles[key] ?: return
            if (!started || handle != candidate || !handle.isValid) return
            pendingSaves.acquire(key, handle) {
                handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
            }
        }
    }

    @Synchronized
    fun start() {
        if (started) return
        // Verify the complete app-private resume inventory before starting JNI.
        // A failed File.listFiles() must never masquerade as zero saved jobs.
        val resumeFiles = config.stateDirectory?.let(LocalResumeDirectoryInventory::prepare)
        val store = if (resumeFiles != null) LocalResumeStore.open(
            requireNotNull(config.stateDirectory), resumeFiles) else null
        resumeEvents = store
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
        if (store != null) {
            // Only this newly started native session can complete a stranded
            // fsynced REMOVE_INTENT. The previous native session no longer
            // exists, and restore() never reloads pending removal identities.
            // A same-process transient Remove failure must NOT be treated as
            // success here: this runs only once on a fresh engine start.
            restore(store)
            reconcileColdRemovalIntents(store)
        }
    }

    /**
     * Finalize owner-authorized Remove intents only after the previous native
     * session is gone and a fresh session has been established. This does not
     * delete any payload and never retries the original native mutation.
     * Other restored jobs remain untouched. If fsynced completion fails,
     * registration stays blocked and the failure remains visible.
     */
    private fun reconcileColdRemovalIntents(store: LocalResumeStore) {
        val problems = restoreProblems.toMutableList()
        val pending = try { store.pendingRemovals() } catch (_: Exception) {
            restoreProblems = problems + "Pending Remove state could not be inspected"
            return
        }
        for (hash in pending) {
            if (handles.containsKey(hash) || retiredNativeHandles.containsKey(hash)) {
                problems += "Pending Remove still has a native session identity: $hash"
                continue
            }
            try { store.removeCommit(hash) } catch (_: Exception) {
                problems += "Pending Remove completion requires storage recovery: $hash"
                // SQLite marks uncertain commits unhealthy until reopened.
                // Never reinterpret or repeatedly write such a transaction.
                break
            }
        }
        restoreProblems = problems
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
     * Production Add: validate torrent-relative payload paths before registering
     * a job. Existing regular files inside the selected directory are permitted
     * as ordinary libtorrent recheck/reuse/replacement candidates.
     * A clean survey is point-in-time only, not a race-free containment guarantee.
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
            val count = files.numFiles()
            if (!LocalPayloadCollisionSurvey.supportsNativeFileCount(count))
                return LocalAddResult.Refused("Torrent payload path count is unsupported. Nothing added.")
            (0 until count).asSequence()
                .filterNot { files.padFileAt(it) }
                .map { files.filePath(it) }.toList()
        } catch (_: Exception) {
            return LocalAddResult.Refused("Torrent file paths could not be inspected. Nothing added.")
        }
        if (paths.isEmpty()) return LocalAddResult.Refused("Torrent has no inspectable payload")
        when (val survey = LocalPayloadCollisionSurvey.inspect(saveDirectory, paths)) {
            is LocalPayloadCollisionSurvey.Result.ClearAtObservation -> Unit
            is LocalPayloadCollisionSurvey.Result.ExistingPaths -> Unit
            is LocalPayloadCollisionSurvey.Result.Unknown ->
                return LocalAddResult.Refused("Cannot verify download file paths: " +
                    survey.problem + ". Nothing added.")
        }
        val key = identity(info)
        if (handles.containsKey(key) || session.find(info.infoHash())?.isValid == true)
            return LocalAddResult.Refused("This torrent is already in the local engine")
        // Production .torrent Add reserves file paths BEFORE calling
        // addStopped(). Drain the exact old native tombstone here or its
        // surviving checked reservation would always reject same-hash re-Add,
        // even after libtorrent invalidates the old handle.
        releaseDrainedNativeRetirement(key)
        // Register BEFORE native Add. A failed resume-file fsync may throw after
        // libtorrent registered a stopped handle: the UI can still discover it.
        // That partially admitted handle must never evade the first-Start fence.
        when (val reservation = checkedPayloadReservations.reserve(key, saveDirectory, paths)) {
            LocalCheckedPayloadReservations.Result.Reserved -> Unit
            is LocalCheckedPayloadReservations.Result.Refused ->
                return LocalAddResult.Refused(reservation.problem + ". Nothing added.")
        }
        val admission = CheckedFirstStart(saveDirectory, paths)
        checkedFirstStarts[key] = admission
        try {
            val added = addStopped(torrentBytes, saveDirectory, peers)
            if (added !is LocalAddResult.AddedStopped && !handles.containsKey(key)) {
                checkedFirstStarts.remove(key, admission)
                checkedPayloadReservations.release(key)
            }
            return added
        } catch (failure: Exception) {
            // A failed resume write can leave a real native handle registered.
            // Retain BOTH fences unless the native job is definitely absent.
            if (!handles.containsKey(key)) {
                checkedFirstStarts.remove(key, admission)
                checkedPayloadReservations.release(key)
            }
            throw failure
        }
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
        // A prior removed handle is safe to reuse only after libtorrent has
        // retired all old native tasks. Never clear this guard by elapsed time.
        releaseDrainedNativeRetirement(hash)
        if (hash in retiredThisSession)
            return LocalAddResult.Refused(
                "The previous Remove is still finishing native file I/O. Try Add again when it settles; a full local-engine restart also drains old work. Downloaded files were kept.")
        if (resumeEvents?.canRegister(hash) == false)
            return LocalAddResult.Refused(
                "The previous saved or removed state of this torrent is not ready for safe reuse. Stop local transfers and restart the app's engine before re-adding. No files were deleted.")

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
        nativeReviewTokens[hash] = mintReviewToken()
        handles[hash] = handle
        // The asynchronous SAVE_RESUME_DATA callback writes immutable resume
        // events. Serialize initial Add, late saves and Remove using the
        // established native-handle fence; never overwrite a prior entry.
        if (config.stateDirectory != null) {
            val resumeBytes = AddTorrentParams.writeResumeDataBuf(params)
            check(LocalResumeWriteFence.writeIfCurrent(handles, hash, handle) {
                writeResume(hash, resumeBytes)
            }) { "Initial resume write lost its native handle" }
        }
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
        // A prior removed handle is safe to reuse only after libtorrent has
        // retired all old native tasks. Never clear this guard by elapsed time.
        releaseDrainedNativeRetirement(hash)
        if (hash in retiredThisSession)
            return LocalAddResult.Refused(
                "The previous Remove is still finishing native file I/O. Try Add again when it settles; a full local-engine restart also drains old work. Downloaded files were kept.")
        if (resumeEvents?.canRegister(hash) == false)
            return LocalAddResult.Refused(
                "The previous saved or removed state of this torrent is not ready for safe reuse. Stop local transfers and restart the app's engine before re-adding. No files were deleted.")
        // The previous handle may have been removed from the visible list
        // while native disk I/O still holds it. Its path claims are tombstoned
        // until session shutdown; do not resurrect the same hash via a magnet.
        if (checkedPayloadReservations.hasReservation(hash))
            return LocalAddResult.Refused("A previous download still reserves this destination until the engine restarts")
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
        nativeReviewTokens[hash] = mintReviewToken()
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

    /** Survey, consent and native Start share the metadata callback's handle fence. */
    fun resume(infoHash: String, readbackTimeoutMs: Long = 5_000): LocalCommandResult =
        command(infoHash, wantStopped = false, readbackTimeoutMs) { handle ->
            val key = normal(infoHash)
            // status() caches metadata for 500 ms. Query the same fresh native
            // flags as snapshot(), including persisted upload-only on restart.
            val state = handle.status(TorrentHandle.QUERY_NAME)
            // A stopped restored job does not inherit in-process Add claims.
            // Recheck and reacquire on every Start, including after permissions
            // or the selected destination changed since the previous session.
            if (restoredNativeJobs.contains(key)) {
                val problem = reserveRestoredJobPaths(key, handle)
                if (problem != null) {
                    checkedStartProblems[key] = "Restored path admission: $problem"
                    return@command false
                }
                if (checkedStartProblems[key]?.startsWith("Restored path admission:") == true)
                    checkedStartProblems.remove(key)
            }
            checkedFirstStarts[key]?.let { pending ->
                // Add's earlier ClearAtObservation is not permission to write
                // during a later Start. Re-survey the exact recorded native
                // destination while the handle's Start and metadata callback
                // share this monitor. Unknown also refuses.
                val problem = firstStartSurvey(pending, handle).second
                if (problem != null) {
                    checkedStartProblems[key] = problem
                    return@command false
                }
                checkedStartProblems.remove(key)
            }
            if (state.hasMetadata() && checkedFirstStarts[key] == null &&
                checkedPayloadReservations.hasReservation(key)) {
                val problem = inspectCheckedRestartCollision(key, handle)
                if (problem != null) {
                    checkedStartProblems[key] = problem
                    return@command false
                }
                checkedStartProblems.remove(key)
            }
            if (state.hasMetadata() &&
                (state.flags().and_(org.libtorrent4j.TorrentFlags.UPLOAD_MODE).non_zero() ||
                    magnetCollisionProblems.containsKey(key))) {
                val problem = inspectMagnetCollision(key, handle)
                if (problem != null) {
                    magnetCollisionProblems[key] = problem
                    return@command false
                }
                magnetCollisionProblems.remove(key)
                // Consume admission before native writes begin. A queued
                // metadata alert must not resurvey our authorized payload.
                awaitingMetadata.remove(key)
                startWhenMetadataArrives.remove(key)
                handle.unsetFlags(org.libtorrent4j.TorrentFlags.UPLOAD_MODE)
                requestMetadataResumeSave(key, handle)
            }
            if (awaitingMetadata.contains(key)) startWhenMetadataArrives.add(key)
            // An explicit Start is the retry after the person freed space or
            // fixed access; a new failed write records a fresh error.
            storageFileErrors.remove(key)
            // An ordinary Start must never inherit a recheck's auto-stop.
            handle.unsetFlags(org.libtorrent4j.TorrentFlags.STOP_WHEN_READY)
            handle.resume()
            // Only after native Start submission: an exception keeps the gate.
            // Future Stop/Start and process restoration remain separate P0.
            checkedFirstStarts.remove(key)
            true
        }

    /**
     * Point-in-time checked-job restart guard: retain path containment and
     * native layout checks, but allow existing regular files at authorized
     * torrent paths. Native piece verification decides usable data. This
     * survey cannot defeat path replacement after inspection.
     */
    private fun inspectCheckedRestartCollision(key: String, handle: TorrentHandle): String? {
        val root = File(handle.savePath())
        val paths: List<String>
        val progress: List<Long>
        try {
            val info = handle.torrentFile() ?: return "Native metadata unavailable before Start"
            val files = info.files()
            val count = files.numFiles()
            if (!LocalPayloadCollisionSurvey.supportsNativeFileCount(count))
                return "Torrent file layout unsupported before Start"
            val nativeProgress = handle.fileProgress()
            if (nativeProgress.size != count)
                return "Native per-file verification status unavailable before Start"
            val selectedPaths = ArrayList<String>(count)
            val selectedProgress = ArrayList<Long>(count)
            for (index in 0 until count) {
                if (files.padFileAt(index)) continue
                selectedPaths += files.filePath(index)
                selectedProgress += nativeProgress[index]
            }
            paths = selectedPaths
            progress = selectedProgress
        } catch (_: Exception) {
            return "Native file paths or verification status unavailable before Start"
        }
        if (paths.isEmpty()) return "Torrent has no inspectable backing paths"
        when (val reservation = checkedPayloadReservations.reserve(
            key, root, paths, allowExistingOwner = true
        )) {
            LocalCheckedPayloadReservations.Result.Reserved -> Unit
            is LocalCheckedPayloadReservations.Result.Refused ->
                return reservation.problem
        }
        return when (val check = LocalCheckedRestartGuard.inspect(root, paths, progress)) {
            LocalCheckedRestartGuard.Result.ClearAtObservation -> null
            is LocalCheckedRestartGuard.Result.Refused -> check.reason
        }
    }

    /** Declared non-pad payload paths of a stopped job with metadata, with its save folder. */
    private fun stoppedJobLayout(key: String): Pair<File, List<String>>? {
        val handle = handles[key]?.takeIf { started && it.isValid } ?: return null
        val status = handle.status(TorrentHandle.QUERY_NAME)
        if (!status.flags().and_(org.libtorrent4j.TorrentFlags.PAUSED).non_zero() || !status.hasMetadata()) return null
        val info = handle.torrentFile() ?: return null
        val files = info.files()
        val count = files.numFiles()
        if (!LocalPayloadCollisionSurvey.supportsNativeFileCount(count)) return null
        return File(handle.savePath()) to (0 until count).filterNot { files.padFileAt(it) }.map { files.filePath(it) }
    }

    /**
     * Read-only: other clients' partial files beside a STOPPED job's declared names
     * (FOREIGN-PARTIAL-TORRENT-RECOVERY P1). Covers a magnet once its metadata arrived.
     */
    internal fun foreignPartialOffer(infoHash: String): ForeignPartialAddPlan.Offer? = try {
        val layout = synchronized(this) { synchronized(handles) { stoppedJobLayout(normal(infoHash)) } }
        layout?.let { (folder, paths) -> ForeignPartialAddPlan.offerForPaths(paths, folder) }
    } catch (_: Exception) { null }

    /**
     * Explicit approval: rename exactly the reviewed, unchanged candidates (never replacing),
     * then queue libtorrent's full recheck. libtorrent4j keeps a paused job in CHECKING_FILES
     * without reading until Start, so the job stays stopped and the next ordinary Start
     * verifies every piece before any download. If the recheck cannot be queued the original
     * names are restored.
     */
    internal fun adoptForeignPartialsForStart(
        infoHash: String, reviewed: ForeignPartialAddPlan.Offer
    ): ForeignPartialAddPlan.Adoption = synchronized(this) {
        synchronized(handles) {
            val key = normal(infoHash)
            val layout = try { stoppedJobLayout(key) } catch (_: Exception) { null }
                ?: return ForeignPartialAddPlan.Adoption.Refused("The transfer is not stopped with known files")
            val (folder, paths) = layout
            val adoption = ForeignPartialAddPlan.adoptReviewedPaths(paths, folder, reviewed)
            if (adoption is ForeignPartialAddPlan.Adoption.Renamed) {
                val queued = try { handles[key]?.forceRecheck(); handles[key] != null } catch (_: Exception) { false }
                if (!queued) {
                    val restored = ForeignPartialAddPlan.undo(folder, adoption)
                    return ForeignPartialAddPlan.Adoption.Refused("The piece check could not be queued" +
                        if (restored) "; original names restored" else "; some names could not be restored")
                }
            }
            adoption
        }
    }

    /**
     * Explicit "Recheck" of a STOPPED job with metadata, using libtorrent's own
     * mechanism: force_recheck, then stop_when_ready, then resume. libtorrent
     * reads every piece and pauses itself inside the same network-thread state
     * transition that leaves checking, before any peer download or upload.
     * Read-only for payload bytes; the job ends stopped again.
     *
     * VERIFIED means the check ran and the job is stopped again, with
     * [LocalCommandResult.status] carrying the re-measured progress. UNCERTAIN
     * means it is still checking (or could not be observed) within the timeout;
     * the person can keep watching or Stop it. Never retried automatically.
     */
    fun recheckStopped(infoHash: String, timeoutMs: Long = 60_000): LocalCommandResult {
        require(timeoutMs in 0L..600_000L)
        val key = normal(infoHash)
        val handle = synchronized(this) {
            synchronized(handles) {
                val exact = handles[key]?.takeIf { started && runCatching { it.isValid }.getOrDefault(false) }
                    ?: return LocalCommandResult(LocalCommandOutcome.NOT_FOUND, null)
                val state = exact.status(TorrentHandle.QUERY_NAME)
                val paused = state.flags().and_(org.libtorrent4j.TorrentFlags.PAUSED).non_zero()
                if (!paused || !state.hasMetadata() || awaitingMetadata.contains(key) ||
                    startWhenMetadataArrives.contains(key))
                    return LocalCommandResult(LocalCommandOutcome.REFUSED, snapshot(key, exact))
                // A never-started checked Add reads through its recorded paths only
                // when the same survey that guards its first Start admits them.
                checkedFirstStarts[key]?.let { pending ->
                    if (firstStartSurvey(pending, exact).second != null)
                        return LocalCommandResult(LocalCommandOutcome.REFUSED, snapshot(key, exact))
                }
                // A stopped Add over existing files (or an adopted partial set)
                // already holds a queued check that libtorrent runs only once
                // unpaused; let that one run rather than queueing another.
                val pendingCheck = state.state() == TorrentStatus.State.CHECKING_FILES ||
                    state.state() == TorrentStatus.State.CHECKING_RESUME_DATA
                try {
                    // Order matters: once CHECKING, stop_when_ready waits for the
                    // transition out of checking instead of pausing immediately.
                    if (!pendingCheck) exact.forceRecheck()
                    exact.setFlags(org.libtorrent4j.TorrentFlags.STOP_WHEN_READY)
                    exact.resume()
                } catch (_: Exception) {
                    runCatching { exact.unsetFlags(org.libtorrent4j.TorrentFlags.STOP_WHEN_READY); exact.pause() }
                    return LocalCommandResult(LocalCommandOutcome.UNCERTAIN, snapshot(key, exact))
                }
                exact
            }
        }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val current = synchronized(handles) { handles[key] }
            if (current != handle) return LocalCommandResult(LocalCommandOutcome.UNCERTAIN, null)
            val native = try { handle.status(TorrentHandle.QUERY_NAME) } catch (_: Exception) { null }
            if (native != null) {
                val flags = native.flags()
                val paused = flags.and_(org.libtorrent4j.TorrentFlags.PAUSED).non_zero()
                val armed = flags.and_(org.libtorrent4j.TorrentFlags.STOP_WHEN_READY).non_zero()
                val checking = native.state() == TorrentStatus.State.CHECKING_FILES ||
                    native.state() == TorrentStatus.State.CHECKING_RESUME_DATA
                // libtorrent clears stop_when_ready exactly when it auto-pauses
                // on leaving checking. A Stop during the check also clears it,
                // but leaves the paused job in a checking state: not done.
                if (paused && !armed && !checking)
                    return LocalCommandResult(LocalCommandOutcome.VERIFIED, snapshot(key, handle))
            }
            if (System.nanoTime() >= deadline)
                return LocalCommandResult(LocalCommandOutcome.UNCERTAIN, snapshot(key, handle))
            Thread.sleep(50)
        }
    }

    fun pause(infoHash: String, readbackTimeoutMs: Long = 5_000): LocalCommandResult =
        command(infoHash, wantStopped = true, readbackTimeoutMs) {
            // User Stop overrides any earlier Add-and-start consent while
            // magnet metadata is in flight, and any pending recheck auto-stop.
            startWhenMetadataArrives.remove(normal(infoHash))
            it.unsetFlags(org.libtorrent4j.TorrentFlags.STOP_WHEN_READY)
            it.pause()
            true
        }

    private fun command(
        infoHash: String,
        wantStopped: Boolean,
        readbackTimeoutMs: Long,
        action: (TorrentHandle) -> Boolean
    ): LocalCommandResult {
        require(readbackTimeoutMs in 0..60_000)
        val key = normal(infoHash)
        synchronized(this) {
            synchronized(handles) {
                val handle = handles[key]?.takeIf { started && it.isValid }
                    ?: return LocalCommandResult(LocalCommandOutcome.NOT_FOUND, null)
                if (!action(handle))
                    return LocalCommandResult(LocalCommandOutcome.REFUSED, snapshot(key, handle))
            }
        }
        return waitForCommandReadback(key, wantStopped, readbackTimeoutMs)
    }

    private fun waitForCommandReadback(
        key: String, wantStopped: Boolean, readbackTimeoutMs: Long
    ): LocalCommandResult {
        val deadline = System.nanoTime() + readbackTimeoutMs * 1_000_000
        while (true) {
            val latest = status(key) ?: return LocalCommandResult(LocalCommandOutcome.NOT_FOUND, null)
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
        if (resumeEvents == null) return LocalTrackerRepairOutcome.UNCERTAIN
        val saved = try { saveResumeData() } catch (_: Exception) {
            return LocalTrackerRepairOutcome.UNCERTAIN
        }
        if (saved != handles.size) return LocalTrackerRepairOutcome.UNCERTAIN
        val onDisk = try {
            val bytes = resumeEvents?.current(key)
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
    /** Reject unsupported native path targets before priority-driven writes. */
    private fun checkedPriorityMutationProblem(key: String, handle: TorrentHandle): String? {
        if (!checkedPayloadReservations.hasReservation(key) ||
            checkedFirstStarts.containsKey(key)) return null
        val problem = inspectCheckedRestartCollision(key, handle)
        if (problem != null) checkedStartProblems[key] = problem
        else checkedStartProblems.remove(key)
        return problem
    }

    fun setFileWanted(infoHash: String, index: Int, wanted: Boolean, readbackTimeoutMs: Long = 5_000): LocalCommandOutcome {
        require(readbackTimeoutMs in 0..60_000)
        val key = normal(infoHash)
        synchronized(this) {
            val handle = handles[key] ?: return LocalCommandOutcome.NOT_FOUND
            val files = handle.torrentFile()?.files() ?: return LocalCommandOutcome.NOT_FOUND
            require(index in 0 until files.numFiles() && !files.padFileAt(index)) { "No such file" }
            if (wanted && checkedPriorityMutationProblem(key, handle) != null)
                return LocalCommandOutcome.REFUSED
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
            if (checkedPriorityMutationProblem(key, handle) != null)
                return LocalFileBatchOutcome.STALE_REVIEW
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
            // Finish in Order is an independent native filePriority writer.
            // Recheck destination path containment before priority-driven writes;
            // a completed read-only planner is not authorization to escape it.
            if (checkedPriorityMutationProblem(key, handle) != null)
                return LocalFileBatchOutcome.STALE_REVIEW
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
                if (on && checkedPriorityMutationProblem(key, handle) != null) return false
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
            if (checkedPriorityMutationProblem(key, handle) != null)
                return LocalFileBatchOutcome.STALE_REVIEW
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
            if (wanted && checkedPriorityMutationProblem(key, handle) != null)
                return LocalFileBatchOutcome.STALE_REVIEW
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

    /**
     * Reap completed native removals without guessing a timeout. libtorrent's
     * remove_torrent is non-blocking; the old handle stays valid while native
     * tasks retain it. The handle becoming invalid is the upstream completion
     * boundary, so we may release only that exact retired handle's path claim.
     *
     * A thrown/unknown getter is NOT completion. Existing saved REMOVE_INTENT
     * still independently blocks an unsafe Add through canRegister().
     * Must be called under the engine monitor before any new path admission.
     */
    private fun releaseDrainedNativeRetirement(requestedHash: String) {
        // Only a deliberate exact-same-infohash re-Add may reclaim its old
        // paths. An unrelated torrent is NOT licensed to reuse that old
        // filename merely because the retired engine handle has drained.
        val oldHandle = retiredNativeHandles[requestedHash] ?: return
        val drained = try { !oldHandle.isValid } catch (_: Exception) { false }
        if (!drained) return
        retiredNativeHandles.remove(requestedHash)
        retiredThisSession.remove(requestedHash)
        checkedPayloadReservations.release(requestedHash)
    }

    /**
     * Remove only the exact native identity that the user reviewed. A hash is
     * insufficient authorization: after an earlier Remove another instance
     * may have added the same hash under a different name or save folder.
     * Check against the live handle while holding the engine's mutation lock,
     * then issue a single session-only Remove under that same lock.
     *
     * False also includes unavailable native snapshots. It never retries,
     * writes payload files or claims persistence after an uncertain result.
     */
    @Synchronized
    fun removeKeepingDataIfMatching(
        infoHash: String, expectedName: String, expectedSavePath: String,
        expectedReviewToken: String
    ): Boolean {
        if (expectedName.isBlank() || !expectedSavePath.startsWith("/") ||
            expectedReviewToken.isBlank()) return false
        val key = normal(infoHash)
        val current = try {
            val handle = handles[key] ?: return false
            snapshot(key, handle)
        } catch (_: Exception) { null } ?: return false
        if (current.infoHash != key ||
            current.name != expectedName ||
            current.savePath != expectedSavePath ||
            current.nativeReviewToken != expectedReviewToken) return false
        return removeKeepingData(key)
    }

    /**
     * Reviewed delete-data request. Existing files are valid user-authorized
     * targets, regardless of who created them. No directory-wide deletion and
     * no native mutation before the full per-file + competing-job survey.
     *
     * The returned REQUESTED is a dispatch receipt, NEVER a deletion success.
     * Call payloadReadback() after asynchronous native removal; a pending or
     * failed readback remains visibly incomplete, without blind retry.
     */
    @Synchronized
    fun removeWithDataIfMatching(
        infoHash: String, expectedName: String, expectedSavePath: String,
        expectedReviewToken: String
    ): LocalDeleteDataReceipt {
        if (expectedName.isBlank() || !expectedSavePath.startsWith("/") ||
            expectedReviewToken.isBlank())
            return LocalDeleteDataReceipt(LocalDeleteDataDispatch.REFUSED, reason = "Invalid reviewed identity")
        val key = normal(infoHash)
        // v2-only alert IDs are not yet correlated to a stable exact hash.
        if (key.length != 40)
            return LocalDeleteDataReceipt(LocalDeleteDataDispatch.REFUSED, reason = "Only exact v1 deletion alerts are supported")
        val handle = handles[key] ?: return LocalDeleteDataReceipt(LocalDeleteDataDispatch.NOT_FOUND)
        val current = try { snapshot(key, handle) } catch (_: Exception) { null }
            ?: return LocalDeleteDataReceipt(LocalDeleteDataDispatch.REFUSED, reason = "Native identity unavailable")
        if (current.infoHash != key || current.name != expectedName ||
            current.savePath != expectedSavePath ||
            current.nativeReviewToken != expectedReviewToken)
            return LocalDeleteDataReceipt(LocalDeleteDataDispatch.REFUSED, reason = "Review is stale")
        val storage = try { handle.torrentFile()?.files() } catch (_: Exception) { null }
            ?: return LocalDeleteDataReceipt(LocalDeleteDataDispatch.REFUSED, reason = "Payload metadata unavailable")
        if (!LocalPayloadCollisionSurvey.supportsNativeFileCount(storage.numFiles()))
            return LocalDeleteDataReceipt(LocalDeleteDataDispatch.REFUSED, reason = "Unsupported file count")
        val relatives = try {
            (0 until storage.numFiles()).asSequence().filterNot { storage.padFileAt(it) }
                .map { storage.filePath(it) }.toList()
        } catch (_: Exception) {
            return LocalDeleteDataReceipt(LocalDeleteDataDispatch.REFUSED, reason = "Payload file list unavailable")
        }
        if (relatives.isEmpty())
            return LocalDeleteDataReceipt(LocalDeleteDataDispatch.REFUSED, reason = "No payload files")
        val root = File(expectedSavePath)
        if (LocalPayloadCollisionSurvey.inspect(root, relatives) is LocalPayloadCollisionSurvey.Result.Unknown)
            return LocalDeleteDataReceipt(LocalDeleteDataDispatch.REFUSED, reason = "Unsafe payload path")
        fun absolute(save: String, relative: String) =
            File(save, relative).absoluteFile.toPath().normalize().toString()
                .lowercase(Locale.ROOT)
        val selected = relatives.map { absolute(expectedSavePath, it) }.toSet()
        // Other registered native jobs could reference the same physical files.
        // An unreadable competing inventory cannot be interpreted as no conflict.
        for ((otherKey, otherHandle) in synchronized(handles) { handles.toList() }) {
            if (otherKey == key) continue
            val otherInfo = try { otherHandle.torrentFile()?.files() } catch (_: Exception) { null }
                ?: return LocalDeleteDataReceipt(LocalDeleteDataDispatch.REFUSED, reason = "Other transfer inventory unavailable")
            if (!LocalPayloadCollisionSurvey.supportsNativeFileCount(otherInfo.numFiles()))
                return LocalDeleteDataReceipt(LocalDeleteDataDispatch.REFUSED, reason = "Other transfer inventory unsupported")
            val otherSave = try { otherHandle.savePath() } catch (_: Exception) { null }
                ?: return LocalDeleteDataReceipt(LocalDeleteDataDispatch.REFUSED, reason = "Other transfer destination unknown")
            try {
                for (i in 0 until otherInfo.numFiles()) {
                    if (!otherInfo.padFileAt(i) && absolute(otherSave, otherInfo.filePath(i)) in selected)
                        return LocalDeleteDataReceipt(LocalDeleteDataDispatch.REFUSED, reason = "Payload shared by another transfer")
                }
            } catch (_: Exception) {
                return LocalDeleteDataReceipt(LocalDeleteDataDispatch.REFUSED, reason = "Other transfer file list unavailable")
            }
        }
        val targets = relatives.map { File(root, it) }
        val completion = AtomicReference<Boolean?>(null)
        if (pendingNativeDeletions.putIfAbsent(key, completion) != null)
            return LocalDeleteDataReceipt(LocalDeleteDataDispatch.REFUSED,
                reason = "A prior deletion is still unresolved")
        val dispatch = retireNativeTransfer(key, handle, deletePayload = true)
        if (dispatch == LocalDeleteDataDispatch.REFUSED || dispatch == LocalDeleteDataDispatch.NOT_FOUND)
            pendingNativeDeletions.remove(key, completion)
        return LocalDeleteDataReceipt(dispatch, targets, nativeCompletion = completion)
    }

    /** Remove from the session only. Payload files are never deleted here. */
    @Synchronized
    fun removeKeepingData(infoHash: String): Boolean {
        val key = normal(infoHash)
        val handle = handles[key] ?: return false
        return retireNativeTransfer(key, handle, deletePayload = false) ==
            LocalDeleteDataDispatch.REQUESTED
    }

    /**
     * A single mutation point for both confirmed Remove choices. Native delete
     * uses libtorrent's own per-torrent file mapping, never a custom unlinker.
     * The filesystem operation is asynchronous, so dispatch != completion.
     */
    private fun retireNativeTransfer(
        key: String, handle: TorrentHandle, deletePayload: Boolean
    ): LocalDeleteDataDispatch {
        val store = resumeEvents
        if (config.stateDirectory != null && store == null)
            return LocalDeleteDataDispatch.REFUSED
        try { store?.removeIntent(key) } catch (_: Exception) {
            return LocalDeleteDataDispatch.REFUSED
        }
        try {
            if (deletePayload) session.remove(handle, session_handle.delete_files)
            else session.remove(handle)
        } catch (_: Exception) { return LocalDeleteDataDispatch.UNCERTAIN }
        retiredThisSession += key
        retiredNativeHandles[key] = handle
        synchronized(handles) { handles.remove(key) }
        nativeReviewTokens.remove(key)
        awaitingMetadata.remove(key)
        startWhenMetadataArrives.remove(key)
        magnetCollisionProblems.remove(key)
        checkedFirstStarts.remove(key)
        restoredNativeJobs.remove(key)
        checkedStartProblems.remove(key)
        storageFileErrors.remove(key)
        pendingFlushes.cancel(key, handle)
        pendingSaves.cancel(key, handle)
        return try {
            store?.removeCommit(key)
            LocalDeleteDataDispatch.REQUESTED
        } catch (_: Exception) {
            // Native removal may already have removed files. Reconcile by
            // exact paths and the durable intent; never blindly resubmit.
            LocalDeleteDataDispatch.UNCERTAIN
        }
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
     * torrent. Null means this is NOT a complete proof of the local device inventory.
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
     * Read-only diagnostic: libtorrent's own have-bit for one piece of the exact
     * job (true only after its hash was verified). Null when the job, its metadata
     * or the piece index is unknown. Never a substitute for independent byte readback.
     */
    fun hasVerifiedPiece(infoHash: String, piece: Int): Boolean? = try {
        val handle = synchronized(handles) { handles[normal(infoHash)] }
            ?.takeIf { started && it.isValid } ?: return null
        val pieces = handle.torrentFile()?.numPieces() ?: return null
        if (piece !in 0 until pieces) null else handle.havePiece(piece)
    } catch (_: Exception) { null }

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
                // A native error (including a recorded failed write) means the
                // verified-piece bitmap may not describe the bytes on disk.
                status?.metadataKnown == true && status.progressPpm == 1_000_000 &&
                    status.state in setOf(LocalTransferState.SEEDING, LocalTransferState.FINISHED) &&
                    status.error == null
            },
            invokeNative = { it.flushCache() }
        )
    }

    /**
     * Persist the exact current job after a user-confirmed Stop, before the UI
     * offers crash/relaunch recovery as settled. Unlike the periodic bulk save,
     * this never waits for unrelated torrents and cannot acknowledge a replaced
     * native handle. A callback counts only after the resume-store commit succeeds.
     *
     * This must run off the Android UI thread. Failure or timeout is UNKNOWN
     * durability, never permission to retry Start/Stop or delete any payload.
     */
    fun saveResumeDataFor(infoHash: String, timeoutMs: Long = 3_000): Boolean {
        require(timeoutMs in 0L..30_000L)
        val key = normal(infoHash)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        fun remaining() = (deadline - System.nanoTime()).coerceAtLeast(0L)
        val (handle, prior) = synchronized(this) {
            if (config.stateDirectory == null || !started) return false
            synchronized(handles) {
                val exact = handles[key]?.takeIf { runCatching { it.isValid }.getOrDefault(false) }
                    ?: return false
                exact to pendingSaves.pendingFor(key, exact)
            }
        }
        // A metadata-triggered save may have been requested BEFORE the Stop.
        // Joining it is not evidence of the stopped state; wait until it
        // settles, then ask libtorrent for a *new* post-Stop snapshot.
        if (prior != null) {
            try {
                if (!prior.awaitSettled(remaining())) return false
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        // Register under the Remove / Close monitor, but NEVER await an
        // asynchronous JNI alert while holding the engine or handles lock.
        val request = synchronized(this) {
            if (config.stateDirectory == null || !started) return false
            synchronized(handles) {
                if (handles[key] != handle ||
                    !runCatching { handle.isValid }.getOrDefault(false)) return false
                pendingSaves.acquire(key, handle) {
                    // libtorrent's documented cache-flush flag prevents a
                    // post-Stop fast-resume snapshot from racing disk writes
                    // and retaining inconsistent payload modification times.
                    handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT.or_(
                        TorrentHandle.FLUSH_DISK_CACHE))
                }
            }
        }
        return try {
            request.awaitConfirmed(remaining())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
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
        completionResaveWanted.clear()
        pendingFlushes.cancelAll()
        storageFileErrors.clear()
        handles.clear()
        awaitingMetadata.clear()
        startWhenMetadataArrives.clear()
        magnetCollisionProblems.clear()
        checkedFirstStarts.clear()
        restoredNativeJobs.clear()
        checkedStartProblems.clear()
        session.stop()
        resumeEvents?.close()
        resumeEvents = null
        retiredThisSession.clear()
        retiredNativeHandles.clear()
        nativeReviewTokens.clear()
        // The native session has stopped before its in-process path claims
        // are discarded. This does not grant ownership to any restored job.
        checkedPayloadReservations.clear()
        started = false
    }

    /** Restore verified current snapshots, including migrated pre-upgrade state. */
    private fun restore(store: LocalResumeStore) {
        val problems = ArrayList<String>(store.problems())
        for ((key, bytes) in store.restorable()) {
            val params = try {
                val error = error_code()
                val raw = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(bytes), error)
                if (raw == null || error.value() != 0) null else AddTorrentParams(raw)
            } catch (_: Exception) { null }
            if (params == null) { problems += store.savedSourceName(key); continue }
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
            if (restored == null) { problems += store.savedSourceName(key); continue }
            nativeReviewTokens[key] = mintReviewToken()
            handles[key] = restored
            restoredNativeJobs.add(key)
            // Reserve before subsequent fresh checked Adds register native
            // handles. A failed reservation leaves this restored job stopped
            // and visible, with a refusal until the conflict is resolved.
            reserveRestoredJobPaths(key, restored)?.let {
                checkedStartProblems[key] = "Restored path admission: $it"
            }
        }
        restoreProblems = problems
    }

    /** Append through an exclusive original descriptor; never replace a known path. */
    private fun writeResume(key: String, bytes: ByteArray) {
        if (config.stateDirectory == null) return
        checkNotNull(resumeEvents) { "Resume store is not initialized" }.save(key, bytes)
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
            error = checkedStartProblems[hash] ?: magnetCollisionProblems[hash] ?: error
                ?: storageFileErrors[hash]?.takeIf { it.first == handle }?.second,
            trackers = trackerSummary(handle),
            downloadPayloadRateBytesPerSecond = status.downloadPayloadRate().coerceAtLeast(0),
            uploadPayloadRateBytesPerSecond = status.uploadPayloadRate().coerceAtLeast(0),
            addedAtEpochMillis = status.addedTime().takeIf { it > 0 },
            nativeReviewToken = nativeReviewTokens[hash].orEmpty(),
            stopsAfterCheck = !paused &&
                status.flags().and_(org.libtorrent4j.TorrentFlags.STOP_WHEN_READY).non_zero()
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
