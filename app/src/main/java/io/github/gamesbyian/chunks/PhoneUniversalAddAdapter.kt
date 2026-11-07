package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentCapabilities
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddIntent
import io.github.gamesbyian.chunks.model.UniversalAddPlace
import io.github.gamesbyian.chunks.model.UniversalAddSource
import io.github.gamesbyian.chunks.model.UniversalAddTarget
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File

/**
 * Phone-specific *read-only* bridge into the shared Add ticket protocol.
 *
 * This class neither starts libtorrent nor chooses a default Place. The
 * already verified phone Place, current source and explicit Start choice are
 * frozen by the existing Add review immediately before its user-triggered
 * mutation. All writes remain in LocalTorrentEngine.
 */
internal object PhoneUniversalAddAdapter {
    /**
     * Existing hashes in one COMPLETE fresh phone inventory are a known
     * no-write refusal before libtorrent is entered. Never use an incomplete
     * or stale list to conclude that the source is already registered.
     */
    fun alreadyPresentInFreshInventory(
        hash: String, inventory: List<LocalTransferStatus>, fresh: Boolean
    ): Boolean = fresh && inventory.any { it.infoHash.equals(hash, ignoreCase = true) }

    fun torrentIntent(
        reviewed: PendingLocalAdd,
        place: LocalDownloadPlace,
        subfolder: String,
        startWhenAdded: Boolean,
        allowArtwork: Boolean
    ): UniversalAddIntent {
        // PendingLocalAdd retains a mutable byte array for navigation. Recheck
        // its exact reviewed identity at the authorization boundary, then copy
        // it into UniversalAddSource. The executor must use only that copy.
        // Snapshot once before parsing. An Activity/source replacement must
        // never change the bytes between the preview hash check and the
        // coordinator's frozen submission copy.
        val sourceBytes = reviewed.bytes.copyOf()
        require(reviewed.stillMatchesReviewedSource(sourceBytes)) {
            "The reviewed torrent source changed"
        }
        val current = try {
            TorrentMetainfoReader.preview(sourceBytes)
        } catch (_: Exception) {
            throw IllegalArgumentException("The reviewed torrent source changed")
        }
        require(current == reviewed.preview) {
            "The verified source no longer matches the visible review"
        }
        return intent(
            UniversalAddSource.metainfo(sourceBytes, current.infoHashV1),
            place, subfolder, startWhenAdded, allowArtwork
        )
    }

    fun magnetIntent(
        checked: MagnetDraftCheck.Valid,
        place: LocalDownloadPlace,
        subfolder: String,
        startWhenAdded: Boolean,
        allowArtwork: Boolean
    ): UniversalAddIntent = intent(
        UniversalAddSource.magnet(checked.normalized, checked.infoHash),
        place, subfolder, startWhenAdded, allowArtwork
    )

    private fun intent(
        source: UniversalAddSource,
        place: LocalDownloadPlace,
        subfolder: String,
        startWhenAdded: Boolean,
        allowArtwork: Boolean
    ): UniversalAddIntent {
        val safeChild = requireNotNull(LocalOwnedAddFolder.normalizedName(subfolder)) {
            "Invalid per-Add subfolder"
        }
        return UniversalAddIntent(
            source = source,
            target = UniversalAddTarget.Phone,
            place = UniversalAddPlace.Phone(LocalAddPlacePolicy.identity(place), safeChild),
            startWhenAdded = startWhenAdded,
            allowArtwork = allowArtwork
        )
    }

    /** A capability may refuse the review; it can never perform a mutation. */
    fun review(coordinator: UniversalAddCoordinator, intent: UniversalAddIntent,
               inventoryConfirmed: Boolean, inventoryStale: Boolean):
        UniversalAddCoordinator.ReviewDecision = coordinator.review(
            intent, EnvironmentCapabilities.phone(),
            if (inventoryConfirmed && !inventoryStale) EnvironmentAvailability.LIVE
            else EnvironmentAvailability.STALE
        )

    /**
     * Refuse to run an earlier ticket against a different Place, even if that
     * destination later becomes writable. Engine write probes still happen
     * separately immediately before registration.
     */
    fun matchesPlace(ticket: UniversalAddCoordinator.Ticket,
                     place: LocalDownloadPlace, subfolder: String): Boolean {
        val reviewed = ticket.intent.place as? UniversalAddPlace.Phone ?: return false
        return ticket.intent.target === UniversalAddTarget.Phone &&
            reviewed.verifiedPlaceId == LocalAddPlacePolicy.identity(place) &&
            reviewed.relativeSubfolder == LocalOwnedAddFolder.normalizedName(subfolder)
    }

    fun expectedDirectory(ticket: UniversalAddCoordinator.Ticket, place: LocalDownloadPlace): File? {
        if (!matchesPlace(ticket, place,
                (ticket.intent.place as? UniversalAddPlace.Phone)?.relativeSubfolder ?: return null))
            return null
        return LocalOwnedAddFolder.proposed(place.root,
            (ticket.intent.place as UniversalAddPlace.Phone).relativeSubfolder)
    }

    /**
     * A magnet may be running in libtorrent upload-only metadata-discovery
     * mode even when the owner explicitly chose "do not start payload".
     * Treat that state as stopped for the *payload* Start contract, and do
     * not claim that a requested Start succeeded before upload-only clears.
     */
    fun observedPayloadStarted(status: LocalTransferStatus): Boolean? = when {
        status.stopped || status.uploadOnly -> false
        !status.metadataKnown -> null
        else -> true
    }

    /**
     * Positive native provenance requires a token returned by THIS engine
     * Add and the exact same token in a later accepted native observation.
     * Merely finding the hash after a timeout cannot prove who added it.
     */
    fun registrationToken(
        ticket: UniversalAddCoordinator.Ticket,
        result: LocalAddResult, place: LocalDownloadPlace
    ): String? {
        val status = when (result) {
            is LocalAddResult.AddedStopped -> result.status
            is LocalAddResult.FetchingMetadata -> result.status
            is LocalAddResult.Refused -> return null
        }
        val expected = expectedDirectory(ticket, place) ?: return null
        if (!status.infoHash.equals(ticket.intent.source.expectedInfoHash, ignoreCase = true) ||
            !sameDirectory(status.savePath, expected)) return null
        return status.nativeReviewToken.takeIf { it.isNotBlank() }
    }

    fun exactObservation(
        ticket: UniversalAddCoordinator.Ticket,
        status: LocalTransferStatus?,
        expectedPlace: LocalDownloadPlace,
        nativeTokenFromThisAdd: String?,
        observedAtEpochMillis: Long
    ): UniversalAddCoordinator.ExactSessionObservation {
        val expected = expectedDirectory(ticket, expectedPlace)
        val matchesHash = status != null &&
            status.infoHash.equals(ticket.intent.source.expectedInfoHash, ignoreCase = true)
        val matchesPlace = expected != null && status != null &&
            sameDirectory(status.savePath, expected)
        return UniversalAddCoordinator.ExactSessionObservation(
            environment = ticket.intent.target.environment,
            infoHash = ticket.intent.source.expectedInfoHash,
            observedAtEpochMillis = observedAtEpochMillis,
            registered = matchesHash,
            reviewedPlaceVerified = matchesHash && matchesPlace,
            started = if (matchesHash) observedPayloadStarted(requireNotNull(status)) else null,
            newRegistrationVerified = matchesHash && matchesPlace &&
                !nativeTokenFromThisAdd.isNullOrBlank() &&
                nativeTokenFromThisAdd == status?.nativeReviewToken
        )
    }

    private fun sameDirectory(actual: String, expected: File): Boolean =
        actual.isNotBlank() && runCatching {
            File(actual).canonicalFile == expected.canonicalFile
        }.getOrDefault(false)
}

/**
 * App-owned process-only phone attempt evidence, analogous to the existing
 * RemoteAddSession. The coordinator is the sole write authorization latch;
 * this stores only a positive native generation receipt for later readback.
 * Never serialize into a Bundle, preferences or a saved-instance state.
 */
internal class PhoneAddAttemptSession(
    val coordinator: UniversalAddCoordinator = UniversalAddCoordinator()
) {
    private var ticketWithReceipt: UniversalAddCoordinator.Ticket? = null
    private var nativeGenerationReceipt: String? = null
    /** A positive registration may be shown in Combined once, separately from Start proof. */
    private var announcedRegistrationTicket: UniversalAddCoordinator.Ticket? = null
    private var ticketWithPlace: UniversalAddCoordinator.Ticket? = null
    private var frozenPlace: LocalDownloadPlace? = null

    val attemptedTicket: UniversalAddCoordinator.Ticket?
        get() = coordinator.retainedTicket

    /** Bind the exact Place alongside the ticket before any native Add call. */
    fun bindPlace(ticket: UniversalAddCoordinator.Ticket, place: LocalDownloadPlace) {
        require(coordinator.retainedTicket === ticket) { "Not the current phone Add attempt" }
        require(PhoneUniversalAddAdapter.expectedDirectory(ticket, place) != null) {
            "The frozen ticket belongs to another phone Place"
        }
        ticketWithPlace = ticket
        frozenPlace = place
        ticketWithReceipt = null
        nativeGenerationReceipt = null
        announcedRegistrationTicket = null
    }

    fun noteNativeResult(ticket: UniversalAddCoordinator.Ticket,
                         result: LocalAddResult, place: LocalDownloadPlace) {
        if (coordinator.retainedTicket !== ticket) return
        ticketWithReceipt = ticket
        nativeGenerationReceipt = PhoneUniversalAddAdapter.registrationToken(ticket, result, place)
        coordinator.requireReadback(ticket)
    }

    /**
     * The existing Combined "new transfer" notice means a registration was
     * positively read back, not that metadata, payload Start or peer bytes
     * have been verified. The source must have returned this ticket's exact
     * native-generation receipt at the reviewed Place. Never announce on
     * Refused or on finding only an already-present matching hash.
     */
    fun announceNativeRegistrationOnce(ticket: UniversalAddCoordinator.Ticket): Boolean {
        if (coordinator.retainedTicket !== ticket ||
            ticketWithReceipt !== ticket || nativeGenerationReceipt.isNullOrBlank() ||
            announcedRegistrationTicket === ticket) return false
        announcedRegistrationTicket = ticket
        return true
    }

    /** A later independently verified ticket may notify only if not already announced. */
    fun announceVerifiedRegistrationOnce(ticket: UniversalAddCoordinator.Ticket): Boolean {
        if (coordinator.retainedTicket !== ticket ||
            coordinator.phase != UniversalAddCoordinator.Phase.VERIFIED ||
            announcedRegistrationTicket === ticket) return false
        announcedRegistrationTicket = ticket
        return true
    }

    /** Called only for a new completed post-attempt engine observation. */
    fun reconcile(ticket: UniversalAddCoordinator.Ticket, status: LocalTransferStatus?,
                  place: LocalDownloadPlace, observedAtEpochMillis: Long):
        UniversalAddCoordinator.Readback {
        if (coordinator.retainedTicket !== ticket) return UniversalAddCoordinator.Readback.WRONG_TICKET
        val exact = PhoneUniversalAddAdapter.exactObservation(
            ticket, status, place,
            nativeGenerationReceipt.takeIf { ticketWithReceipt === ticket },
            observedAtEpochMillis)
        return coordinator.reconcileExactSession(ticket, exact)
    }

    /** Called only after a new completed, current-generation phone inventory read. */
    fun reconcileInventory(transfers: List<LocalTransferStatus>, observedAtEpochMillis: Long):
        UniversalAddCoordinator.Readback? {
        val ticket = attemptedTicket ?: return null
        val place = frozenPlace?.takeIf { ticketWithPlace === ticket } ?: return null
        val matching = transfers.filter {
            it.infoHash.equals(ticket.intent.source.expectedInfoHash, ignoreCase = true)
        }
        // An ambiguous duplicated native identity is never positive evidence.
        if (matching.size > 1) return UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED
        return reconcile(ticket, matching.singleOrNull(), place, observedAtEpochMillis)
    }

    /**
     * A fresh read established an exact job and reviewed Place, but requested
     * payload Start remains uncertain. Only an explicit user acknowledgement
     * can release that ticket; no engine Start or Add is sent here.
     */
    fun acknowledgeObservedJob(): Boolean = clearEvidenceAfter(coordinator.acknowledgeObservedJob())

    fun finishReviewedAttempt(): Boolean =
        clearEvidenceAfter(coordinator.finishVerified() || coordinator.acknowledgeExactSession())

    private fun clearEvidenceAfter(done: Boolean): Boolean {
        if (done) {
            ticketWithReceipt = null
            nativeGenerationReceipt = null
            announcedRegistrationTicket = null
            ticketWithPlace = null
            frozenPlace = null
        }
        return done
    }
}
