package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InterruptedSwarmEvidenceTest {
    @Test fun partialDownloadMustAdvanceByFourMiBAfterRestart() {
        val start = 8L shl 20
        val wanted = 64L shl 20
        assertEquals(start + (4L shl 20),
            InterruptedSwarmEvidence.continuationGoal(start, wanted))
    }

    @Test fun nearCompleteTransferStillRequiresPositiveNewBytes() {
        val wanted = 64L shl 20
        val before = wanted - 3
        assertEquals(wanted, InterruptedSwarmEvidence.continuationGoal(before, wanted))
        assertTrue(requireNotNull(InterruptedSwarmEvidence.continuationGoal(before, wanted)) > before)
    }

    @Test fun completedFixtureCannotCertifyInterruptedContinuation() {
        val wanted = 64L shl 20
        assertNull(InterruptedSwarmEvidence.continuationGoal(wanted, wanted))
        assertNull(InterruptedSwarmEvidence.continuationGoal(wanted + 1, wanted))
    }

    @Test fun invalidOrEmptyProofMarkerIsRefused() {
        assertNull(InterruptedSwarmEvidence.continuationGoal(0, 100))
        assertNull(InterruptedSwarmEvidence.continuationGoal(-1, 100))
        assertNull(InterruptedSwarmEvidence.continuationGoal(1, 0))
        assertNull(InterruptedSwarmEvidence.continuationGoal(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test fun largeValidTransferCannotOverflowGoal() {
        val before = Long.MAX_VALUE - 1L
        assertEquals(Long.MAX_VALUE,
            InterruptedSwarmEvidence.continuationGoal(before, Long.MAX_VALUE))
    }
}
