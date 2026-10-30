package io.github.gamesbyian.chunks.rpc

import org.junit.Assert.*
import org.junit.Test

class RemoteTrafficWindowTest {
    private val hash = "aa".repeat(20)
    private fun point(n: Int, rx: Long? = 0, verified: Long? = 0, size: Long? = 1000,
                      peers: Long? = 1, active: Boolean? = true) = RemoteTrafficSample(
        hash = hash, monotonicNanos = n * 1_000_000_000L,
        receivedBytes = rx, verifiedBytes = verified, expectedBytes = size,
        peers = peers, active = active, rateBytesPerSecond = 0)

    @Test fun actualReceivedAndVerifiedGrowthRequiresThreeStableSamples() {
        val result = RemoteTrafficWindow.evaluate(listOf(
            point(1, 100, 100, peers = 0),
            point(3, 150, 100, peers = 2),
            point(5, 250, 200, peers = 1)))
        assertEquals(TrafficVerdict.RECEIVED_AND_VERIFIED, result.verdict)
        assertEquals(150L, result.receivedDelta)
        assertEquals(100L, result.verifiedDelta)
        assertEquals(2L, result.peakPeers)
        assertEquals(4000L, result.elapsedMillis)
        assertTrue(result.summary().contains("both increased"))
    }

    @Test fun aSingleNonzeroRateOrStartFlagCannotInventByteProgress() {
        val sample = point(1).copy(rateBytesPerSecond = 9999, active = true)
        val verdict = RemoteTrafficWindow.evaluate(listOf(sample, point(2), point(3)))
        assertEquals(TrafficVerdict.NO_PROGRESS_OBSERVED, verdict.verdict)
        assertFalse(verdict.summary().contains("confirms progress"))
    }

    @Test fun distinguishUnverifiedTrafficFromRehashOfExistingBytes() {
        assertEquals(TrafficVerdict.RECEIVED_ONLY,
            RemoteTrafficWindow.evaluate(listOf(
                point(1, 0, 0), point(2, 70, 0), point(3, 90, 0))).verdict)
        val reused = RemoteTrafficWindow.evaluate(listOf(
            point(1, 100, 0), point(2, 100, 20), point(3, 100, 50)))
        assertEquals(TrafficVerdict.VERIFIED_ONLY, reused.verdict)
        assertTrue(reused.summary().contains("Rechecking"))
    }

    @Test fun unavailableCountersNeverBecomeZeroOrPositive() {
        val missing = listOf(point(1, null, null), point(2, null, null), point(3, null, null))
        assertEquals(TrafficVerdict.INSUFFICIENT_EVIDENCE,
            RemoteTrafficWindow.evaluate(missing).verdict)
        val partial = listOf(point(1, null, 5), point(2, null, 8), point(3, null, 10))
        assertEquals(TrafficVerdict.VERIFIED_ONLY,
            RemoteTrafficWindow.evaluate(partial).verdict)
        assertNull(RemoteTrafficWindow.evaluate(partial).receivedDelta)
    }

    @Test fun interruptedCounterCollectionCannotProveNoProgress() {
        // The middle getter failed, but the endpoints hint at a real recheck.
        // We cannot call this zero progress without complete observations.
        val missingMiddleVerified = listOf(
            point(1, rx = 100, verified = 20),
            point(2, rx = 100, verified = null),
            point(3, rx = 100, verified = 30))
        val result = RemoteTrafficWindow.evaluate(missingMiddleVerified)
        assertEquals(TrafficVerdict.INSUFFICIENT_EVIDENCE, result.verdict)
        assertEquals(0L, result.receivedDelta)
        assertNull(result.verifiedDelta)
        assertFalse(result.summary().contains("No positive byte growth"))

        val missingMiddleReceived = listOf(
            point(1, rx = 100, verified = 20),
            point(2, rx = null, verified = 20),
            point(3, rx = 140, verified = 20))
        assertEquals(TrafficVerdict.INSUFFICIENT_EVIDENCE,
            RemoteTrafficWindow.evaluate(missingMiddleReceived).verdict)

        // Complete non-growing counters still support the existing verdict.
        assertEquals(TrafficVerdict.NO_PROGRESS_OBSERVED,
            RemoteTrafficWindow.evaluate(listOf(point(1), point(2), point(3))).verdict)
    }

    @Test fun aCounterResetOrChangingSizeInvalidatesGrowth() {
        val resets = listOf(point(1, 100, 30), point(2, 50, 35), point(3, 150, 40))
        assertEquals(TrafficVerdict.COUNTER_RESET_OR_SIZE_CHANGED,
            RemoteTrafficWindow.evaluate(resets).verdict)
        val changedSize = listOf(point(1, 100, 30), point(2, 200, 50, size = 2000),
            point(3, 300, 90))
        assertEquals(TrafficVerdict.COUNTER_RESET_OR_SIZE_CHANGED,
            RemoteTrafficWindow.evaluate(changedSize).verdict)
        val impossible = listOf(point(1, 100, 30), point(2, 200, 50),
            point(3, 300, 1001))
        assertEquals(TrafficVerdict.COUNTER_RESET_OR_SIZE_CHANGED,
            RemoteTrafficWindow.evaluate(impossible).verdict)
    }

    @Test fun identityAndClockAndThreeSampleContractFailClosed() {
        try {
            RemoteTrafficWindow.evaluate(listOf(point(1), point(2)))
            fail("Should require three observations")
        } catch (_: IllegalArgumentException) { }
        try {
            RemoteTrafficWindow.evaluate(listOf(point(1), point(2).copy(hash = "bb".repeat(20)), point(3)))
            fail("Should reject different hash")
        } catch (_: IllegalArgumentException) { }
        try {
            RemoteTrafficWindow.evaluate(listOf(point(1), point(2), point(2)))
            fail("Should reject stalled clock")
        } catch (_: IllegalArgumentException) { }
    }
}
