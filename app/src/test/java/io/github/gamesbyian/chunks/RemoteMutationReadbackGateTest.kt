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

    @Test fun provenIdleReleasesOnlyItsOwnNoWriteLease() {
        val gate = RemoteMutationReadbackGate()
        val beforeIdle = gate.ticket()
        assertTrue(gate.tryStart())
        val overlappingRead = gate.ticket()
        assertFalse(gate.tryStart())

        gate.abandonKnownNoWrite()
        assertFalse("An old snapshot must not inherit an idle lease", gate.accepts(beforeIdle))
        assertFalse("A read acquired while idle work ran is obsolete", gate.accepts(overlappingRead))
        assertTrue("A positively idle tick cannot lock out later Start/Stop", gate.tryStart())

        gate.settled() // This second attempt could have actually written.
        assertFalse("A real/uncertain write still requires fresh readback", gate.tryStart())
        assertTrue(gate.acceptFreshRead(gate.ticket()))
        assertTrue(gate.tryStart())
    }

    @Test fun noWriteAbortCannotClearASettledUncertainMutation() {
        val gate = RemoteMutationReadbackGate()
        assertTrue(gate.tryStart())
        gate.settled()
        val afterWrite = gate.ticket()
        gate.abandonKnownNoWrite()
        assertTrue("A later no-op abort must not invalidate readback", gate.accepts(afterWrite))
        assertFalse("Uncertain write cannot be retried without a read", gate.tryStart())
        assertTrue(gate.acceptFreshRead(afterWrite))
        assertTrue(gate.tryStart())
    }

    @Test fun provenIdleReleasesOnlyItsOwnNoWriteLease() {
        val gate = RemoteMutationReadbackGate()
        val beforeIdle = gate.ticket()
        assertTrue(gate.tryStart())
        val overlappingRead = gate.ticket()
        assertFalse(gate.tryStart())

        gate.abandonKnownNoWrite()
        assertFalse("An old snapshot must not inherit an idle lease", gate.accepts(beforeIdle))
        assertFalse("A read acquired while idle work ran is obsolete", gate.accepts(overlappingRead))
        assertTrue("A positively idle tick cannot lock out later Start/Stop", gate.tryStart())

        gate.settled() // This second attempt could have actually written.
        assertFalse("A real/uncertain write still requires fresh readback", gate.tryStart())
        assertTrue(gate.acceptFreshRead(gate.ticket()))
        assertTrue(gate.tryStart())
    }

    @Test fun noWriteAbortCannotClearASettledUncertainMutation() {
        val gate = RemoteMutationReadbackGate()
        assertTrue(gate.tryStart())
        gate.settled()
        val afterWrite = gate.ticket()
        gate.abandonKnownNoWrite()
        assertTrue("A later no-op abort must not invalidate readback", gate.accepts(afterWrite))
        assertFalse("Uncertain write cannot be retried without a read", gate.tryStart())
        assertTrue(gate.acceptFreshRead(afterWrite))
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
