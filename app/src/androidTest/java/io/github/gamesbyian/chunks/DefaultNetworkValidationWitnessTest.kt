package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Pure negative controls: a toggle acknowledgement is not a network outage. */
@RunWith(AndroidJUnit4::class)
class DefaultNetworkValidationWitnessTest {
    private val P = DefaultNetworkValidationWitness.Phase

    @Test fun stableValidatedOutageAndReturnAreRequiredInOrder() {
        val gate = DefaultNetworkValidationWitness()
        assertEquals(P.NEED_BASELINE, gate.observe(true))
        assertEquals(P.NEED_LOSS, gate.observe(true))
        assertEquals(P.NEED_LOSS, gate.observe(false))
        assertEquals(P.NEED_RETURN, gate.observe(false))
        assertEquals(P.NEED_RETURN, gate.observe(true))
        assertEquals(P.COMPLETE, gate.observe(true))
        assertEquals(P.COMPLETE, gate.observe(false))
    }

    @Test fun permanentlyValidatedDefaultNetworkNeverCertifiesRadioOutage() {
        val gate = DefaultNetworkValidationWitness()
        repeat(20) { gate.observe(true) }
        assertEquals(P.NEED_LOSS, gate.phase)
    }

    @Test fun initialDisconnectionCannotBeMisclassifiedAsOutage() {
        val gate = DefaultNetworkValidationWitness()
        repeat(20) { gate.observe(false) }
        assertEquals(P.NEED_BASELINE, gate.phase)
    }

    @Test fun oneTransientOfflineSampleDoesNotCertifyLoss() {
        val gate = DefaultNetworkValidationWitness()
        repeat(2) { gate.observe(true) }
        gate.observe(false)
        gate.observe(true)
        assertEquals(P.NEED_LOSS, gate.phase)
    }

    @Test fun permanentOutageCannotBeReportedAsSuccessfulRecovery() {
        val gate = DefaultNetworkValidationWitness()
        repeat(2) { gate.observe(true) }
        repeat(20) { gate.observe(false) }
        assertEquals(P.NEED_RETURN, gate.phase)
    }
}
