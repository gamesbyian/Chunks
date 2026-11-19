package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddIntent
import io.github.gamesbyian.chunks.model.UniversalAddPlace
import io.github.gamesbyian.chunks.model.UniversalAddSource
import io.github.gamesbyian.chunks.model.UniversalAddTarget
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
        clientDefaultFolder: String?
    ): UniversalAddIntent = UniversalAddIntent(
        source = when (val source = request.source) {
            is ClientAddSource.TorrentFile -> UniversalAddSource.metainfo(source.bytes, source.infoHash)
            is ClientAddSource.Magnet -> UniversalAddSource.magnet(source.uri, source.infoHash)
        },
        target = UniversalAddTarget.ProtocolClient(EnvironmentId(clientId)),
        place = request.directory?.let { UniversalAddPlace.ServerCustom(it) }
            ?: UniversalAddPlace.ServerDefault(clientDefaultFolder.orEmpty()),
        startWhenAdded = request.startNow,
        allowArtwork = false
    )

    /**
     * Execute exactly one Add for [ticket] and record the evidence on [coordinator].
     * Never retries; any failure after the write boundary keeps the attempted-write fence.
     */
    suspend fun submit(
        coordinator: UniversalAddCoordinator,
        ticket: UniversalAddCoordinator.Ticket,
        operations: ClientOperations,
        request: ClientAddRequest,
        clock: () -> Long = System::currentTimeMillis
    ): ClientOperationOutcome {
        val hash = request.source.infoHash
        require(ticket.intent.source.expectedInfoHash.equals(hash, ignoreCase = true)) {
            "The request is not the reviewed ticket's source"
        }
        // Sampled before the write: the readback the workflow performs starts after it,
        // so this never makes the result look newer than the client's own read.
        val writeStartedAt = clock()
        val outcome = try { operations.add(request) } catch (cancel: CancellationException) {
            ClientOperationOutcome.Uncertain(hash, "cancelled after submission")
        } catch (_: Exception) {
            ClientOperationOutcome.Uncertain(hash, "unexpected failure")
        }
        when (outcome) {
            // Exact absence first, one write, exact readback: positive provenance.
            is ClientOperationOutcome.Verified -> coordinator.reconcileExactSession(ticket,
                UniversalAddCoordinator.ExactSessionObservation(
                    environment = ticket.intent.target.environment, infoHash = hash,
                    observedAtEpochMillis = writeStartedAt, registered = true,
                    reviewedPlaceVerified = true, started = request.startNow,
                    newRegistrationVerified = true))
            // Nothing was written: the latch may disarm.
            is ClientOperationOutcome.Refused -> coordinator.refusedBeforeAnyWrite(ticket)
            // Something may have landed: keep the attempted-write fence.
            is ClientOperationOutcome.Pending, is ClientOperationOutcome.Uncertain ->
                coordinator.requireReadback(ticket)
        }
        return outcome
    }
}
