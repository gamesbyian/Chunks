package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API35 admission-order acceptance for the native phone screen state contract.
 * This exercises readback fencing on Android, not an actual blocked JNI transfer.
 */
@RunWith(AndroidJUnit4::class)
class LocalScreenPostMutationReadbackInstrumentedTest {
    @Test fun latePreCommandAndMidCommandReadsCannotReopenWrites() {
        val gate = LocalScreenObservationRevision()
        val before = gate.snapshot()
        assertTrue(gate.beginMutation())
        assertFalse(gate.beginMutation())
        assertFalse(gate.acceptObservation(before, listOf("old torrent")))
        val during = gate.snapshot()
        assertFalse(gate.acceptObservation(during, listOf("incomplete torrent")))
        gate.settledMutation()
        assertFalse(gate.acceptObservation(during, listOf("late torrent")))
        assertTrue(gate.blocksMutations())
        assertFalse(gate.beginMutation())
    }

    @Test fun failureDoesNotClearFenceButFreshEmptyListCan() {
        val gate = LocalScreenObservationRevision()
        assertTrue(gate.beginMutation())
        gate.settledMutation()
        val post = gate.snapshot()
        assertTrue(gate.acceptObservation<String>(post, null))
        assertTrue(gate.blocksMutations())
        assertFalse(gate.beginMutation())
        assertTrue(gate.acceptObservation(post, emptyList<String>()))
        assertFalse(gate.blocksMutations())
        assertTrue(gate.beginMutation())
    }

    @Test fun navigationDoesNotResetOutstandingReadback() {
        val oldScreenGate = LocalScreenObservationRevision()
        assertTrue(oldScreenGate.beginMutation())
        oldScreenGate.settledMutation()
        val newScreenGate = oldScreenGate
        assertTrue(newScreenGate.blocksMutations())
        assertFalse(newScreenGate.beginMutation())
        assertTrue(newScreenGate.acceptObservation(newScreenGate.snapshot(), listOf("still present")))
        assertFalse(oldScreenGate.blocksMutations())
    }
}
