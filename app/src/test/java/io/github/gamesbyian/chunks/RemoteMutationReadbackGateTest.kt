package io.github.gamesbyian.chunks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMutationReadbackGateTest {
    @Test fun onlyAnObservationStartedAfterSettlementCanClearTheMutationLatch() {
        val gate = RemoteMutationReadbackGate()
        val earlierRead = gate.ticket()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart()) // no simultaneous remote writes
        assertFalse(gate.accepts(earlierRead))

        val overlappingRead = gate.ticket()
        assertFalse(gate.accepts(overlappingRead))
        gate.settled()
        // A completed response from an overlapping HTTP refresh is not readback.
        assertFalse(gate.accepts(earlierRead))
        assertFalse(gate.accepts(overlappingRead))
        assertFalse(gate.tryStart()) // no next write before a successful read
        assertTrue(gate.acceptFreshRead(gate.ticket()))
        assertTrue(gate.tryStart())
    }

    @Test fun reconnectionInvalidatesOldResponsesAndAllowsNewWrites() {
        val gate = RemoteMutationReadbackGate()
        assertTrue(gate.tryStart())
        val oldSessionRead = gate.ticket()
        gate.reset()
        assertFalse(gate.accepts(oldSessionRead))
        assertTrue(gate.tryStart())
        gate.settled()
        assertTrue(gate.acceptFreshRead(gate.ticket()))
    }
}
