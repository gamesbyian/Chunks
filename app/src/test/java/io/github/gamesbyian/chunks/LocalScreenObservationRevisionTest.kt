package io.github.gamesbyian.chunks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScreenObservationRevisionTest {
    @Test fun preCommandAndOverlappingReadsCannotClearAnUncertainWrite() {
        val gate = LocalScreenObservationRevision()
        val earlier = gate.snapshot()
        assertTrue(gate.beginMutation())
        assertTrue(gate.blocksMutations())
        assertFalse(gate.beginMutation())
        assertFalse(gate.acceptObservation(earlier, listOf("old state")))

        val overlapping = gate.snapshot()
        assertFalse(gate.acceptObservation(overlapping, listOf("mid-write state")))
        gate.settledMutation()
        assertFalse(gate.acceptObservation(overlapping, listOf("old result arriving late")))
        assertTrue(gate.blocksMutations())
        assertFalse(gate.beginMutation())
    }

    @Test fun failedInventoryIsNotReadbackButGenuineEmptyInventoryIs() {
        val gate = LocalScreenObservationRevision()
        assertTrue(gate.beginMutation())
        gate.settledMutation()
        val postCommand = gate.snapshot()

        assertTrue(gate.acceptObservation<String>(postCommand, null))
        assertTrue(gate.blocksMutations())
        assertFalse(gate.beginMutation())

        assertTrue(gate.acceptObservation(postCommand, emptyList<String>()))
        assertFalse(gate.blocksMutations())
        assertTrue(gate.beginMutation())
    }

    @Test fun screenNavigationCannotReopenWritesWhileReadbackIsOutstanding() {
        // Both screen instances refer to the same process-wide revision gate.
        val gate = LocalScreenObservationRevision()
        assertTrue(gate.beginMutation())
        gate.settledMutation()
        val newScreen = gate
        assertTrue(newScreen.blocksMutations())
        assertFalse(newScreen.beginMutation())
        val oldSample = gate.snapshot()
        assertTrue(newScreen.acceptObservation(oldSample, listOf("still present")))
        assertFalse(gate.blocksMutations())
    }
}
