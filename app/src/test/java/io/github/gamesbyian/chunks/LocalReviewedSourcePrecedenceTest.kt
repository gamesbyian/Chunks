package io.github.gamesbyian.chunks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReviewedSourcePrecedenceTest {
    @Test fun selectedDocumentCanSubmitOnlyWithoutNewerTypedSourceOrPendingRead() {
        assertTrue(localReviewedMetainfoIsCurrent("", reading = false))
        assertFalse(localReviewedMetainfoIsCurrent("", reading = true))
        assertFalse(localReviewedMetainfoIsCurrent(
            "https://example.test/private.torrent?token=secret", reading = false))
        assertFalse(localReviewedMetainfoIsCurrent("magnet:?xt=urn:btih:INVALID", reading = false))
        assertFalse(localReviewedMetainfoIsCurrent(
            "magnet:?xt=urn:btih:" + "a".repeat(40), reading = true))
        assertTrue("Clearing a rejected candidate re-enables the existing verified .torrent",
            localReviewedMetainfoIsCurrent("", reading = false))
    }
}
