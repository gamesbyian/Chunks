package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddIntent
import io.github.gamesbyian.chunks.model.UniversalAddPlace
import io.github.gamesbyian.chunks.model.UniversalAddSource
import io.github.gamesbyian.chunks.model.UniversalAddTarget
import io.github.gamesbyian.chunks.model.UniversalAddExecutor
import io.github.gamesbyian.chunks.model.UniversalAddLifecycle
import io.github.gamesbyian.chunks.model.UniversalAddObserver
import io.github.gamesbyian.chunks.model.UniversalAddRun
import io.github.gamesbyian.chunks.model.UniversalAddSettlement
import io.github.gamesbyian.chunks.model.UniversalAddWriteBoundary
import kotlinx.coroutines.CancellationException

/**
 * Bridges the shared Universal Add ticket to a Transmission/qBittorrent/Deluge
 * client through the one [ClientOperations] workflow, as
 * `LocalUniversalAddAdapter` and `RemoteUniversalAddAdapter` do for Local and
 * rTorrent. The coordinator owns authorization and evidence; this adapter only
 * builds the reviewed intent and records what the single write proved.
 */
internal object ProtocolUniversalAddAdapter {
    fun intent(
        clientId: String,
        request: ClientAddRequest,
        clientDefaultFolder: String?,
        allowArtwork: Boolean = true
    ): UniversalAddIntent = UniversalAddIntent(
        source = when (val source = request.source) {
            is ClientAddSource.TorrentFile -> UniversalAddSource.metainfo(source.bytes, source.infoHash)
            is ClientAddSource.Magnet -> UniversalAddSource.magnet(source.uri, source.infoHash)
        },
        target = UniversalAddTarget.ProtocolClient(EnvironmentId(clientId)),
        place = request.directory?.let { UniversalAddPlace.ServerCustom(it) }
            ?: UniversalAddPlace.ServerDefault(clientDefaultFolder.orEmpty()),
        startWhenAdded = request.startNow,
        allowArtwork = allowArtwork
    )

    /**
     * Execute exactly one Add for [ticket] through the shared lifecycle and
     * record the evidence on [coordinator]. Never retries; any failure after
     * the write boundary keeps the attempted-write fence.
     */
    suspend fun submit(
        coordinator: UniversalAddCoordinator,
        ticket: UniversalAddCoordinator.Ticket,
        operations: ClientOperations,
        request: ClientAddRequest,
        clock: () -> Long = System::currentTimeMillis
    ): ClientOperationOutcome {
        val executor = ProtocolAddExecutor(operations, request, clock)
        executor.requireTicket(ticket)
        return when (val run = UniversalAddLifecycle.run(coordinator, ticket, executor)) {
            is UniversalAddRun.Completed -> run.result
            is UniversalAddRun.Failed -> ClientOperationOutcome.Uncertain(request.source.infoHash, "unexpected failure")
        }
    }
}

/**
 * The Transmission/qBittorrent/Deluge executor for the
 * shared [UniversalAddLifecycle]. The one write is [ClientOperations.add]:
 * exact-absence precondition, ONE add, exact readback.
 */
internal class ProtocolAddExecutor(
    private val operations: ClientOperations,
    private val request: ClientAddRequest,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Local privacy preference preflight. Failure is a proven no-write refusal. */
    private val prepareBeforeWrite: (suspend () -> Boolean)? = null
) : UniversalAddExecutor<ClientOperationOutcome> {
    private var writeStartedAt = -1L

    fun requireTicket(ticket: UniversalAddCoordinator.Ticket) {
        require(ticket.intent.source.expectedInfoHash.equals(request.source.infoHash, ignoreCase = true)) {
            "The request is not the reviewed ticket's source"
        }
        require(ticket.intent.target is UniversalAddTarget.ProtocolClient) { "Not a protocol client ticket" }
    }

    override suspend fun execute(
        ticket: UniversalAddCoordinator.Ticket,
        boundary: UniversalAddWriteBoundary
    ): ClientOperationOutcome {
        requireTicket(ticket)
        // Opt-out must be persisted on this exact profile+hash before ANY RPC.
        // A failed preference write must never cross the one-shot write boundary.
        // No implicit success path for an opted-out ticket: direct adapter callers
        // must supply the exact-key persistence preflight, too.
        val prepared = try {
            ticket.intent.allowArtwork || (prepareBeforeWrite?.invoke() ?: false)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) { false }
        if (!prepared) return ClientOperationOutcome.Refused(request.source.infoHash,
            "The artwork preference could not be saved; nothing was sent")
        // Sampled before the write: the workflow's readback starts after it,
        // so this never makes the result look newer than the client's own read.
        writeStartedAt = clock()
        boundary.enter()
        return try { operations.add(request) } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            ClientOperationOutcome.Uncertain(request.source.infoHash, "unexpected failure")
        }
    }

    override suspend fun settle(
        ticket: UniversalAddCoordinator.Ticket,
        result: ClientOperationOutcome
    ): UniversalAddSettlement = when (result) {
        // Exact absence first, one write, exact readback: positive provenance.
        is ClientOperationOutcome.Verified -> UniversalAddSettlement.Observed(
            UniversalAddCoordinator.ExactSessionObservation(
                environment = ticket.intent.target.environment, infoHash = result.hash,
                observedAtEpochMillis = writeStartedAt, registered = true,
                reviewedPlaceVerified = true, started = request.startNow,
                newRegistrationVerified = true))
        // Nothing was written (precondition, capability or definite refusal).
        is ClientOperationOutcome.Refused -> UniversalAddSettlement.KnownNoWrite
        // Something may have landed: keep the attempted-write fence.
        is ClientOperationOutcome.Pending, is ClientOperationOutcome.Uncertain ->
            UniversalAddSettlement.ReadbackRequired
    }
}

/** Read-only exact-hash recheck of a retained protocol Add ticket. */
internal class ProtocolAddObserver(
    private val client: TorrentClient,
    private val clock: () -> Long = System::currentTimeMillis
) : UniversalAddObserver {
    /**
     * One exact-hash read (never a write). It can confirm the exact job, its
     * reviewed folder and requested Start, but never who registered it.
     */
    override suspend fun observe(
        ticket: UniversalAddCoordinator.Ticket
    ): UniversalAddCoordinator.ExactSessionObservation? {
        val hash = ticket.intent.source.expectedInfoHash
        val observedAt = clock()
        val torrent = try { client.torrent(hash) } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) { return null }
        if (torrent != null && !torrent.hash.equals(hash, ignoreCase = true)) return null
        val reviewedFolder = when (val place = ticket.intent.place) {
            is UniversalAddPlace.ServerCustom -> place.absolutePath
            is UniversalAddPlace.ServerDefault -> place.observedDefaultPath.ifBlank { null }
            else -> null
        }
        val started = torrent?.runState?.started
        return UniversalAddCoordinator.ExactSessionObservation(
            environment = ticket.intent.target.environment,
            infoHash = hash,
            observedAtEpochMillis = observedAt,
            registered = torrent != null,
            reviewedPlaceVerified = torrent != null &&
                ClientHashes.sameDirectory(torrent.downloadDirectory, reviewedFolder),
            // A client that is still checking or queued has not proved either Start state.
            started = started,
            newRegistrationVerified = false
        )
    }
}
