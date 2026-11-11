package io.github.gamesbyian.chunks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneReviewedSourcePrecedenceTest {
    @Test fun selectedDocumentCanSubmitOnlyWithoutNewerTypedSourceOrPendingRead() {
        assertTrue(phoneReviewedMetainfoIsCurrent("", reading = false))
        assertFalse(phoneReviewedMetainfoIsCurrent("", reading = true))
        assertFalse(phoneReviewedMetainfoIsCurrent(
            "https://example.test/private.torrent?token=secret", reading = false))
        assertFalse(phoneReviewedMetainfoIsCurrent("magnet:?xt=urn:btih:INVALID", reading = false))
        assertFalse(phoneReviewedMetainfoIsCurrent(
            "magnet:?xt=urn:btih:" + "a".repeat(40), reading = true))
        assertTrue("Clearing a rejected candidate re-enables the existing verified .torrent",
            phoneReviewedMetainfoIsCurrent("", reading = false))
    }
}
