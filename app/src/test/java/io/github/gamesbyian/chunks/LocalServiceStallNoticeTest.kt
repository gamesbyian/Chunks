package io.github.gamesbyian.chunks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalServiceStallNoticeTest {
    @Test fun warningIsOnlyEmittedOncePerStalledRead() {
        val notice = LocalServiceStallNotice()
        notice.newPollAdmitted()
        assertFalse(notice.shouldWarn(null))
        assertFalse(notice.shouldWarn(14_999L))
        assertTrue(notice.shouldWarn(15_000L))
        repeat(20_000) { assertFalse(notice.shouldWarn(Long.MAX_VALUE)) }
        // If a read returns after its warning, no extra notice is needed.
        assertFalse(notice.shouldWarn(null))
    }

    @Test fun newlyAdmittedReadMayWarnAgainButShortReadsCannot() {
        val notice = LocalServiceStallNotice()
        assertTrue(notice.shouldWarn(15_000))
        notice.newPollAdmitted()
        assertFalse(notice.shouldWarn(0))
        assertFalse(notice.shouldWarn(14_000))
        assertTrue(notice.shouldWarn(25_000))
        notice.newPollAdmitted()
        assertFalse(notice.shouldWarn(null))
        assertTrue(notice.shouldWarn(16_000))
    }

    @Test fun customThresholdMustBePositive() {
        try {
            LocalServiceStallNotice(0)
            throw AssertionError("Zero threshold should not be valid")
        } catch (_: IllegalArgumentException) { }
    }
}
