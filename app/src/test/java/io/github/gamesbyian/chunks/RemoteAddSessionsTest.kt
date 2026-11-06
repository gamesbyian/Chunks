package io.github.gamesbyian.chunks

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
}
