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
