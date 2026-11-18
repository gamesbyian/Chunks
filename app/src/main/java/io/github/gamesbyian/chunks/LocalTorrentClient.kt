package io.github.gamesbyian.chunks

import android.content.Context
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalDeleteDataDispatch
import io.github.gamesbyian.chunks.engine.LocalDeleteDataReadback
import io.github.gamesbyian.chunks.engine.LocalFileBatchOutcome
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientAddSource
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientFile
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientHashes
import io.github.gamesbyian.chunks.rpc.ClientPostWritePendingException
import io.github.gamesbyian.chunks.rpc.ClientRefusedException
import io.github.gamesbyian.chunks.rpc.ClientRunState
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.ClientTorrent
import io.github.gamesbyian.chunks.rpc.ClientWriteUncertainException
import io.github.gamesbyian.chunks.rpc.TorrentClient
import io.github.gamesbyian.chunks.rpc.TorrentClientKind
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The device's own libtorrent engine on the ONE shared [TorrentClient] port,
 * so Local and every remote engine use the same application workflow
 * ([io.github.gamesbyian.chunks.rpc.ClientOperations]: capability gate →
 * exact precondition → one write → exact readback → Verified / Pending /
 * Uncertain / Refused) and the same job-only Copy/Move receipt.
 *
 * Every write delegates to an existing guarded [LocalTorrentEngine] method
 * (checked Add path survey, first-Start fence, exact native review token
 * for Remove, independent delete-data readback) — no second native code path.
 * Each write holds the process-wide [LocalMutationFence], exactly like the
 * device screen and Combined Details, so a stale list on another surface can
 * never authorize a competing change. Capability differences (no folder
 * relocation; Recheck only while stopped) are reported, never emulated.
 */
internal class LocalTorrentClient(
    /** The running engine, or null. Reads never start networking. */
    private val engine: () -> LocalTorrentEngine?,
    /** Starts the engine for an explicit Add (production: LocalEngineHolder.get). */
    private val engineForAdd: () -> LocalTorrentEngine = { engine() ?: throw ClientRefusedException(ENGINE_UNAVAILABLE) },
    private val places: LocalClientPlaces,
    private val fence: LocalScreenObservationRevision = LocalMutationFence.revision,
    /** Called after a verified payload Start (production: keep the foreground service up). */
    private val onPayloadStarted: () -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val deleteReadbackTimeoutMs: Long = 15_000L
) : TorrentClient {
    override val kind = TorrentClientKind.LOCAL

    override suspend fun session(): ClientSessionInfo = withContext(io) {
        ClientSessionInfo(
            kind = kind,
            version = "libtorrent (libtorrent4j)",
            defaultDownloadDirectory = places.defaultDirectory(),
            capabilities = CAPABILITIES,
            filePriorities = ClientFilePriority.entries.toSet()
        )
    }

    override suspend fun torrents(): List<ClientTorrent> = withContext(io) {
        val running = engine() ?: throw IOException(ENGINE_UNAVAILABLE)
        // A dropped native handle must never look like an absent transfer.
        val identities = running.completeIdentityInventory()
            ?: throw IOException("The local inventory is not complete right now")
        val rows = running.list()
        if (rows.map { it.infoHash }.toSet() != identities.toSet())
            throw IOException("The local inventory changed during the read")
        rows.map(::torrentOf)
    }

    override suspend fun torrent(hash: String): ClientTorrent? = withContext(io) {
        val exact = ClientHashes.requireHash(hash)
        val running = engine() ?: throw IOException(ENGINE_UNAVAILABLE)
        running.status(exact)?.let(::torrentOf)
    }

    override suspend fun files(hash: String): List<ClientFile> = withContext(io) {
        val exact = ClientHashes.requireHash(hash)
        val running = engine() ?: throw IOException(ENGINE_UNAVAILABLE)
        val status = running.status(exact) ?: throw IOException("Transfer is not in this client")
        if (!status.metadataKnown) return@withContext emptyList()
        val files = running.files(exact) ?: throw IOException("This transfer's files could not be read")
        files.map(::fileOf)
    }

    override suspend fun add(request: ClientAddRequest) {
        val hash = request.source.infoHash
        val folder = withContext(io) { places.resolve(request.directory) }
            ?: throw ClientRefusedException(
                if (request.directory == null) "No verified download location on this device"
                else "That folder is not a verified download location on this device")
        write(forAdd = true) { running ->
            val added = when (val source = request.source) {
                is ClientAddSource.TorrentFile -> running.addStoppedChecked(source.bytes, folder)
                is ClientAddSource.Magnet ->
                    running.addMagnet(source.uri, folder, autoStartAfterMetadata = request.startNow)
            }
            if (added is LocalAddResult.Refused) {
                // A Refused after the native write boundary (read-back or
                // stop-enforcement failure) can leave a registered handle.
                if (running.status(hash) == null) throw ClientRefusedException(added.reason)
                throw ClientWriteUncertainException("The local engine did not confirm this Add")
            }
            if (request.startNow && added is LocalAddResult.AddedStopped) {
                when (running.resume(hash).outcome) {
                    LocalCommandOutcome.VERIFIED -> notifyStarted()
                    LocalCommandOutcome.UNCERTAIN ->
                        throw ClientWriteUncertainException("Added; Start was not confirmed")
                    // Registered stopped (e.g. the destination holds unreviewed
                    // files). The shared readback reports Start as not observed.
                    LocalCommandOutcome.REFUSED, LocalCommandOutcome.NOT_FOUND -> Unit
                }
            } else if (request.startNow) notifyStarted() // magnet: Start after metadata
            else if (added is LocalAddResult.AddedStopped)
                // The device screen's "Stopped after verification": existing
                // files at the exact torrent paths are hash-checked and the job
                // pauses itself before any peer traffic (no-op when none exist).
                runCatching { running.beginStoppedVerification(hash) }
        }
    }

    override suspend fun setStarted(hash: String, started: Boolean) {
        val exact = ClientHashes.requireHash(hash)
        write { running ->
            val result = if (started) running.resume(exact) else running.pause(exact)
            when (result.outcome) {
                LocalCommandOutcome.VERIFIED -> if (started) notifyStarted()
                    // Best-effort durable Stop checkpoint; a failed save never
                    // turns a verified Stop into an unknown command.
                    else runCatching { running.saveResumeDataFor(exact) }
                LocalCommandOutcome.NOT_FOUND -> throw ClientRefusedException("Transfer is not in this client")
                LocalCommandOutcome.REFUSED -> throw ClientRefusedException(
                    if (started) "The local engine refused Start; review this transfer's folder first"
                    else "The local engine refused Stop")
                LocalCommandOutcome.UNCERTAIN -> throw ClientWriteUncertainException("Local lifecycle change not confirmed")
            }
        }
    }

    override suspend fun remove(hash: String, deleteData: Boolean) {
        val exact = ClientHashes.requireHash(hash)
        write { running ->
            val reviewed = running.status(exact) ?: throw ClientRefusedException("Transfer is not in this client")
            if (reviewed.nativeReviewToken.isBlank() || !reviewed.savePath.startsWith("/"))
                throw ClientRefusedException("This transfer's identity cannot be verified right now")
            if (!deleteData) {
                if (!running.removeKeepingDataIfMatching(
                        exact, reviewed.name, reviewed.savePath, reviewed.nativeReviewToken)) {
                    if (running.status(exact)?.nativeReviewToken != reviewed.nativeReviewToken)
                        throw ClientWriteUncertainException("Local Remove not confirmed")
                    throw ClientRefusedException("The local engine refused Remove")
                }
                return@write
            }
            if (!reviewed.metadataKnown)
                throw ClientRefusedException("Remove + data needs this transfer's file list")
            val receipt = running.removeWithDataIfMatching(
                exact, reviewed.name, reviewed.savePath, reviewed.nativeReviewToken)
            when (receipt.dispatch) {
                LocalDeleteDataDispatch.REFUSED ->
                    throw ClientRefusedException("Remove + data refused: " + (receipt.reason ?: "review is stale"))
                LocalDeleteDataDispatch.NOT_FOUND -> throw ClientRefusedException("Transfer is not in this client")
                LocalDeleteDataDispatch.REQUESTED, LocalDeleteDataDispatch.UNCERTAIN -> Unit
            }
            when (awaitDeleteReadback(receipt, deleteReadbackTimeoutMs)) {
                LocalDeleteDataReadback.ABSENT -> Unit
                LocalDeleteDataReadback.UNAVAILABLE -> throw ClientPostWritePendingException(
                    "Deletion of downloaded files is not yet independently verified")
                LocalDeleteDataReadback.STILL_PRESENT, LocalDeleteDataReadback.NATIVE_FAILED ->
                    throw ClientWriteUncertainException("Native deletion did not finish; some files remain")
            }
        }
    }

    /**
     * Skips first (batch), then un-skips (batch), then explicit Low/Normal/
     * High per file, each against the engine's fresh exact file identity.
     * Any failure after the first native write is uncertain, never replayed.
     */
    override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) {
        val exact = ClientHashes.requireHash(hash)
        write { running ->
            var wrote = false
            fun fail(message: String): Nothing =
                if (wrote) throw ClientWriteUncertainException("Some file priorities may have changed")
                else throw ClientRefusedException(message)
            fun fresh(): List<LocalTransferFile> = running.files(exact) ?: fail("This transfer's files are not available")
            fun settle(outcome: LocalFileBatchOutcome) = when (outcome) {
                LocalFileBatchOutcome.VERIFIED -> wrote = true
                LocalFileBatchOutcome.UNCHANGED -> Unit
                LocalFileBatchOutcome.STALE_REVIEW -> fail("File list changed or is not ready; refresh first")
                LocalFileBatchOutcome.NOT_FOUND -> fail("Transfer is not in this client")
                LocalFileBatchOutcome.UNCERTAIN -> throw ClientWriteUncertainException("Local file priority change not confirmed")
            }
            val current = fresh()
            if (!current.map { it.index }.toSet().containsAll(priorities.keys)) fail("File list changed; refresh first")
            val skip = current.filter { priorities[it.index] == ClientFilePriority.SKIP && it.wanted }
            if (skip.isNotEmpty()) settle(running.setFilesWantedBatch(exact, skip, false))
            val unskip = fresh().filter { file ->
                val wanted = priorities[file.index]
                wanted != null && wanted != ClientFilePriority.SKIP && !file.wanted
            }
            if (unskip.isNotEmpty()) settle(running.setFilesWantedBatch(exact, unskip, true))
            for (file in fresh().sortedBy { it.index }) {
                val wanted = priorities[file.index] ?: continue
                if (wanted == ClientFilePriority.SKIP || priorityOf(file) == wanted) continue
                settle(running.setFilePriority(exact, file, nativePriority(wanted)))
            }
        }
    }

    override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) {
        throw ClientRefusedException("Changing a local transfer's folder is not supported here")
    }

    override suspend fun recheck(hash: String) {
        val exact = ClientHashes.requireHash(hash)
        write { running ->
            when (running.recheckStopped(exact).outcome) {
                LocalCommandOutcome.VERIFIED -> Unit
                LocalCommandOutcome.REFUSED -> throw ClientRefusedException("Stop this transfer before rechecking it")
                LocalCommandOutcome.NOT_FOUND -> throw ClientRefusedException("Transfer is not in this client")
                LocalCommandOutcome.UNCERTAIN -> throw ClientWriteUncertainException("Local recheck not confirmed")
            }
        }
    }

    /** The job's own exported metainfo (exact info dictionary). Never logged. */
    override suspend fun transferSource(hash: String): ClientAddSource? = withContext(io) {
        val exact = ClientHashes.requireHash(hash)
        val bytes = engine()?.exportTorrentFile(exact) ?: return@withContext null
        runCatching { ClientAddSource.TorrentFile(bytes) }.getOrNull()?.takeIf { it.infoHash == exact }
    }

    private fun notifyStarted() { runCatching { onPayloadStarted() } }

    /** One fenced native change; the fence settles even on failure, never retried. */
    private suspend fun write(forAdd: Boolean = false, block: suspend (LocalTorrentEngine) -> Unit) =
        withContext(io) {
            val running = if (forAdd) engineForAdd() else engine() ?: throw ClientRefusedException(ENGINE_UNAVAILABLE)
            if (!fence.beginMutation()) throw ClientRefusedException(LOCKED)
            try { block(running) } finally { fence.settledMutation() }
        }

    internal companion object {
        const val ENGINE_UNAVAILABLE = "The local engine is not running"
        const val LOCKED = "Another change to this device's transfers is in progress; refresh first"

        val CAPABILITIES = setOf(
            ClientCapability.ADD_TORRENT_FILE, ClientCapability.ADD_MAGNET,
            ClientCapability.ADD_STOPPED, ClientCapability.ADD_TO_DIRECTORY,
            ClientCapability.START_STOP, ClientCapability.FILE_PRIORITIES,
            ClientCapability.REMOVE, ClientCapability.REMOVE_WITH_DATA,
            ClientCapability.RECHECK, ClientCapability.EXPORT_JOB
        )

        /** libtorrent 0..7 onto the shared four levels (1 Low, 4 Normal, 7 High). */
        fun priorityOf(file: LocalTransferFile): ClientFilePriority = when {
            !file.wanted || file.priority <= 0 -> ClientFilePriority.SKIP
            file.priority <= 3 -> ClientFilePriority.LOW
            file.priority <= 5 -> ClientFilePriority.NORMAL
            else -> ClientFilePriority.HIGH
        }

        fun nativePriority(priority: ClientFilePriority): Int = when (priority) {
            ClientFilePriority.LOW -> 1
            ClientFilePriority.HIGH -> 7
            else -> 4
        }

        fun fileOf(file: LocalTransferFile) =
            ClientFile(file.index, file.path, file.sizeBytes, file.doneBytes, priorityOf(file))

        /**
         * The Local *payload* Start contract: metadata-only (upload mode),
         * stop-when-ready verification and paused jobs are not started.
         */
        fun runStateOf(status: LocalTransferStatus): ClientRunState = when {
            status.error != null -> ClientRunState.ERROR
            status.state == LocalTransferState.CHECKING -> ClientRunState.CHECKING
            else -> when (LocalUniversalAddAdapter.observedPayloadStarted(status)) {
                false -> ClientRunState.STOPPED
                null -> ClientRunState.UNKNOWN
                true -> when (status.state) {
                    LocalTransferState.SEEDING, LocalTransferState.FINISHED -> ClientRunState.SEEDING
                    LocalTransferState.DOWNLOADING -> ClientRunState.DOWNLOADING
                    else -> ClientRunState.UNKNOWN
                }
            }
        }

        fun torrentOf(status: LocalTransferStatus) = ClientTorrent(
            hash = status.infoHash.lowercase(),
            name = status.name.ifBlank { status.infoHash.lowercase() },
            runState = runStateOf(status),
            totalBytes = status.wantedBytes.takeIf { status.metadataKnown && it > 0 },
            completedBytes = status.doneBytes.takeIf { status.metadataKnown && it >= 0 },
            downloadBytesPerSecond = status.downloadPayloadRateBytesPerSecond.toLong(),
            uploadBytesPerSecond = status.uploadPayloadRateBytesPerSecond.toLong(),
            downloadDirectory = status.savePath.takeIf { it.startsWith("/") }
        )

        /** Production wiring: the app's engine holder and verified Places. */
        fun forApp(context: Context): LocalTorrentClient {
            val app = context.applicationContext
            return LocalTorrentClient(
                engine = { LocalEngineHolder.current() },
                engineForAdd = { LocalEngineHolder.get(app) },
                places = LocalClientPlaces.forApp(app),
                onPayloadStarted = { LocalEngineService.start(app) }
            )
        }
    }
}

/**
 * Verified device download locations for the shared port. Only an existing,
 * write-probed folder at or below a verified Place is admissible; a request
 * must name its canonical path so the engine's reported folder compares equal.
 */
internal interface LocalClientPlaces {
    /** The current default Place's path for presentation/Add default; creates nothing. */
    fun defaultDirectory(): String?

    /** Off the main thread, after explicit authorization: the folder to Add into, or null. */
    fun resolve(directory: String?): File?

    companion object {
        fun within(roots: List<File>, directory: String): File? {
            val requested = File(directory)
            val canonical = runCatching { requested.canonicalFile }.getOrNull() ?: return null
            if (canonical.path != requested.path.trimEnd('/').ifEmpty { "/" } || !canonical.isDirectory) return null
            val inside = roots.any { root ->
                val base = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
                canonical == base || canonical.path.startsWith(base.path.trimEnd('/') + "/")
            }
            return canonical.takeIf { inside }
        }

        fun forApp(context: Context): LocalClientPlaces = object : LocalClientPlaces {
            override fun defaultDirectory(): String? =
                runCatching { LocalDownloadPlaces.resolve(context).selected?.root?.absolutePath }.getOrNull()

            override fun resolve(directory: String?): File? {
                val resolution = LocalDownloadPlaces.resolve(context)
                if (directory == null) return resolution.selected?.let(LocalDownloadPlaces::materialize)
                val verified = resolution.choices.filter { it.verified }.mapNotNull {
                    if (it.place == resolution.selected) LocalDownloadPlaces.materialize(it.place) else it.place.root
                }
                return within(verified, directory)?.takeIf { LocalPlaceWriteProbe.verify(it) == null }
            }
        }
    }
}
