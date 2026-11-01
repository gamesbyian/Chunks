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
        assertTrue(text.contains("absent from rTorrent's session"))
        assertTrue(text.contains("Physical files were not inspected"))
        assertFalse(text.contains("main server view"))
    }

    @Test fun alreadyAbsentDidNotSendAnotherErase() {
        val text = sessionEraseFeedback(SessionEraseOutcome.AlreadyAbsent(hash))
        assertTrue(text.contains("session"))
        assertTrue(text.contains("No erase command was sent"))
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
        assertTrue(text.contains("uncertain"))
        assertTrue(text.contains("never blindly repeat"))
        assertFalse(text.contains("Verified"))
    }
}
