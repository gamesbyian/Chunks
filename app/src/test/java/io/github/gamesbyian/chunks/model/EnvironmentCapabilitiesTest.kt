package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.RemoteProbe
import org.junit.Assert.*
import org.junit.Test

class EnvironmentCapabilitiesTest {
    private val serverA = EnvironmentId("a".repeat(64))
    private val serverB = EnvironmentId("b".repeat(64))
    private val all = RemoteCapabilities.features.flatMap { it.methods }.toSet()

    @Test fun everyRemoteFeatureMapsToOneOperation() {
        assertEquals(EnvironmentOperation.entries.toSet(),
            RemoteCapabilities.features.mapNotNull { it.operation }.toSet())
        assertEquals(RemoteCapabilities.features.size,
            RemoteCapabilities.features.mapNotNull { it.operation }.toSet().size)
    }

    @Test fun phoneIsBuiltInExceptCopyToPhone() {
        val phone = EnvironmentCapabilities.phone()
        assertEquals(CapabilityEvidence.NOT_APPLICABLE, phone.of(EnvironmentOperation.COPY_TO_PHONE))
        assertEquals(CapabilityEvidence.NOT_APPLICABLE, phone.of(EnvironmentOperation.SERVER_DEFAULT_ADD_FOLDER))
        assertEquals(CapabilityEvidence.BUILT_IN, phone.of(EnvironmentOperation.ADD_MAGNET))
        assertTrue(phone.decide(EnvironmentOperation.START_STOP, EnvironmentAvailability.LIVE).allowed)
        assertFalse(phone.decide(EnvironmentOperation.COPY_TO_PHONE, EnvironmentAvailability.LIVE).allowed)
    }

    @Test fun builtInPhoneEvidenceCannotBeRetargetedToRemoteProfile() {
        for (remoteId in listOf(serverA, serverB)) {
            try {
                EnvironmentCapabilities.phone(remoteId)
                fail("A remote server cannot inherit the phone engine's built-in features")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun forgedBuildFactsAndServerAdvertisementsCannotAuthorizeWrongEngine() {
        val borrowedPhone = EnvironmentCapabilitySnapshot(serverA, "phone engine", 10,
            mapOf(EnvironmentOperation.START_STOP to CapabilityEvidence.BUILT_IN))
        val fakeRemote = EnvironmentCapabilitySnapshot(EnvironmentSnapshots.PHONE, "rTorrent test", 10,
            mapOf(EnvironmentOperation.START_STOP to CapabilityEvidence.ADVERTISED))
        for (snapshot in listOf(borrowedPhone, fakeRemote)) {
            val decision = snapshot.decide(EnvironmentOperation.START_STOP, EnvironmentAvailability.LIVE)
            assertFalse(decision.allowed)
            assertTrue(decision.explanation.contains("engine"))
        }
        val ordinaryServer = EnvironmentCapabilities.fromProbe(serverA,
            RemoteProbe("0.9.8", all), 10)
        assertTrue(ordinaryServer.decide(EnvironmentOperation.START_STOP, EnvironmentAvailability.LIVE).allowed)
    }

    @Test fun eachServerIsGradedFromItsOwnProbeOnly() {
        val a = EnvironmentCapabilities.fromProbe(serverA, RemoteProbe("0.9.8", all - "d.erase"), 5)
        val b = EnvironmentCapabilities.fromProbe(serverB, RemoteProbe("0.15.1", all), 6)
        assertEquals(CapabilityEvidence.MISSING, a.of(EnvironmentOperation.SESSION_REMOVE))
        assertEquals(CapabilityEvidence.ADVERTISED, b.of(EnvironmentOperation.SESSION_REMOVE))
        assertEquals(CapabilityEvidence.ADVERTISED, a.of(EnvironmentOperation.START_STOP))
        val refused = a.decide(EnvironmentOperation.SESSION_REMOVE, EnvironmentAvailability.LIVE)
        assertFalse(refused.allowed)
        assertTrue(refused.explanation.contains("rTorrent 0.9.8"))
        try {
            EnvironmentCapabilities.fromProbe(EnvironmentSnapshots.PHONE, RemoteProbe("x", all), 1)
            fail("a server probe described the phone")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun missingOptionalDefaultFolderDoesNotRefuseEitherAddRoute() {
        val snapshot = EnvironmentCapabilities.fromProbe(serverA, RemoteProbe("0.9.8", all - "directory.default"), 1)
        assertEquals(CapabilityEvidence.MISSING, snapshot.of(EnvironmentOperation.SERVER_DEFAULT_ADD_FOLDER))
        for (route in listOf(EnvironmentOperation.ADD_MAGNET, EnvironmentOperation.ADD_TORRENT_FILE)) {
            assertEquals(CapabilityEvidence.ADVERTISED, snapshot.of(route))
            assertTrue(snapshot.decide(route, EnvironmentAvailability.LIVE).allowed)
        }
        // Each Add route is graded on its own load method.
        val noRaw = EnvironmentCapabilities.fromProbe(serverA, RemoteProbe("0.9.8", all - "load.raw_verbose"), 1)
        assertEquals(CapabilityEvidence.ADVERTISED, noRaw.of(EnvironmentOperation.ADD_MAGNET))
        assertEquals(CapabilityEvidence.MISSING, noRaw.of(EnvironmentOperation.ADD_TORRENT_FILE))
    }

    @Test fun sessionRemoveNeedsExactHashReadbackNotJustErase() {
        val noHash = EnvironmentCapabilities.fromProbe(serverA, RemoteProbe("0.9.8", all - "d.hash"), 1)
        assertEquals(CapabilityEvidence.MISSING, noHash.of(EnvironmentOperation.SESSION_REMOVE))
        assertFalse(noHash.decide(EnvironmentOperation.SESSION_REMOVE, EnvironmentAvailability.LIVE).allowed)
        val eraseOnly = EnvironmentCapabilities.fromProbe(serverA, RemoteProbe("0.9.8", setOf("d.erase")), 1)
        assertEquals(CapabilityEvidence.MISSING, eraseOnly.of(EnvironmentOperation.SESSION_REMOVE))
    }

    @Test fun oneServerWithoutActivityGetterCannotPrecheckVerifiableStartStop() {
        val missing = EnvironmentCapabilities.fromProbe(serverA,
            RemoteProbe("0.16", all - "d.is_active"), 7)
        val complete = EnvironmentCapabilities.fromProbe(serverB,
            RemoteProbe("0.16", all), 8)
        assertEquals(CapabilityEvidence.MISSING, missing.of(EnvironmentOperation.START_STOP))
        assertFalse(missing.decide(EnvironmentOperation.START_STOP, EnvironmentAvailability.LIVE).allowed)
        assertEquals(CapabilityEvidence.ADVERTISED, complete.of(EnvironmentOperation.START_STOP))
        assertTrue(complete.decide(EnvironmentOperation.START_STOP, EnvironmentAvailability.LIVE).allowed)
        // A server with unavailable introspection stays UNKNOWN, never missing.
        assertEquals(CapabilityEvidence.UNKNOWN, EnvironmentCapabilities.fromProbe(serverA,
            RemoteProbe("0.16", null), 9).of(EnvironmentOperation.START_STOP))
    }

    @Test fun optionalPeerAddressFallbackAndMandatoryIdentityGettersAreGradedIndependently() {
        val optionalAbsent = EnvironmentCapabilities.fromProbe(serverA,
            RemoteProbe("0.16", all - "network.bind_address" - "network.local_address"), 10)
        assertEquals(CapabilityEvidence.ADVERTISED,
            optionalAbsent.of(EnvironmentOperation.COPY_TO_PHONE))
        val missingHash = EnvironmentCapabilities.fromProbe(serverB,
            RemoteProbe("0.16", all - "d.hash"), 11)
        assertEquals(CapabilityEvidence.MISSING,
            missingHash.of(EnvironmentOperation.TRACKER_HEALTH))
        assertEquals(CapabilityEvidence.MISSING,
            missingHash.of(EnvironmentOperation.FINISH_IN_ORDER))
    }

    @Test fun unknownIsNeverMissingAndUnavailableEnvironmentsRefuse() {
        val noList = EnvironmentCapabilities.fromProbe(serverA, RemoteProbe("0.9.6", null), 1)
        val unprobed = EnvironmentCapabilities.unprobed(serverA)
        for (snapshot in listOf(noList, unprobed)) {
            assertEquals(CapabilityEvidence.UNKNOWN, snapshot.of(EnvironmentOperation.START_STOP))
            val decision = snapshot.decide(EnvironmentOperation.START_STOP, EnvironmentAvailability.LIVE)
            assertTrue(decision.allowed)
            assertTrue(decision.explanation.contains("not pre-checked"))
        }
        val phone = EnvironmentCapabilities.phone()
        for (state in listOf(EnvironmentAvailability.OFFLINE, EnvironmentAvailability.LOCKED,
            EnvironmentAvailability.STALE)) {
            assertFalse(phone.decide(EnvironmentOperation.START_STOP, state).allowed)
        }
    }
}
