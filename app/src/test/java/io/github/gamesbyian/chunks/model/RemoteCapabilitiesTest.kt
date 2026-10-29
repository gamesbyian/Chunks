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


    @Test fun optionalDefaultDirectoryDoesNotDisqualifyExplicitDestinationAdd() {
        val graded = RemoteCapabilities.grade(RemoteProbe("0.16", all - "directory.default"))
            .associateBy { it.feature.label }
        assertEquals(RemoteCapabilities.Status.ADVERTISED,
            graded.getValue("Add magnet via XML-RPC").status)
        assertEquals(RemoteCapabilities.Status.ADVERTISED,
            graded.getValue("Add .torrent via XML-RPC").status)
        assertEquals(RemoteCapabilities.Status.MISSING,
            graded.getValue("Use server default Add folder").status)
    }

    @Test fun addRoutesAreIndependentAndRequireExactSessionLookup() {
        val missingMagnet = RemoteCapabilities.grade(RemoteProbe("0.16", all - "load.verbose"))
            .associateBy { it.feature.label }
        assertEquals(RemoteCapabilities.Status.MISSING,
            missingMagnet.getValue("Add magnet via XML-RPC").status)
        assertEquals(RemoteCapabilities.Status.ADVERTISED,
            missingMagnet.getValue("Add .torrent via XML-RPC").status)
        val noExactLookup = RemoteCapabilities.grade(RemoteProbe("0.16", all - "d.name"))
            .associateBy { it.feature.label }
        assertEquals(RemoteCapabilities.Status.MISSING,
            noExactLookup.getValue("Add magnet via XML-RPC").status)
        assertEquals(RemoteCapabilities.Status.MISSING,
            noExactLookup.getValue("Add .torrent via XML-RPC").status)
    }

    @Test fun removeRequiresExactHashReadbackInAdditionToEraseMethod() {
        val graded = RemoteCapabilities.grade(RemoteProbe("0.16", all - "d.hash"))
            .associateBy { it.feature.label }
        assertEquals(RemoteCapabilities.Status.MISSING,
            graded.getValue("Remove from server session").status)
        assertEquals(listOf("d.hash"), graded.getValue("Remove from server session").missing)
    }

    @Test fun missingActivityGetterCannotAdvertiseVerifiableLifecycle() {
        val graded = RemoteCapabilities.grade(RemoteProbe("0.16", all - "d.is_active"))
            .associateBy { it.feature.label }
        assertEquals(RemoteCapabilities.Status.MISSING,
            graded.getValue("Start and stop").status)
        assertEquals(listOf("d.is_active"), graded.getValue("Start and stop").missing)
    }

    @Test fun noIntrospectionMeansUnknownNotMissing() {
        val probe = RemoteProbe("0.9.6", null)
        val graded = RemoteCapabilities.grade(probe)
        assertTrue(graded.all { it.status == RemoteCapabilities.Status.UNKNOWN })
        assertTrue(RemoteCapabilities.summary(probe, graded).contains("cannot pre-check"))
    }
}
