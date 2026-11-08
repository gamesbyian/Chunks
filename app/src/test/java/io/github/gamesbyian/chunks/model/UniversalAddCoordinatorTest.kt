package io.github.gamesbyian.chunks.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalAddCoordinatorTest {
    private val hash = "a".repeat(40)
    private val otherHash = "b".repeat(40)
    private val phone = UniversalAddTarget.Phone
    private val remote = UniversalAddTarget.AuthenticatedServer("https://host.invalid/RPC2\u0000exact-user")
    private val otherRemote = UniversalAddTarget.AuthenticatedServer("https://host.invalid/RPC2\u0000another-user")

    private fun magnet() = UniversalAddSource.magnet(
        "magnet:?xt=urn:btih:$hash&tr=https://private.invalid/announce?passkey=SECRET", hash)

    private fun intent(
        target: UniversalAddTarget = phone,
        place: UniversalAddPlace = if (target === phone)
            UniversalAddPlace.Phone("verified-phone-place")
        else UniversalAddPlace.ServerCustom("/srv/downloads"),
        start: Boolean = true,
        source: UniversalAddSource = magnet()
    ) = UniversalAddIntent(source, target, place, start, allowArtwork = false)

    private fun review(
        coordinator: UniversalAddCoordinator,
        intent: UniversalAddIntent,
        availability: EnvironmentAvailability = EnvironmentAvailability.LIVE
    ) = coordinator.review(intent,
        if (intent.target === phone) EnvironmentCapabilities.phone()
        else EnvironmentCapabilities.unprobed(intent.target.environment), availability)

    private fun snapshot(
        target: UniversalAddTarget,
        at: Long,
        started: Boolean,
        transferHash: String = hash,
        complete: Boolean = true,
        availability: EnvironmentAvailability = EnvironmentAvailability.LIVE
    ): EnvironmentTransferSnapshot {
        val key = UnifiedTransferKey.of(target.environment, transferHash)
        val observation = UnifiedTransferObservation(
            key, "test", "test", sizeBytes = null, progressPpm = null,
            started = started, downloadBytesPerSecond = null,
            uploadBytesPerSecond = null, peers = null)
        return EnvironmentTransferSnapshot(target.environment, availability, complete,
            at, listOf(observation))
    }

    private fun attempted(
        coordinator: UniversalAddCoordinator,
        request: UniversalAddIntent = intent()
    ): UniversalAddCoordinator.Ticket {
        assertTrue(review(coordinator, request).accepted)
        assertTrue(coordinator.authorizeByUser())
        return requireNotNull(coordinator.beginSubmission(1000))
    }

    @Test fun reviewAndConfirmationProduceOnlyOneWriteTicket() {
        val state = UniversalAddCoordinator()
        assertTrue(review(state, intent()).accepted)
        assertNull(state.beginSubmission(1000))
        assertTrue(state.authorizeByUser())
        assertFalse(state.authorizeByUser())
        assertNotNull(state.beginSubmission(1000))
        assertNull(state.beginSubmission(1001))
        assertEquals(UniversalAddCoordinator.Phase.WRITE_ATTEMPTED, state.phase)
    }

    @Test fun wrongEnvironmentAndEnginePlaceAreRefusedBeforeApproval() {
        val state = UniversalAddCoordinator()
        val wrongCapabilities = EnvironmentCapabilities.unprobed(remote.environment)
        assertFalse(state.review(intent(), wrongCapabilities, EnvironmentAvailability.LIVE).accepted)
        assertFalse(review(state, intent(phone, UniversalAddPlace.ServerCustom("/srv"))).accepted)
        assertFalse(review(state, intent(remote, UniversalAddPlace.Phone("local-place"))).accepted)
        assertNull(state.beginSubmission(1000))
    }

    @Test fun capabilityAndFreshnessArePrerequisitesButNotWriteAuthority() {
        val state = UniversalAddCoordinator()
        assertFalse(review(state, intent(), EnvironmentAvailability.STALE).accepted)
        val missing = EnvironmentCapabilitySnapshot(
            phone.environment, "phone engine", 100L,
            mapOf(EnvironmentOperation.ADD_MAGNET to CapabilityEvidence.MISSING))
        assertFalse(state.review(intent(), missing, EnvironmentAvailability.LIVE).accepted)
        assertTrue(review(state, intent()).accepted)
        assertNull(state.beginSubmission(1000))
    }

    @Test fun revisedPlaceOrStartRequiresNewUserApproval() {
        val state = UniversalAddCoordinator()
        assertTrue(review(state, intent(start = true)).accepted)
        assertTrue(state.authorizeByUser())
        assertTrue(review(state, intent(start = false,
            place = UniversalAddPlace.Phone("different-place"))).accepted)
        assertNull(state.beginSubmission(1000))
        assertTrue(state.authorizeByUser())
        assertFalse(requireNotNull(state.beginSubmission(1000)).intent.startWhenAdded)
    }

    @Test fun navigationCanReadRetainedDraftAndTicketWithoutReauthorizing() {
        val state = UniversalAddCoordinator()
        val draft = intent(remote)
        assertTrue(review(state, draft).accepted)
        assertTrue(state.retainedReview === draft)
        assertNull(state.retainedTicket)
        assertTrue(state.authorizeByUser())
        val ticket = requireNotNull(state.beginSubmission(1000))
        assertTrue(state.retainedTicket === ticket)
        assertTrue(state.retainedReview === draft)
        assertTrue(state.requireReadback(ticket))
        // A remounted view is allowed to inspect the ticket, but not resend.
        assertTrue(state.retainedTicket === ticket)
        assertNull(state.beginSubmission(1001))
        assertFalse(review(state, intent(remote)).accepted)
    }

    @Test fun preWireRefusalCanRearmButExceptionAfterWriteCannot() {
        val state = UniversalAddCoordinator()
        val first = attempted(state)
        assertTrue(state.refusedBeforeAnyWrite(first))
        assertEquals(UniversalAddCoordinator.Phase.REVIEWED, state.phase)
        assertTrue(state.authorizeByUser())
        val second = requireNotNull(state.beginSubmission(1001))
        assertFalse(state.requireReadback(first))
        assertTrue(state.requireReadback(second))
        assertFalse(state.refusedBeforeAnyWrite(second))
        assertNull(state.beginSubmission(1002))
        assertFalse(review(state, intent()).accepted)
        assertFalse(state.finishVerified())
    }

    @Test fun onlyExactFreshAccountAndHashCanBeReconciled() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state, intent(remote))
        assertEquals(UniversalAddCoordinator.Readback.WRONG_ENVIRONMENT,
            state.reconcile(ticket, snapshot(otherRemote, 1002, started = true),
                true, true, true))
        assertEquals(UniversalAddCoordinator.Readback.NEEDS_FRESH_INVENTORY,
            state.reconcile(ticket, snapshot(remote, 999, started = true), true, true, true))
        assertEquals(UniversalAddCoordinator.Readback.NEEDS_FRESH_INVENTORY,
            state.reconcile(ticket, snapshot(remote, 1002, started = true, complete = false),
                true, true, true))
        assertEquals(UniversalAddCoordinator.Readback.NOT_OBSERVED,
            state.reconcile(ticket, snapshot(remote, 1002, started = true, transferHash = otherHash),
                true, true, true))
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, state.phase)
        assertNull(state.beginSubmission(1003))
    }

    @Test fun matchingReadbackIsInsufficientWithoutAdapterRegistrationAndPlaceProof() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state, intent(remote))
        val observed = snapshot(remote, 1010, started = true)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcile(ticket, observed, adapterConfirmedNewAdd = false,
                adapterVerifiedPlace = true, adapterVerifiedStart = true))
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcile(ticket, observed, adapterConfirmedNewAdd = true,
                adapterVerifiedPlace = false, adapterVerifiedStart = true))
        assertNull(state.beginSubmission(1020))
        assertFalse(state.finishVerified())
    }

    @Test fun requestedStartRequiresBothAdapterAndInventoryProof() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcile(ticket, snapshot(phone, 1100, started = false),
                true, true, true))
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcile(ticket, snapshot(phone, 1100, started = true),
                true, true, false))
        assertEquals(UniversalAddCoordinator.Readback.VERIFIED,
            state.reconcile(ticket, snapshot(phone, 1100, started = true),
                true, true, true))
        assertTrue(state.finishVerified())
        assertEquals(UniversalAddCoordinator.Phase.EMPTY, state.phase)
    }

    @Test fun stoppedReviewCannotBeVerifiedByUnexpectedlyStartedJob() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state, intent(start = false))
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcile(ticket, snapshot(phone, 1100, started = true),
                true, true, false))
        assertEquals(UniversalAddCoordinator.Readback.VERIFIED,
            state.reconcile(ticket, snapshot(phone, 1101, started = false),
                true, true, false))
    }

    @Test fun frozenMagnetCannotClaimADifferentHash() {
        val failure = runCatching {
            UniversalAddSource.magnet("magnet:?xt=urn:btih:$hash", otherHash)
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is IllegalArgumentException)
    }

    @Test fun hiddenRemoteSessionMayBeConfirmedWithoutInventingNewAddAttribution() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state, intent(remote, start = true))
        val matched = UniversalAddCoordinator.ExactSessionObservation(
            environment = remote.environment, infoHash = hash,
            observedAtEpochMillis = 1200L, registered = true,
            reviewedPlaceVerified = true, started = true,
            newRegistrationVerified = false)
        assertEquals(UniversalAddCoordinator.Readback.EXACT_SESSION_CONFIRMED,
            state.reconcileExactSession(ticket, matched))
        assertEquals(UniversalAddCoordinator.Phase.SESSION_CONFIRMED, state.phase)
        assertFalse(state.finishVerified()) // No proof this client created the job.
        assertNull(state.beginSubmission(1201L))
        assertFalse(review(state, intent(remote)).accepted)
        // Recovery is read-only and can be deliberately ended without a retry.
        assertTrue(state.acknowledgeExactSession())
        assertEquals(UniversalAddCoordinator.Phase.EMPTY, state.phase)
    }

    @Test fun exactSessionResultCanVerifyOwnNewRegistrationWithPlaceAndStart() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state, intent(remote, start = false))
        val evidence = UniversalAddCoordinator.ExactSessionObservation(
            environment = remote.environment, infoHash = hash,
            observedAtEpochMillis = 1001L, registered = true,
            reviewedPlaceVerified = true, started = false,
            newRegistrationVerified = true)
        assertEquals(UniversalAddCoordinator.Readback.VERIFIED,
            state.reconcileExactSession(ticket, evidence))
        assertTrue(state.finishVerified())
    }

    @Test fun hiddenSessionRejectsWrongPrincipalStaleEvidenceAndWrongPlace() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state, intent(remote))
        val valid = UniversalAddCoordinator.ExactSessionObservation(
            environment = remote.environment, infoHash = hash,
            observedAtEpochMillis = 1010L, registered = true,
            reviewedPlaceVerified = true, started = true,
            newRegistrationVerified = true)
        assertEquals(UniversalAddCoordinator.Readback.WRONG_ENVIRONMENT,
            state.reconcileExactSession(ticket,
                valid.copy(environment = otherRemote.environment)))
        assertEquals(UniversalAddCoordinator.Readback.NEEDS_FRESH_INVENTORY,
            state.reconcileExactSession(ticket,
                valid.copy(observedAtEpochMillis = 999L)))
        assertEquals(UniversalAddCoordinator.Readback.NOT_OBSERVED,
            state.reconcileExactSession(ticket,
                valid.copy(infoHash = otherHash)))
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcileExactSession(ticket,
                valid.copy(reviewedPlaceVerified = false)))
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcileExactSession(ticket,
                valid.copy(started = false)))
        assertEquals(UniversalAddCoordinator.Readback.VERIFIED,
            state.reconcileExactSession(ticket, valid))
        assertFalse(state.acknowledgeExactSession())
    }

    @Test fun metadataOnlyMagnetStartPendingCanBeExplicitlyAcknowledgedWithoutReplay() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state, intent(start = true))
        val waiting = UniversalAddCoordinator.ExactSessionObservation(
            environment = phone.environment, infoHash = hash,
            observedAtEpochMillis = 1001L, registered = true,
            reviewedPlaceVerified = true, started = null,
            newRegistrationVerified = true)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcileExactSession(ticket, waiting))
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED, state.phase)
        assertFalse(state.finishVerified())
        assertFalse(state.acknowledgeExactSession())
        assertNull(state.beginSubmission(1002L))
        assertFalse(review(state, intent()).accepted)
        assertFalse(state.refusedBeforeAnyWrite(ticket))
        assertTrue(state.acknowledgeObservedJob())
        assertEquals(UniversalAddCoordinator.Phase.EMPTY, state.phase)
        assertNull(state.retainedTicket)
        assertEquals(UniversalAddCoordinator.Readback.WRONG_TICKET,
            state.reconcileExactSession(ticket, waiting))
        assertTrue(review(state, intent()).accepted)
        // Acknowledging never confers new mutation authority.
        assertNull(state.beginSubmission(1100L))
        assertTrue(state.authorizeByUser())
        assertNotNull(state.beginSubmission(1101L))
    }

    @Test fun deferredStartMayEventuallyVerifyWithoutAcknowledgmentOrSecondWrite() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state)
        val pending = UniversalAddCoordinator.ExactSessionObservation(
            environment = phone.environment, infoHash = hash,
            observedAtEpochMillis = 1001L, registered = true,
            reviewedPlaceVerified = true, started = null,
            newRegistrationVerified = true)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcileExactSession(ticket, pending))
        assertTrue(state.requireReadback(ticket))
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED, state.phase)
        assertNull(state.beginSubmission(1002L))
        assertEquals(UniversalAddCoordinator.Readback.VERIFIED,
            state.reconcileExactSession(ticket,
                pending.copy(started = true, observedAtEpochMillis = 1400L)))
        assertEquals(UniversalAddCoordinator.Phase.VERIFIED, state.phase)
        assertFalse(state.acknowledgeObservedJob())
        assertTrue(state.finishVerified())
    }

    @Test fun laterVerifiedStartWithoutRegistrationProvenanceNeedsSeparateAcknowledgment() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state)
        val pending = UniversalAddCoordinator.ExactSessionObservation(
            environment = phone.environment, infoHash = hash,
            observedAtEpochMillis = 1100L, registered = true,
            reviewedPlaceVerified = true, started = null,
            newRegistrationVerified = false)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcileExactSession(ticket, pending))
        assertEquals(UniversalAddCoordinator.Readback.EXACT_SESSION_CONFIRMED,
            state.reconcileExactSession(ticket, pending.copy(
                observedAtEpochMillis = 1200L, started = true)))
        assertEquals(UniversalAddCoordinator.Phase.SESSION_CONFIRMED, state.phase)
        assertFalse(state.finishVerified())
        assertFalse(state.acknowledgeObservedJob())
        assertTrue(state.acknowledgeExactSession())
    }

    @Test fun wrongAccountHashPlaceStaleAndAbsentJobCannotUnlockObservedJobAcknowledgment() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state, intent(remote))
        val valid = UniversalAddCoordinator.ExactSessionObservation(
            environment = remote.environment, infoHash = hash,
            observedAtEpochMillis = 1100L, registered = true,
            reviewedPlaceVerified = true, started = null,
            newRegistrationVerified = false)
        assertEquals(UniversalAddCoordinator.Readback.WRONG_ENVIRONMENT,
            state.reconcileExactSession(ticket,
                valid.copy(environment = otherRemote.environment)))
        assertFalse(state.acknowledgeObservedJob())
        assertEquals(UniversalAddCoordinator.Readback.NOT_OBSERVED,
            state.reconcileExactSession(ticket,
                valid.copy(infoHash = otherHash)))
        assertFalse(state.acknowledgeObservedJob())
        assertEquals(UniversalAddCoordinator.Readback.NEEDS_FRESH_INVENTORY,
            state.reconcileExactSession(ticket,
                valid.copy(observedAtEpochMillis = 999L)))
        assertFalse(state.acknowledgeObservedJob())
        assertEquals(UniversalAddCoordinator.Readback.NOT_OBSERVED,
            state.reconcileExactSession(ticket,
                valid.copy(registered = false)))
        assertFalse(state.acknowledgeObservedJob())
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcileExactSession(ticket,
                valid.copy(reviewedPlaceVerified = false)))
        assertEquals(UniversalAddCoordinator.Phase.OBSERVED_UNCERTAIN, state.phase)
        assertFalse(state.acknowledgeObservedJob())
    }

    @Test fun completeCatalogMayConfirmExactPlaceWhileRequestedStartRemainsUnverified() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcile(ticket, snapshot(phone, 1100L, started = false),
                adapterConfirmedNewAdd = true, adapterVerifiedPlace = false,
                adapterVerifiedStart = false))
        assertFalse(state.acknowledgeObservedJob())
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcile(ticket, snapshot(phone, 1101L, started = false),
                adapterConfirmedNewAdd = true, adapterVerifiedPlace = true,
                adapterVerifiedStart = false))
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED, state.phase)
        assertNull(state.beginSubmission(1102L))
        assertTrue(state.acknowledgeObservedJob())
    }

    @Test fun anUnexpectedStartForAReviewedStoppedAddNeedsExplicitAcknowledgment() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state, intent(start = false))
        val unexpected = UniversalAddCoordinator.ExactSessionObservation(
            environment = phone.environment, infoHash = hash,
            observedAtEpochMillis = 1010L, registered = true,
            reviewedPlaceVerified = true, started = true,
            newRegistrationVerified = true)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcileExactSession(ticket, unexpected))
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED, state.phase)
        assertFalse(state.finishVerified())
        assertTrue(state.acknowledgeObservedJob())
    }

    @Test fun newerExactMissingRegistrationRevokesPreviouslyAcknowledgableJob() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state, intent(remote))
        val observed = UniversalAddCoordinator.ExactSessionObservation(
            environment = remote.environment, infoHash = hash,
            observedAtEpochMillis = 1700L, registered = true,
            reviewedPlaceVerified = true, started = null,
            newRegistrationVerified = false)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcileExactSession(ticket, observed))
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED, state.phase)
        assertEquals(UniversalAddCoordinator.Readback.NOT_OBSERVED,
            state.reconcileExactSession(ticket, observed.copy(
                observedAtEpochMillis = 1800L, registered = false)))
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, state.phase)
        assertFalse(state.acknowledgeObservedJob())
        assertNull(state.beginSubmission(1801L))
        assertEquals(UniversalAddCoordinator.Readback.NEEDS_FRESH_INVENTORY,
            state.reconcileExactSession(ticket, observed.copy(observedAtEpochMillis = 1750L)))
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcileExactSession(ticket, observed.copy(observedAtEpochMillis = 1900L)))
        assertTrue(state.acknowledgeObservedJob())
        assertEquals(UniversalAddCoordinator.Phase.EMPTY, state.phase)
    }

    @Test fun newerExactPlaceContradictionRevokesObservedJobAcknowledgment() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state)
        val observed = UniversalAddCoordinator.ExactSessionObservation(
            environment = phone.environment, infoHash = hash,
            observedAtEpochMillis = 1700L, registered = true,
            reviewedPlaceVerified = true, started = null,
            newRegistrationVerified = false)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcileExactSession(ticket, observed))
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcileExactSession(ticket, observed.copy(
                observedAtEpochMillis = 1800L, reviewedPlaceVerified = false)))
        assertEquals(UniversalAddCoordinator.Phase.OBSERVED_UNCERTAIN, state.phase)
        assertFalse(state.acknowledgeObservedJob())
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcileExactSession(ticket, observed.copy(observedAtEpochMillis = 1900L)))
        assertTrue(state.acknowledgeObservedJob())
    }

    @Test fun newerCompleteCatalogAbsentJobOrWrongPlaceRevokesOldAcknowledgment() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state)
        fun present(at: Long, placeVerified: Boolean) = state.reconcile(
            ticket, snapshot(phone, at, started = false),
            adapterConfirmedNewAdd = false, adapterVerifiedPlace = placeVerified,
            adapterVerifiedStart = false)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            present(1700L, true))
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            present(1800L, false))
        assertEquals(UniversalAddCoordinator.Phase.OBSERVED_UNCERTAIN, state.phase)
        assertFalse(state.acknowledgeObservedJob())
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            present(1900L, true))
        val empty = EnvironmentTransferSnapshot(
            phone.environment, EnvironmentAvailability.LIVE,
            true, 2000L, emptyList())
        assertEquals(UniversalAddCoordinator.Readback.NOT_OBSERVED,
            state.reconcile(ticket, empty))
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, state.phase)
        assertFalse(state.acknowledgeObservedJob())
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            present(2100L, true))
        assertTrue(state.acknowledgeObservedJob())
    }

    @Test fun ambiguousOrUnrelatedReadsDoNotRevokePositiveObservedJob() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state, intent(remote))
        val observed = UniversalAddCoordinator.ExactSessionObservation(
            environment = remote.environment, infoHash = hash,
            observedAtEpochMillis = 1700L, registered = true,
            reviewedPlaceVerified = true, started = null,
            newRegistrationVerified = false)
        state.reconcileExactSession(ticket, observed)
        assertEquals(UniversalAddCoordinator.Readback.WRONG_ENVIRONMENT,
            state.reconcileExactSession(ticket, observed.copy(
                environment = otherRemote.environment, observedAtEpochMillis = 2000L)))
        assertEquals(UniversalAddCoordinator.Readback.NOT_OBSERVED,
            state.reconcileExactSession(ticket, observed.copy(
                infoHash = otherHash, observedAtEpochMillis = 2000L)))
        assertEquals(UniversalAddCoordinator.Readback.NEEDS_FRESH_INVENTORY,
            state.reconcile(ticket, snapshot(remote, 2000L, started = false, complete = false),
                adapterVerifiedPlace = false))
        assertEquals(UniversalAddCoordinator.Readback.NEEDS_FRESH_INVENTORY,
            state.reconcileExactSession(ticket, observed.copy(observedAtEpochMillis = 1200L)))
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED, state.phase)
        assertTrue(state.requireReadback(ticket))
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED, state.phase)
        assertTrue(state.acknowledgeObservedJob())
    }

    @Test fun olderExactReadCannotPromotePendingStartAfterNewerExactObservation() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state, intent(remote, start = true))
        val pending = UniversalAddCoordinator.ExactSessionObservation(
            environment = remote.environment, infoHash = hash,
            observedAtEpochMillis = 1700L, registered = true,
            reviewedPlaceVerified = true, started = null,
            newRegistrationVerified = false)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcileExactSession(ticket, pending))
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED, state.phase)
        // Both observations followed Submit; only one followed the latest read.
        assertEquals(UniversalAddCoordinator.Readback.NEEDS_FRESH_INVENTORY,
            state.reconcileExactSession(ticket, pending.copy(
                observedAtEpochMillis = 1500L, started = true, newRegistrationVerified = true)))
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED, state.phase)
        assertNull(state.beginSubmission(1750L))
        assertEquals(UniversalAddCoordinator.Readback.VERIFIED,
            state.reconcileExactSession(ticket, pending.copy(
                observedAtEpochMillis = 1800L, started = true, newRegistrationVerified = true)))
    }

    @Test fun catalogReadbackWatermarkAlsoFencesOlderExactSession() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcile(ticket, snapshot(phone, 1800L, started = false),
                adapterConfirmedNewAdd = true, adapterVerifiedPlace = true,
                adapterVerifiedStart = false))
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED, state.phase)
        val exact = UniversalAddCoordinator.ExactSessionObservation(
            environment = phone.environment, infoHash = hash,
            observedAtEpochMillis = 1700L, registered = true,
            reviewedPlaceVerified = true, started = true,
            newRegistrationVerified = true)
        assertEquals(UniversalAddCoordinator.Readback.NEEDS_FRESH_INVENTORY,
            state.reconcileExactSession(ticket, exact))
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED, state.phase)
        assertEquals(UniversalAddCoordinator.Readback.VERIFIED,
            state.reconcileExactSession(ticket,
                exact.copy(observedAtEpochMillis = 1900L)))
    }

    @Test fun exactReadbackWatermarkAlsoFencesOlderCatalogSnapshot() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state)
        val pending = UniversalAddCoordinator.ExactSessionObservation(
            environment = phone.environment, infoHash = hash,
            observedAtEpochMillis = 1700L, registered = true,
            reviewedPlaceVerified = true, started = null,
            newRegistrationVerified = false)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcileExactSession(ticket, pending))
        assertEquals(UniversalAddCoordinator.Readback.NEEDS_FRESH_INVENTORY,
            state.reconcile(ticket, snapshot(phone, 1400L, started = true),
                adapterConfirmedNewAdd = true, adapterVerifiedPlace = true,
                adapterVerifiedStart = true))
        assertEquals(UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED, state.phase)
        assertEquals(UniversalAddCoordinator.Readback.VERIFIED,
            state.reconcile(ticket, snapshot(phone, 1900L, started = true),
                adapterConfirmedNewAdd = true, adapterVerifiedPlace = true,
                adapterVerifiedStart = true))
    }

    @Test fun unrelatedHashWrongAccountAndIncompleteReadNeverPoisonTimestampFence() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state, intent(remote))
        val exact = UniversalAddCoordinator.ExactSessionObservation(
            environment = remote.environment, infoHash = hash,
            observedAtEpochMillis = 1600L, registered = true,
            reviewedPlaceVerified = true, started = true,
            newRegistrationVerified = true)
        assertEquals(UniversalAddCoordinator.Readback.WRONG_ENVIRONMENT,
            state.reconcileExactSession(ticket, exact.copy(
                environment = otherRemote.environment, observedAtEpochMillis = 9000L)))
        assertEquals(UniversalAddCoordinator.Readback.NOT_OBSERVED,
            state.reconcileExactSession(ticket, exact.copy(
                infoHash = otherHash, observedAtEpochMillis = 9000L)))
        assertEquals(UniversalAddCoordinator.Readback.NEEDS_FRESH_INVENTORY,
            state.reconcile(ticket, snapshot(remote, 9000L, started = true, complete = false),
                adapterConfirmedNewAdd = true, adapterVerifiedPlace = true,
                adapterVerifiedStart = true))
        assertEquals(UniversalAddCoordinator.Readback.VERIFIED,
            state.reconcileExactSession(ticket, exact))
    }

    @Test fun acceptedNegativeReadbackFencesOlderLatePositiveWithoutReplaying() {
        val state = UniversalAddCoordinator()
        val ticket = attempted(state, intent(remote))
        val valid = UniversalAddCoordinator.ExactSessionObservation(
            environment = remote.environment, infoHash = hash,
            observedAtEpochMillis = 1800L, registered = false,
            reviewedPlaceVerified = false, started = null,
            newRegistrationVerified = false)
        assertEquals(UniversalAddCoordinator.Readback.NOT_OBSERVED,
            state.reconcileExactSession(ticket, valid))
        assertEquals(UniversalAddCoordinator.Readback.NEEDS_FRESH_INVENTORY,
            state.reconcileExactSession(ticket, valid.copy(
                observedAtEpochMillis = 1700L, registered = true,
                reviewedPlaceVerified = true, started = true,
                newRegistrationVerified = true)))
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, state.phase)
        assertNull(state.beginSubmission(1900L))
        assertEquals(UniversalAddCoordinator.Readback.VERIFIED,
            state.reconcileExactSession(ticket, valid.copy(
                observedAtEpochMillis = 2000L, registered = true,
                reviewedPlaceVerified = true, started = true,
                newRegistrationVerified = true)))
    }

    @Test fun latestObservationIsScopedToTicketAndEqualTimeEvidenceCanResolve() {
        val state = UniversalAddCoordinator()
        val first = attempted(state)
        val pending = UniversalAddCoordinator.ExactSessionObservation(
            environment = phone.environment, infoHash = hash,
            observedAtEpochMillis = 2000L, registered = true,
            reviewedPlaceVerified = true, started = null,
            newRegistrationVerified = true)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcileExactSession(first, pending))
        // Independent exact engine proofs may legitimately share a millisecond.
        assertEquals(UniversalAddCoordinator.Readback.VERIFIED,
            state.reconcileExactSession(first, pending.copy(started = true)))
        assertTrue(state.finishVerified())
        val second = attempted(state)
        assertEquals(UniversalAddCoordinator.Readback.VERIFIED,
            state.reconcileExactSession(second,
                pending.copy(observedAtEpochMillis = 1001L, started = true)))
        assertEquals(UniversalAddCoordinator.Readback.WRONG_TICKET,
            state.reconcileExactSession(first, pending))
    }

    @Test fun sourceCopiesBytesAndRedactsPrivateValuesFromDiagnostics() {
        val bytes = byteArrayOf(1, 2, 3)
        val source = UniversalAddSource.metainfo(bytes, hash, "https://private.invalid/SECRET")
        bytes[0] = 9
        assertEquals(1, source.metainfoForSubmission()!![0].toInt())
        val returned = source.metainfoForSubmission()!!
        returned[1] = 8
        assertEquals(2, source.metainfoForSubmission()!![1].toInt())
        assertFalse(source.toString().contains("SECRET"))
        assertFalse(intent(source = source).toString().contains("SECRET"))
        assertFalse(magnet().toString().contains("SECRET"))
        assertFalse(remote.toString().contains("exact-user"))
    }
}
