package io.github.gamesbyian.chunks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalServicePollGateTest {
    @Test fun repeatedTicksSkipWhileReadIsActive() {
        val gate = LocalServicePollGate()
        assertTrue(gate.tryBegin())
        repeat(20_000) { assertFalse(gate.tryBegin()) }
        gate.finish()
        assertTrue(gate.tryBegin())
        gate.finish()
    }

    @Test fun inFlightAgeIsMonotonicAndClearsAfterFinish() {
        val gate = LocalServicePollGate()
        assertTrue(gate.inFlightMillis(20_000_000_000L) == null)
        assertTrue(gate.tryBegin(nowNanos = 2_000_000_000L))
        assertFalse(gate.tryBegin(nowNanos = 3_000_000_000L))
        assertTrue(gate.inFlightMillis(17_000_000_000L) == 15_000L)
        assertTrue(gate.inFlightMillis(1_000_000_000L) == 0L)
        gate.finish()
        assertTrue(gate.inFlightMillis(18_000_000_000L) == null)
        assertTrue(gate.tryBegin(nowNanos = 19_000_000_000L))
        assertTrue(gate.inFlightMillis(19_001_000_000L) == 1L)
        gate.finish()
    }

    @Test fun exceptionCleanupAllowsNextObservation() {
        val gate = LocalServicePollGate()
        assertTrue(gate.tryBegin())
        try {
            check(false)
        } catch (_: IllegalStateException) {
            // A failed read must leave the next scheduled poll possible.
        } finally {
            gate.finish()
        }
        assertTrue(gate.tryBegin())
        assertFalse(gate.tryBegin())
        gate.finish()
    }
}
