package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalDeleteDataDispatch
import io.github.gamesbyian.chunks.engine.LocalFileBatchOutcome
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The bundled Local engine (Arvid libtorrent via libtorrent4j) on the same
 * [RemoteTorrentClient] port, so Local takes part in the shared operation,
 * job Copy/Move and Smart Routing workflows. Every mutator delegates to the
 * engine's existing exact-identity, readback-checked command and maps its
 * outcome (REFUSED / NOT_FOUND = nothing written; UNCERTAIN = may have been
 * applied). Removal carries the engine's own name/save-path/review-token
 * guards. In-process: there is no lost network ACK, only engine uncertainty.
 *
 * Folder authority stays with the caller: [allowedRoots] lists the verified
 * Places this adapter may add into; anything else is refused before writing.
 */
internal class LocalClientAdapter(
    private val engine: LocalTorrentEngine,
    private val defaultDirectory: File?,
    private val allowedRoots: List<File>
) : RemoteTorrentClient {
    /** Not a remote protocol; Local is identified by its environment ID. */
    override val kind: RemoteBackendKind? = null

    override suspend fun session(): ClientSessionInfo = io {
        ClientSessionInfo(kind, "local libtorrent", defaultDirectory?.absolutePath,
            setOf(ClientCapability.ADD_TORRENT_FILE, ClientCapability.ADD_MAGNET,
                ClientCapability.ADD_STOPPED, ClientCapability.ADD_TO_DIRECTORY,
                ClientCapability.START_STOP, ClientCapability.FILE_PRIORITIES,
                ClientCapability.REMOVE, ClientCapability.REMOVE_WITH_DATA,
                ClientCapability.RECHECK, ClientCapability.EXPORT_JOB))
    }

    override suspend fun torrents(): List<ClientTorrent> = io { engine.list().map(::torrentOf) }

    override suspend fun torrent(hash: String): ClientTorrent? = io {
        engine.status(ClientHashes.requireHash(hash))?.let(::torrentOf)
    }

    override suspend fun files(hash: String): List<ClientFile> = io {
        val files = engine.files(ClientHashes.requireHash(hash)) ?: throw IOException("Local file list unavailable")
        files.map { f ->
            ClientFile(f.index, f.path, f.sizeBytes, f.doneBytes, when {
                !f.wanted || f.priority == 0 -> ClientFilePriority.SKIP
                f.priority in 1..3 -> ClientFilePriority.LOW
                f.priority in 5..7 -> ClientFilePriority.HIGH
                else -> ClientFilePriority.NORMAL
            })
        }
    }

    override suspend fun add(request: ClientAddRequest) = io {
        val dir = (request.directory?.let(::File) ?: defaultDirectory)
            ?: throw ClientRefusedException("Choose a verified local folder")
        val canonical = dir.canonicalFile
        if (allowedRoots.none { root -> canonical.toPath().startsWith(root.canonicalFile.toPath()) })
            throw ClientRefusedException("That folder is not a verified local Place")
        if (!canonical.isDirectory && !canonical.mkdirs()) throw ClientRefusedException("Could not use that local folder")
        val result = when (val source = request.source) {
            is ClientAddSource.TorrentFile -> engine.addStoppedChecked(source.bytes, canonical)
            is ClientAddSource.Magnet -> engine.addMagnet(source.uri, canonical)
        }
        if (result is LocalAddResult.Refused) throw ClientRefusedException(result.reason)
        if (request.startNow) {
            // A stopped .torrent Add over existing data first hash-checks it
            // (stop_when_ready). Let that settle (bounded), then send ONE
            // Start; a refusal after a verified registration is uncertain.
            val hash = request.source.infoHash
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                val s = engine.status(hash) ?: break
                if (s.state != LocalTransferState.CHECKING && !s.stopsAfterCheck) break
                Thread.sleep(200)
            }
            val started = engine.resume(hash)
            if (started.outcome != LocalCommandOutcome.VERIFIED)
                throw ClientWriteUncertainException("Local job added; Start not confirmed")
        }
    }

    override suspend fun setStarted(hash: String, started: Boolean) = io {
        val exact = ClientHashes.requireHash(hash)
        val result = if (started) engine.resume(exact) else engine.pause(exact)
        when (result.outcome) {
            LocalCommandOutcome.VERIFIED -> Unit
            LocalCommandOutcome.REFUSED -> throw ClientRefusedException(
                engine.status(exact)?.error?.take(200) ?: "The local engine refused")
            LocalCommandOutcome.NOT_FOUND -> throw ClientRefusedException("Transfer is not on this device")
            LocalCommandOutcome.UNCERTAIN -> throw ClientWriteUncertainException("Local lifecycle not confirmed")
        }
    }

    override suspend fun remove(hash: String, deleteData: Boolean) = io {
        val exact = ClientHashes.requireHash(hash)
        val status = engine.status(exact) ?: throw ClientRefusedException("Transfer is not on this device")
        if (deleteData) {
            when (engine.removeWithDataIfMatching(exact, status.name, status.savePath, status.nativeReviewToken).dispatch) {
                LocalDeleteDataDispatch.REQUESTED -> Unit
                LocalDeleteDataDispatch.REFUSED, LocalDeleteDataDispatch.NOT_FOUND ->
                    throw ClientRefusedException("The local engine refused Remove + data")
                LocalDeleteDataDispatch.UNCERTAIN -> throw ClientWriteUncertainException("Local Remove + data not confirmed")
            }
        } else if (!engine.removeKeepingDataIfMatching(exact, status.name, status.savePath, status.nativeReviewToken)) {
            throw ClientRefusedException("The local engine refused the removal")
        }
    }

    override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) = io {
        val exact = ClientHashes.requireHash(hash)
        var sent = 0
        for ((index, wanted) in priorities.toSortedMap()) {
            val current = engine.files(exact)?.firstOrNull { it.index == index }
                ?: throw if (sent == 0) ClientRefusedException("File list changed; refresh first")
                    else ClientWriteUncertainException("Some file priorities may have changed")
            if (wanted == ClientFilePriority.SKIP) {
                when (engine.setFileWanted(exact, index, false)) {
                    LocalCommandOutcome.VERIFIED -> Unit
                    LocalCommandOutcome.UNCERTAIN -> throw ClientWriteUncertainException("Local priority not confirmed")
                    else -> throw if (sent == 0) ClientRefusedException("The local engine refused")
                        else ClientWriteUncertainException("Some file priorities may have changed")
                }
            } else {
                val native = when (wanted) { ClientFilePriority.LOW -> 1; ClientFilePriority.HIGH -> 7; else -> 4 }
                var reviewed = current
                if (!current.wanted || current.priority == 0) {
                    // Un-skipping restores "wanted" (native Normal) first.
                    when (engine.setFileWanted(exact, index, true)) {
                        LocalCommandOutcome.VERIFIED -> Unit
                        LocalCommandOutcome.UNCERTAIN -> throw ClientWriteUncertainException("Local priority not confirmed")
                        else -> throw if (sent == 0) ClientRefusedException("The local engine refused")
                            else ClientWriteUncertainException("Some file priorities may have changed")
                    }
                    sent++
                    reviewed = engine.files(exact)?.firstOrNull { it.index == index }
                        ?: throw ClientWriteUncertainException("Some file priorities may have changed")
                    if (reviewed.priority == native) continue
                }
                when (engine.setFilePriority(exact, reviewed, native)) {
                    LocalFileBatchOutcome.VERIFIED, LocalFileBatchOutcome.UNCHANGED -> Unit
                    LocalFileBatchOutcome.UNCERTAIN -> throw ClientWriteUncertainException("Local priority not confirmed")
                    else -> throw if (sent == 0) ClientRefusedException("File list changed; refresh first")
                        else ClientWriteUncertainException("Some file priorities may have changed")
                }
            }
            sent++
        }
    }

    override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) =
        throw ClientRefusedException("Use the local storage move for device folders")

    override suspend fun recheck(hash: String) = io {
        when (engine.recheckStopped(ClientHashes.requireHash(hash)).outcome) {
            LocalCommandOutcome.VERIFIED -> Unit
            LocalCommandOutcome.UNCERTAIN -> throw ClientWriteUncertainException("Local recheck not confirmed")
            else -> throw ClientRefusedException("Stop the transfer before rechecking it")
        }
    }

    override suspend fun transferSource(hash: String): ClientAddSource? = io {
        val exact = ClientHashes.requireHash(hash)
        engine.exportTorrentFile(exact)?.let { bytes ->
            runCatching { ClientAddSource.TorrentFile(bytes) }.getOrNull()?.takeIf { it.infoHash == exact }
        }
    }

    private fun torrentOf(s: LocalTransferStatus) = ClientTorrent(
        hash = s.infoHash,
        name = s.name.ifBlank { s.infoHash },
        runState = when {
            s.error != null && !s.stopped -> ClientRunState.ERROR
            s.stopped || s.stopsAfterCheck -> if (s.state == LocalTransferState.CHECKING && !s.stopped)
                ClientRunState.CHECKING else ClientRunState.STOPPED
            s.state == LocalTransferState.CHECKING -> ClientRunState.CHECKING
            s.state == LocalTransferState.DOWNLOADING_METADATA -> ClientRunState.METADATA
            s.state == LocalTransferState.DOWNLOADING -> ClientRunState.DOWNLOADING
            s.state == LocalTransferState.SEEDING || s.state == LocalTransferState.FINISHED -> ClientRunState.SEEDING
            else -> ClientRunState.UNKNOWN
        },
        totalBytes = s.wantedBytes.takeIf { s.metadataKnown && it > 0 },
        completedBytes = s.doneBytes.takeIf { s.metadataKnown && it >= 0 },
        downloadBytesPerSecond = s.downloadPayloadRateBytesPerSecond.toLong(),
        uploadBytesPerSecond = s.uploadPayloadRateBytesPerSecond.toLong(),
        downloadDirectory = s.savePath.takeIf { it.startsWith("/") }
    )

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
