package io.github.gamesbyian.chunks.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaInventoryRetryHeartbeatTest {
    @Test fun firstHostObservationAndFrequentInventoryTicksNeverRepeatQueries() {
        val clock = MediaInventoryRetryHeartbeat()
        assertFalse(clock.shouldRefresh(100_000L))
        for (step in 1..170) {
            assertFalse("Ten-second UI refresh must not repeat full-inventory enrichment",
                clock.shouldRefresh(100_000L + step * 10_000L))
        }
        assertTrue("A stable, eligible inventory gets an eventual retry",
            clock.shouldRefresh(100_000L + 180 * 10_000L))
        assertFalse(clock.shouldRefresh(100_000L + 181 * 10_000L))
        assertTrue(clock.shouldRefresh(100_000L + 360 * 10_000L))
    }

    @Test fun longSleepAndExpiredNegativeCacheGetOneBoundedRefresh() {
        val clock = MediaInventoryRetryHeartbeat(1000L)
        assertFalse(clock.shouldRefresh(100L))
        assertTrue(clock.shouldRefresh(20_000L))
        assertFalse(clock.shouldRefresh(20_001L))
        assertFalse(clock.shouldRefresh(20_999L))
        assertTrue(clock.shouldRefresh(21_000L))
    }

    @Test fun monotonicResetAndInvalidSampleNeverFloodRetry() {
        val clock = MediaInventoryRetryHeartbeat(1000L)
        assertFalse(clock.shouldRefresh(50_000L))
        assertFalse(clock.shouldRefresh(-1L))
        assertFalse(clock.shouldRefresh(12L))
        assertFalse(clock.shouldRefresh(999L))
        assertTrue(clock.shouldRefresh(1012L))
    }

    @Test fun eachHostLifecycleStartsWithNewInitialObservation() {
        val old = MediaInventoryRetryHeartbeat(100L)
        assertFalse(old.shouldRefresh(100L))
        assertTrue(old.shouldRefresh(200L))
        val next = MediaInventoryRetryHeartbeat(100L)
        assertFalse("New session does its own initial inventory pass",
            next.shouldRefresh(200L))
    }
}
