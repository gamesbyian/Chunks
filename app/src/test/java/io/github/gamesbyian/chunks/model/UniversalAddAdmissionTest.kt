package io.github.gamesbyian.chunks.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure cross-client authorization proof. Native/RPC writes are covered separately. */
class UniversalAddAdmissionTest {
    private val hash = "a".repeat(40)
    private val source = UniversalAddSource.magnet(
        "magnet:?xt=urn:btih:$hash&tr=https://private.invalid/passkey", hash)
    private val local = UniversalAddTarget.Local
    private val server = UniversalAddTarget.AuthenticatedServer(
        "https://host.invalid/RPC2\u0000exact-user")
    private val otherServer = UniversalAddTarget.AuthenticatedServer(
        "https://host.invalid/RPC2\u0000different-user")

    private fun intent(target: UniversalAddTarget) = UniversalAddIntent(
        source, target,
        if (target === local) UniversalAddPlace.Local("exact-reviewed-place")
        else UniversalAddPlace.ServerCustom("/exact-reviewed-folder"),
        startWhenAdded = true, allowArtwork = false)

    private fun capabilities(target: UniversalAddTarget) =
        if (target === local) EnvironmentCapabilities.local()
        else EnvironmentCapabilities.unprobed(target.environment)

    @Test fun identicalOneShotAdmissionForLocalAndExactAuthenticatedRemote() {
        for (target in listOf(local, server)) {
            val coordinator = UniversalAddCoordinator()
            var gateCalls = 0
            val request = intent(target)
            val accepted = UniversalAddAdmission.authorize(
                coordinator, request, capabilities(target), EnvironmentAvailability.LIVE,
                acquireMutationGate = { gateCalls++; true }, submittedAtEpochMillis = 1_000L)
            assertTrue(accepted.accepted)
            assertTrue(accepted.mutationGateAcquired)
            assertNull(accepted.refusal)
            assertEquals(1, gateCalls)
            val ticket = requireNotNull(accepted.ticket)
            assertSame(request, ticket.intent)
            assertEquals(target.environment, ticket.intent.target.environment)
            assertEquals(UniversalAddCoordinator.Phase.WRITE_ATTEMPTED, coordinator.phase)
            // A second click cannot reach either native or RPC gate, even if
            // a network response or Compose recomposition is delayed.
            val replay = UniversalAddAdmission.authorize(
                coordinator, request, capabilities(target), EnvironmentAvailability.LIVE,
                acquireMutationGate = { gateCalls++; true }, submittedAtEpochMillis = 1_001L)
            assertFalse(replay.accepted)
            assertEquals(UniversalAddAdmission.Refusal.REVIEW, replay.refusal)
            assertEquals(1, gateCalls)
            assertSame(accepted.ticket, coordinator.retainedTicket)
        }
    }

    @Test fun capabilityAccountAndFreshnessFailureNeverEnterMutationGate() {
        val coordinator = UniversalAddCoordinator()
        var gateCalls = 0
        val wrongAccount = UniversalAddAdmission.authorize(
            coordinator, intent(server), capabilities(otherServer), EnvironmentAvailability.LIVE,
            acquireMutationGate = { gateCalls++; true }, submittedAtEpochMillis = 1_000L)
        assertEquals(UniversalAddAdmission.Refusal.REVIEW, wrongAccount.refusal)
        assertFalse(wrongAccount.mutationGateAcquired)
        assertNull(coordinator.retainedTicket)

        val stale = UniversalAddAdmission.authorize(
            coordinator, intent(local), capabilities(local), EnvironmentAvailability.STALE,
            acquireMutationGate = { gateCalls++; true }, submittedAtEpochMillis = 1_000L)
        assertEquals(UniversalAddAdmission.Refusal.REVIEW, stale.refusal)
        assertFalse(stale.mutationGateAcquired)
        assertEquals(0, gateCalls)
        assertNull(coordinator.retainedTicket)
    }

    @Test fun refusedGateRequiresFreshUserClickAndNeverStartsAWrite() {
        val coordinator = UniversalAddCoordinator()
        val request = intent(local)
        val denied = UniversalAddAdmission.authorize(
            coordinator, request, capabilities(local), EnvironmentAvailability.LIVE,
            acquireMutationGate = { false }, submittedAtEpochMillis = 1_000L)
        assertEquals(UniversalAddAdmission.Refusal.MUTATION_GATE, denied.refusal)
        assertFalse(denied.mutationGateAcquired)
        assertNull(coordinator.retainedTicket)
        assertEquals(UniversalAddCoordinator.Phase.AUTHORIZED, coordinator.phase)
        // Re-review on another explicit click, rather than silently retrying.
        val authorized = UniversalAddAdmission.authorize(
            coordinator, request, capabilities(local), EnvironmentAvailability.LIVE,
            acquireMutationGate = { true }, submittedAtEpochMillis = 1_001L)
        assertTrue(authorized.accepted)
        assertEquals(1_001L, authorized.ticket!!.submittedAtEpochMillis)
    }

    @Test fun ticketFailureReportsAnAlreadyAcquiredGateForCallerToSettle() {
        val coordinator = UniversalAddCoordinator()
        val denied = UniversalAddAdmission.authorize(
            coordinator, intent(server), capabilities(server), EnvironmentAvailability.LIVE,
            acquireMutationGate = { true }, submittedAtEpochMillis = -1L)
        assertEquals(UniversalAddAdmission.Refusal.TICKET, denied.refusal)
        assertTrue(denied.mutationGateAcquired)
        assertNull(denied.ticket)
        assertNull(coordinator.retainedTicket)
    }
}
