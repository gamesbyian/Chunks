package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentCapabilities
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddIntent
import io.github.gamesbyian.chunks.model.UniversalAddPlace
import io.github.gamesbyian.chunks.model.UniversalAddSource
import io.github.gamesbyian.chunks.model.UniversalAddTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recheck consumes only a newer, exact-account negative witness.
 * It never authorizes or repeats the already attempted Add or Start. */
class VerifiedAddRecheckWitnessTest {
    private val hash = "a".repeat(40)
    private val server = UniversalAddTarget.AuthenticatedServer(
        "https://example.invalid/RPC2\u0000exact-user")

    private fun confirmed(): Pair<UniversalAddCoordinator, UniversalAddCoordinator.Ticket> {
        val state = UniversalAddCoordinator()
        val intent = UniversalAddIntent(
            UniversalAddSource.magnet("magnet:?xt=urn:btih:$hash", hash),
            server, UniversalAddPlace.ServerCustom("/downloads"),
            startWhenAdded = true, allowArtwork = false
        )
        assertTrue(state.review(intent, EnvironmentCapabilities.unprobed(server.environment),
            EnvironmentAvailability.LIVE).accepted)
        assertTrue(state.authorizeByUser())
        val ticket = requireNotNull(state.beginSubmission(1_000L))
        assertTrue(state.requireReadback(ticket))
        assertEquals(UniversalAddCoordinator.Readback.EXACT_SESSION_CONFIRMED,
            state.reconcileExactSession(ticket, observation(1_100L, true, true, true)))
        assertEquals(UniversalAddCoordinator.Phase.SESSION_CONFIRMED, state.phase)
        return state to ticket
    }

    private fun observation(at: Long, registered: Boolean, place: Boolean, started: Boolean?) =
        UniversalAddCoordinator.ExactSessionObservation(
            environment = server.environment, infoHash = hash,
            observedAtEpochMillis = at, registered = registered,
            reviewedPlaceVerified = place, started = started,
            newRegistrationVerified = false
        )

    @Test fun onlyPositiveUnacknowledgedSessionsRequestAnExtraContradictionRead() {
        assertTrue(shouldProbeContradictoryRecheck(UniversalAddCoordinator.Phase.SESSION_CONFIRMED))
        assertTrue(shouldProbeContradictoryRecheck(
            UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED))
        UniversalAddCoordinator.Phase.entries
            .filterNot { it == UniversalAddCoordinator.Phase.SESSION_CONFIRMED ||
                it == UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED }
            .forEach { assertFalse(shouldProbeContradictoryRecheck(it)) }
    }

    @Test fun newerExactAbsenceRevokesPreviousSessionWithoutRearmingAdd() {
        val (state, ticket) = confirmed()
        assertEquals(UniversalAddCoordinator.Readback.NOT_OBSERVED,
            state.reconcileExactSession(ticket, observation(1_200L, false, false, null)))
        assertEquals(UniversalAddCoordinator.Phase.READBACK_REQUIRED, state.phase)
        assertNull(state.beginSubmission(2_000L))
    }

    @Test fun wrongPlaceRevokesButUnknownAndStaleEvidenceDoNot() {
        val (state, ticket) = confirmed()
        assertEquals(UniversalAddCoordinator.Readback.NEEDS_FRESH_INVENTORY,
            state.reconcileExactSession(ticket, observation(1_050L, false, false, null)))
        assertEquals(UniversalAddCoordinator.Phase.SESSION_CONFIRMED, state.phase)
        assertEquals(UniversalAddCoordinator.Readback.WRONG_ENVIRONMENT,
            state.reconcileExactSession(ticket, observation(1_250L, false, false, null)
                .copy(environment = EnvironmentSnapshots.PHONE)))
        assertEquals(UniversalAddCoordinator.Phase.SESSION_CONFIRMED, state.phase)
        // A null adapter witness triggers no call. The coordinator remains
        // confirmed until an actual authenticated exact-job result arrives.
        val noWitness: UniversalAddCoordinator.ExactSessionObservation? = null
        noWitness?.let { state.reconcileExactSession(ticket, it) }
        assertEquals(UniversalAddCoordinator.Phase.SESSION_CONFIRMED, state.phase)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            state.reconcileExactSession(ticket, observation(1_300L, true, false, null)))
        assertEquals(UniversalAddCoordinator.Phase.OBSERVED_UNCERTAIN, state.phase)
        assertNull(state.beginSubmission(2_000L))
    }
}
