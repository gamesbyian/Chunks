package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.SessionEraseOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Readback talks about exact session membership, not a possibly-hidden main view. */
class SessionEraseFeedbackTest {
    private val hash = "a".repeat(40)

    @Test fun verifiedAbsentIsSessionSpecificAndDoesNotClaimPayloadInspection() {
        val text = sessionEraseFeedback(SessionEraseOutcome.VerifiedRemoved(hash))
        assertTrue(text.contains("gone from the server list"))
        assertTrue(text.contains("downloaded files weren't checked"))
        assertFalse(text.contains("main server view"))
    }

    @Test fun alreadyAbsentDidNotSendAnotherErase() {
        val text = sessionEraseFeedback(SessionEraseOutcome.AlreadyAbsent(hash))
        assertTrue(text.contains("already absent from the server"))
        assertTrue(text.contains("No removal request was sent"))
        assertFalse(text.contains("main view"))
    }

    @Test fun stillPresentCouldBeInHiddenViewAndRequiresInspection() {
        val text = sessionEraseFeedback(SessionEraseOutcome.PendingRemoval(hash))
        assertTrue(text.contains("still reports this exact infohash in its session"))
        assertTrue(text.contains("Refresh and inspect"))
        assertFalse(text.contains("main view"))
    }

    @Test fun uncertainNeverClaimsSuccessOrPromisesSafeRetry() {
        val text = sessionEraseFeedback(SessionEraseOutcome.Uncertain(hash))
        assertTrue(text.contains("couldn't confirm removal"))
        assertTrue(text.contains("Refresh its status before trying again"))
        assertFalse(text.contains("Verified"))
    }
}
