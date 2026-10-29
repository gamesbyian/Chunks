package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.RemoteProbe
import org.junit.Assert.*
import org.junit.Test

class RemoteCapabilitiesTest {
    private val all = RemoteCapabilities.features.flatMap { it.methods }.toSet()

    @Test fun everyFeatureAdvertisedWhenAllMethodsListed() {
        val graded = RemoteCapabilities.grade(RemoteProbe("0.9.8", all + "extra.method"))
        assertTrue(graded.all { it.status == RemoteCapabilities.Status.ADVERTISED })
        assertTrue(RemoteCapabilities.summary(RemoteProbe("0.9.8", all), graded).contains("advertises every method"))
    }

    @Test fun missingMethodsAreNamedPerFeature() {
        val graded = RemoteCapabilities.grade(RemoteProbe("0.9.8", all - "d.erase" - "d.bitfield"))
        val byLabel = graded.associateBy { it.feature.label }
        assertEquals(listOf("d.erase"), byLabel.getValue("Remove from server session").missing)
        assertEquals(RemoteCapabilities.Status.MISSING, byLabel.getValue("Finish in Order").status)
        assertEquals(RemoteCapabilities.Status.ADVERTISED, byLabel.getValue("Transfer list").status)
    }

    @Test fun noIntrospectionMeansUnknownNotMissing() {
        val probe = RemoteProbe("0.9.6", null)
        val graded = RemoteCapabilities.grade(probe)
        assertTrue(graded.all { it.status == RemoteCapabilities.Status.UNKNOWN })
        assertTrue(RemoteCapabilities.summary(probe, graded).contains("cannot pre-check"))
    }
}
