package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.model.RemoteBackendKind
import java.io.IOException

/**
 * Bridges the established [RTorrentRemote] adapter onto [TorrentClient]
 * WITHOUT changing its behaviour: each mutator delegates to the existing
 * exact-hash, readback-checked rTorrent operation and maps its own outcome
 * onto the port's contract (refusal = nothing written; uncertain = a write
 * may have been delivered). The shared [ClientOperations] then adds
 * its own independent exact-hash readback on top.
 *
 * rTorrent stores three file priority levels (off / normal / high). Remove +
 * data is offered only when this exact account's ruTorrent erase capability
 * is actionable; plain XML-RPC session erase never deletes payload files.
 */
internal class RTorrentClientAdapter(
    private val remote: RTorrentRemote
) : TorrentClient {
    override val kind = TorrentClientKind.RTORRENT

    override suspend fun session(): ClientSessionInfo {
        val version = remote.version()
        val default = runCatching { remote.defaultDirectory() }.getOrNull()?.takeIf { it.startsWith("/") }
        val deleteData = runCatching { remote.discoverDeleteDataCapability() }.getOrNull()?.actionable == true
        val caps = buildSet {
            addAll(listOf(ClientCapability.ADD_TORRENT_FILE, ClientCapability.ADD_MAGNET,
                ClientCapability.ADD_STOPPED, ClientCapability.ADD_TO_DIRECTORY,
                ClientCapability.START_STOP, ClientCapability.FILE_PRIORITIES,
                ClientCapability.REMOVE, ClientCapability.RECHECK))
            if (deleteData) add(ClientCapability.REMOVE_WITH_DATA)
            add(ClientCapability.EXPORT_JOB)
        }
        return ClientSessionInfo(kind, version, default, caps,
            setOf(ClientFilePriority.SKIP, ClientFilePriority.NORMAL, ClientFilePriority.HIGH))
    }

    override suspend fun transferSource(hash: String): ClientAddSource? {
        val exact = ClientHashes.requireHash(hash)
        runCatching { remote.originalMetainfoForSnapshot(exact) }.getOrNull()?.let { bytes ->
            val file = runCatching { ClientAddSource.TorrentFile(bytes) }.getOrNull()
            if (file?.infoHash == exact) return file
        }
        val row = torrent(exact) ?: return null
        return when (val trackers = runCatching { remote.readPublicTrackers(exact) }.getOrNull()) {
            is RTorrentRemote.PublicTrackers.Urls -> ClientMagnets.build(exact, row.name, trackers.urls)
            else -> null // private or unreadable: a bare magnet would not be faithful
        }
    }

    override suspend fun torrents(): List<ClientTorrent> = remote.listTransfers("main").map(::torrentOf)

    override suspend fun torrent(hash: String): ClientTorrent? {
        val exact = ClientHashes.requireHash(hash)
        val rows = remote.listTransfers("main").filter { it.hash.equals(exact, ignoreCase = true) }
        if (rows.size > 1) throw IOException("Ambiguous rTorrent identity")
        val row = rows.singleOrNull() ?: return null
        // rTorrent reports a multi-file job's directory as <parent>/<name>.
        // Report the Add parent only with an exact d.is_multi_file = 1 and a
        // matching final segment (the established Add readback rule).
        val multi = (remote.call("d.is_multi_file", listOf(row.hash)) as? Long)
            ?: throw IOException("Invalid rTorrent multi-file flag")
        if (multi !in 0L..1L) throw IOException("Invalid rTorrent multi-file flag")
        val parent = if (multi == 1L && row.name.isNotBlank() && !row.name.contains('/') &&
            row.directory.endsWith("/" + row.name))
            row.directory.removeSuffix("/" + row.name).ifEmpty { "/" } else row.directory
        return torrentOf(row).copy(downloadDirectory = parent.takeIf { it.startsWith("/") })
    }

    override suspend fun files(hash: String): List<ClientFile> =
        remote.listFiles(ClientHashes.requireHash(hash)).mapIndexed { index, file ->
            ClientFile(index, file.path, file.sizeBytes, null, when (file.priority) {
                0 -> ClientFilePriority.SKIP
                2 -> ClientFilePriority.HIGH
                else -> ClientFilePriority.NORMAL
            })
        }

    override suspend fun add(request: ClientAddRequest) {
        val directory = request.directory
        val observedDefault = if (directory == null) remote.defaultDirectory().orEmpty() else ""
        val result: Any = when (val source = request.source) {
            is ClientAddSource.Magnet -> {
                val plan = if (directory == null) StoppedRemoteAddPlan.fromMagnetAtServerDefault(source.uri, observedDefault)
                    else StoppedRemoteAddPlan.fromMagnet(source.uri, directory)
                if (request.startNow) remote.loadStarted(plan) else remote.loadStopped(plan)
            }
            is ClientAddSource.TorrentFile -> if (request.startNow)
                remote.loadStartedTorrentBytes(source.bytes, directory ?: observedDefault, directory == null)
            else remote.loadStoppedTorrentBytes(source.bytes, directory ?: observedDefault, directory == null)
        }
        val outcome = (result as? RemoteAddAndStart.AddedStopped)?.result ?: result
        when (outcome) {
            is RemoteAddOutcome.AlreadyPresent -> throw ClientRefusedException("Already in this client")
            is RemoteAddOutcome.Uncertain -> throw ClientWriteUncertainException("rTorrent Add reply lost")
            is RemoteAddAndStart.StartNotVerified ->
                throw ClientWriteUncertainException("rTorrent registered the Add; Start was not confirmed")
            else -> Unit // VerifiedStopped, PendingVerification, Started: shared readback decides
        }
    }

    override suspend fun setStarted(hash: String, started: Boolean) {
        when (remote.setStarted(ClientHashes.requireHash(hash), started)) {
            is LifecycleOutcome.Uncertain -> throw ClientWriteUncertainException("rTorrent lifecycle reply lost")
            else -> Unit
        }
    }

    override suspend fun remove(hash: String, deleteData: Boolean) {
        if (!deleteData) return removeJob(hash) {}
        val exact = ClientHashes.requireHash(hash)
        val row = torrentRow(exact)
        when (val outcome = remote.removeWithDataViaRuTorrent(exact, row.name, row.directory)) {
            is RemoteDeleteDataOutcome.Unsupported -> throw ClientRefusedException("Remove + data is not available here")
            is RemoteDeleteDataOutcome.Refused -> throw ClientRefusedException("rTorrent refused Remove + data (${outcome.reason})")
            is RemoteDeleteDataOutcome.Uncertain, is RemoteDeleteDataOutcome.Partial ->
                throw ClientWriteUncertainException("rTorrent Remove + data not confirmed")
            is RemoteDeleteDataOutcome.QueuedForCleanup ->
                throw ClientPostWritePendingException(
                    "ruTorrent queued cleanup; downloaded-file deletion is not independently verified")
        }
    }

    override suspend fun removeJob(hash: String, beforeWrite: () -> Unit) {
        val exact = ClientHashes.requireHash(hash)
        val row = torrentRow(exact)
        when (remote.eraseFromSession(exact, row.name, row.directory, beforeWrite)) {
            is SessionEraseOutcome.AlreadyAbsent -> throw ClientRefusedException("Transfer is not in this client")
            is SessionEraseOutcome.Uncertain -> throw ClientWriteUncertainException("rTorrent erase reply lost")
            else -> Unit
        }
    }

    override suspend fun setFilePriorities(hash: String, priorities: Map<Int, ClientFilePriority>) {
        val exact = ClientHashes.requireHash(hash)
        val before = remote.listFiles(exact)
        var sent = 0
        for ((index, wanted) in priorities.toSortedMap()) {
            val file = before.getOrNull(index) ?: throw ClientRefusedException("File list changed; refresh first")
            val desired = when (wanted) {
                ClientFilePriority.SKIP -> 0
                ClientFilePriority.HIGH -> 2
                else -> 1
            }
            if (file.priority == desired) continue
            val outcome = try {
                remote.setFilePriority(exact, index, file.path, file.sizeBytes, file.priority, desired)
            } catch (refused: IllegalArgumentException) {
                if (sent > 0) throw ClientWriteUncertainException("Some file priorities may have changed")
                throw ClientRefusedException(refused.message ?: "rTorrent refused the priority change")
            }
            if (outcome is FilePriorityOutcome.Uncertain) throw ClientWriteUncertainException("rTorrent priority reply lost")
            sent++
        }
    }

    override suspend fun setLocation(hash: String, directory: String, moveData: Boolean) {
        throw ClientRefusedException("Changing an rTorrent transfer's folder is not supported here")
    }

    override suspend fun reannounce(hash: String) {
        val exact = ClientHashes.requireHash(hash)
        torrentRow(exact)
        try { remote.call("d.tracker_announce", listOf(exact)) } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) { throw ClientWriteUncertainException("rTorrent announce reply lost") }
    }

    override suspend fun recheck(hash: String) {
        val exact = ClientHashes.requireHash(hash)
        val row = torrentRow(exact)
        when (remote.requestHashRecheck(exact, row.name, row.directory)) {
            is RemoteHashRecheckOutcome.Uncertain -> throw ClientWriteUncertainException("rTorrent recheck reply lost")
            else -> Unit
        }
    }

    private suspend fun torrentRow(hash: String): RemoteTransfer =
        remote.listTransfers("main").singleOrNull { it.hash.equals(hash, ignoreCase = true) }
            ?: throw ClientRefusedException("Transfer is not in this client")

    private fun torrentOf(row: RemoteTransfer): ClientTorrent {
        val complete = row.metadataPending != true && row.sizeBytes > 0 && row.completedBytes >= row.sizeBytes
        return ClientTorrent(
            hash = row.hash.lowercase(),
            name = row.name.ifBlank { row.hash.lowercase() },
            runState = when {
                !row.started -> ClientRunState.STOPPED
                row.metadataPending == true -> ClientRunState.METADATA
                complete -> ClientRunState.SEEDING
                else -> ClientRunState.DOWNLOADING
            },
            totalBytes = row.sizeBytes.takeIf { it > 0 && row.metadataPending != true },
            completedBytes = row.completedBytes.takeIf { it >= 0 && row.metadataPending != true },
            downloadBytesPerSecond = row.downBytesPerSecond,
            uploadBytesPerSecond = row.upBytesPerSecond,
            downloadDirectory = row.directory.takeIf { it.startsWith("/") }
        )
    }
}
