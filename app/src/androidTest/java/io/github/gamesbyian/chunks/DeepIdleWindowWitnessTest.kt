package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic observation sequences; no forced device idle or network changes. */
@RunWith(AndroidJUnit4::class)
class DeepIdleWindowWitnessTest {
    @Test fun steadyIdleWithAllSamplesCanBeReported() {
        val witness = DeepIdleWindowWitness()
        repeat(19) { witness.record(true) }
        assertTrue(witness.sustained(19))
        assertEquals(19, witness.samples)
        assertEquals(0, witness.nonIdleSamples)
    }

    @Test fun oneBriefExitInvalidatesTheEntireWindow() {
        val witness = DeepIdleWindowWitness()
        repeat(10) { witness.record(true) }
        witness.record(false)
        repeat(8) { witness.record(true) }
        assertFalse(witness.sustained(19))
        assertEquals(1, witness.nonIdleSamples)
    }

    @Test fun aPositiveShellForceIdleAcknowledgementCannotReplaceSampling() {
        val witness = DeepIdleWindowWitness()
        witness.record(true)
        assertFalse(witness.sustained(19))
    }

    @Test fun nonIdleAtStartOrEndRefusesAcceptance() {
        for (badSample in listOf(0, 18)) {
            val witness = DeepIdleWindowWitness()
            repeat(19) { witness.record(it != badSample) }
            assertFalse(witness.sustained(19))
        }
    }

    @Test fun extraOrMissingSamplesCannotCertifySpecifiedWindow() {
        val witness = DeepIdleWindowWitness()
        repeat(18) { witness.record(true) }
        assertFalse(witness.sustained(19))
        witness.record(true)
        witness.record(true)
        assertFalse(witness.sustained(19))
        assertFalse(witness.sustained(0))
    }
}
