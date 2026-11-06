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
