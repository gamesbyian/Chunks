package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentCapabilities
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddIntent
import io.github.gamesbyian.chunks.model.UniversalAddPlace
import io.github.gamesbyian.chunks.model.UniversalAddSource
import io.github.gamesbyian.chunks.model.UniversalAddTarget
import org.junit.Assert.*
import org.junit.Test

class RemoteAddSessionsTest {
    private val first = "https://box.example/rutorrent2/RPC2\u0000owner"
    private val other = "https://box.example/rutorrent2/RPC2\u0000OWNER"

    @Test fun ordinaryReconnectRetainsReviewWhileAnotherOpaqueAccountIsIndependent() {
        val drafts = RemoteAddSessions()
        val review = drafts.forAccount(first)
        review.useFile = true
        review.destination = "/reviewed"
        review.alreadyAttempted = true
        review.uncertain = true
        assertSame(review, drafts.forAccount(first))
        assertTrue(drafts.forAccount(first).useFile)
        assertTrue(drafts.forAccount(first).alreadyAttempted)
        val different = drafts.forAccount(other)
        assertNotSame(review, different)
        assertFalse(different.useFile)
        assertFalse(different.alreadyAttempted)
        assertEquals("", different.destination)
    }

    @Test fun explicitMagnetPreparedBeforeLoginReplacesFileModeOnceAfterAuthentication() {
        val drafts = RemoteAddSessions()
        val review = drafts.forAccount(first)
        review.useFile = true
        drafts.selectMagnet("")
        assertTrue("No authenticated target was selected yet", review.useFile)
        assertSame(review, drafts.forAccount(first))
        assertFalse("Explicit incoming magnet must outrank old file mode", review.useFile)
        review.useFile = true // A subsequent explicit file review.
        assertTrue("Ordinary reconnect must not replay the old handoff", drafts.forAccount(first).useFile)
    }

    @Test fun supersedingSourceCancelsDeferredMagnetWithoutClearingAnAttemptedReview() {
        val drafts = RemoteAddSessions()
        val review = drafts.forAccount(first)
        review.useFile = true
        drafts.selectMagnet("")
        drafts.clearPendingSourceSelection()
        assertTrue(drafts.forAccount(first).useFile)
        review.alreadyAttempted = true
        review.uncertain = true
        drafts.selectMagnet(first)
        assertTrue(review.useFile)
        assertTrue(review.alreadyAttempted)
        assertTrue(review.uncertain)
    }

    @Test fun onlyExplicitAcknowledgmentOfExactSessionReleasesUncertainAdd() {
        val review = RemoteAddSession(first, "/reviewed")
        val hash = "ab".repeat(20)
        val target = UniversalAddTarget.AuthenticatedServer(first)
        val intent = UniversalAddIntent(
            source = UniversalAddSource.magnet("magnet:?xt=urn:btih:$hash", hash),
            target = target,
            place = UniversalAddPlace.ServerCustom("/reviewed"),
            startWhenAdded = true, allowArtwork = true)
        assertTrue(review.coordinator.review(intent,
            EnvironmentCapabilities.unprobed(target.environment),
            EnvironmentAvailability.LIVE).accepted)
        assertTrue(review.coordinator.authorizeByUser())
        val ticket = requireNotNull(review.coordinator.beginSubmission(1000L))
        review.attemptTicket = ticket
        review.alreadyAttempted = true
        review.uncertain = true
        review.verifiedHash = hash
        review.coordinator.requireReadback(ticket)
        assertFalse("A found UI hash cannot release a still-uncertain write", review.beginNextDraft())
        assertSame(ticket, review.attemptTicket)
        assertEquals(UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED,
            review.coordinator.reconcileExactSession(ticket,
                UniversalAddCoordinator.ExactSessionObservation(
                    target.environment, hash, 2000L, registered = true,
                    reviewedPlaceVerified = false, started = true, newRegistrationVerified = false)))
        assertFalse("Wrong Place must leave the attempt fenced", review.beginNextDraft())
        assertEquals(UniversalAddCoordinator.Readback.EXACT_SESSION_CONFIRMED,
            review.coordinator.reconcileExactSession(ticket,
                UniversalAddCoordinator.ExactSessionObservation(
                    target.environment, hash, 3000L, registered = true,
                    reviewedPlaceVerified = true, started = true, newRegistrationVerified = false)))
        assertEquals(UniversalAddCoordinator.Phase.SESSION_CONFIRMED, review.coordinator.phase)
        assertTrue("Explicit acknowledgment may follow exact-session proof even with uncertain provenance",
            review.beginNextDraft())
        assertEquals(UniversalAddCoordinator.Phase.EMPTY, review.coordinator.phase)
        assertNull(review.attemptTicket)
        assertFalse(review.alreadyAttempted)
        assertNull(review.verifiedHash)
        assertFalse(review.uncertain)
    }
}
