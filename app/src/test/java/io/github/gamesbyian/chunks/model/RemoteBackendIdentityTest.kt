package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

/**
 * The same torrent hash on two protocols is two separate Chunks jobs.
 * A read-only protocol's successful inventory must not turn unknown
 * operations into writable controls in the existing shared UI.
 */
class RemoteBackendIdentityTest {
    private val endpoint = "https://seedbox.example/client-a/RPC2"

    @Test fun newBackendsHaveDisjointStableIdsForTheSameEndpointAndPrincipal() {
        val id = RemoteBackendIdentity.of(RemoteBackendKind.TRANSMISSION, endpoint, "alice")
        assertEquals(id, RemoteBackendIdentity.of(RemoteBackendKind.TRANSMISSION, endpoint, "alice"))
        assertTrue(id.value.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(id, RemoteBackendIdentity.of(RemoteBackendKind.QBITTORRENT, endpoint, "alice"))
        assertNotEquals(id, RemoteBackendIdentity.of(RemoteBackendKind.DELUGE, endpoint, "alice", "host-one"))
        assertNotEquals(id, RemoteBackendIdentity.of(RemoteBackendKind.RTORRENT, endpoint, "alice"))
    }

    @Test fun preservesExistingRTorrentProfileIdentityExactly() {
        val existing = EnvironmentSnapshots.serverId(endpoint, " alice ")
        val newKey = RemoteBackendIdentity.of(RemoteBackendKind.RTORRENT, endpoint, " alice ")
        assertEquals(existing, newKey)
        assertNotEquals(newKey,
            RemoteBackendIdentity.of(RemoteBackendKind.RTORRENT, endpoint, "alice"))
    }

    @Test fun exactMountAndAccountCaseAndDelugeHostIdRemainPartOfIdentity() {
        val kind = RemoteBackendKind.TRANSMISSION
        val base = RemoteBackendIdentity.of(kind, endpoint, "alice")
        assertNotEquals(base, RemoteBackendIdentity.of(kind, endpoint.replace("/client-a/", "/client-b/"), "alice"))
        assertNotEquals(base, RemoteBackendIdentity.of(kind, endpoint, "Alice"))
        assertNotEquals(base, RemoteBackendIdentity.of(kind, endpoint, "alice "))
        assertNotEquals(
            RemoteBackendIdentity.of(RemoteBackendKind.DELUGE, endpoint, "", "host-one"),
            RemoteBackendIdentity.of(RemoteBackendKind.DELUGE, endpoint, "", "host-two")
        )
    }

    @Test fun malformedProtocolIdentityNeverBecomesAnotherValidProfile() {
        val invalidEndpoints = listOf(
            "http://public.example/RPC2",
            "https://alice:password@seedbox.example/RPC2",
            "https://seedbox.example/RPC2?token=private",
            " https://seedbox.example/RPC2",
            "https://seedbox.example/RPC2 "
        )
        invalidEndpoints.forEach { endpoint ->
            try {
                RemoteBackendIdentity.of(RemoteBackendKind.TRANSMISSION, endpoint, "alice")
                fail("Ambiguous remote client endpoint accepted")
            } catch (expected: IllegalArgumentException) {
                assertFalse(expected.message.orEmpty().contains("password"))
                assertFalse(expected.message.orEmpty().contains("private"))
            }
        }
        try {
            RemoteBackendIdentity.of(RemoteBackendKind.DELUGE, endpoint, "")
            fail("Deluge host identity missing")
        } catch (expected: IllegalArgumentException) { }
        try {
            RemoteBackendIdentity.of(RemoteBackendKind.QBITTORRENT, endpoint, "alice", "host-one")
            fail("Another client inherited Deluge host identity")
        } catch (expected: IllegalArgumentException) { }
    }

    @Test fun newReadOnlyClientsCannotInheritUnknownWritePermissions() {
        for (kind in listOf(RemoteBackendKind.TRANSMISSION, RemoteBackendKind.QBITTORRENT,
            RemoteBackendKind.DELUGE)) {
            val id = RemoteBackendIdentity.of(kind, endpoint, "alice",
                if (kind == RemoteBackendKind.DELUGE) "host-one" else null)
            val caps = NewRemoteReadOnlyCapabilities.forBackend(kind, id, 1_234L)
            assertEquals(CapabilityEvidence.UNKNOWN, caps.of(EnvironmentOperation.LIST))
            assertTrue(caps.decide(EnvironmentOperation.LIST, EnvironmentAvailability.LIVE).allowed)
            assertFalse(caps.decide(EnvironmentOperation.LIST, EnvironmentAvailability.OFFLINE).allowed)
            EnvironmentOperation.entries.filter { it != EnvironmentOperation.LIST }.forEach { op ->
                assertFalse(kind.name + ": " + op.name,
                    caps.decide(op, EnvironmentAvailability.LIVE).allowed)
                assertTrue(caps.of(op) == CapabilityEvidence.MISSING ||
                    caps.of(op) == CapabilityEvidence.NOT_APPLICABLE)
            }
        }
    }

    @Test fun readOnlySnapshotCannotImpersonateLocalOrRTorrentCapabilityEvidence() {
        val id = RemoteBackendIdentity.of(RemoteBackendKind.QBITTORRENT, endpoint, "alice")
        for (kind in listOf(RemoteBackendKind.TRANSMISSION, RemoteBackendKind.QBITTORRENT,
            RemoteBackendKind.DELUGE)) {
            try {
                NewRemoteReadOnlyCapabilities.forBackend(kind, EnvironmentSnapshots.LOCAL)
                fail("Remote capability attached to local")
            } catch (expected: IllegalArgumentException) { }
        }
        try {
            NewRemoteReadOnlyCapabilities.forBackend(RemoteBackendKind.RTORRENT, id)
            fail("Existing rTorrent capability mapping replaced")
        } catch (expected: IllegalArgumentException) { }
    }
}
