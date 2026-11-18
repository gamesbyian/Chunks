package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.DelugeReadOnlyRpc
import io.github.gamesbyian.chunks.rpc.QBittorrentReadOnlyRpc
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.TransmissionReadOnlyRpc
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteProtocolReadOnlyClientTest {
    private val hash = "a".repeat(40)
    private val base = "https://seedbox.example/tenant-a/"
    private fun credentials(path: String = "client/rpc", username: String = "alice") =
        RemoteCredentials(base + path, username, "private-password")
    private fun config(
        backend: RemoteBackendKind, path: String = "client/rpc",
        username: String = "alice", daemon: String? = null
    ) = RemoteProtocolReadOnlyClient.Configuration(backend, credentials(path, username), daemon)

    private fun transmission(challenged: Boolean) =
        RemoteProtocolReadOnlyClient.ProtocolResponse.Transmission(
            TransmissionReadOnlyRpc.Snapshot(
                "4.1.0", 18, TransmissionReadOnlyRpc.Dialect.JSON_RPC_2,
                listOf(TransmissionReadOnlyRpc.Torrent(
                    hash, 8L, "Example", 200L, 50L, "/remote",
                    4, 17L, 3L
                )), challenged
            )
        )

    private fun qbittorrent() = RemoteProtocolReadOnlyClient.ProtocolResponse.QBittorrent(
        QBittorrentReadOnlyRpc.Snapshot("5.0.1", "2.12",
            listOf(QBittorrentReadOnlyRpc.Torrent(
                hash, "Example", "downloading", 200L, 300L, 100L,
                12L, 1L, "/remote-qb"
            )))
    )

    private fun deluge(daemon: String) = RemoteProtocolReadOnlyClient.ProtocolResponse.Deluge(
        DelugeReadOnlyRpc.Snapshot(daemon, "2.1.1", listOf(
            DelugeReadOnlyRpc.Torrent(hash, "Example", "Paused", 100L, 0L, null, null, "/remote-deluge")
        ))
    )

    @Test fun transmissionRequiresAcceptedHttpAccountChallengeBeforeAnyLiveInventory() = runBlocking {
        var calls = 0
        val input = config(RemoteBackendKind.TRANSMISSION)
        val rejected = RemoteProtocolReadOnlyClient(
            RemoteProtocolReadOnlyClient.Reader { calls++; transmission(false) }, { 1_000L }
        )
        try { rejected.inspect(input); fail("Unchallenged HTTP 200 became an authenticated client") }
        catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("did not authenticate"))
        }
        assertEquals(1, calls)

        val accepted = RemoteProtocolReadOnlyClient(
            RemoteProtocolReadOnlyClient.Reader { transmission(true) }, { 1_000L }
        ).inspect(input)
        assertEquals(RemoteBackendKind.TRANSMISSION, accepted.backend)
        assertEquals("4.1.0", accepted.reportedVersion)
        assertEquals(1, accepted.snapshot.transfers.size)
        assertEquals(250_000, accepted.snapshot.transfers.single().progressPpm)
        assertTrue(accepted.snapshot.canClaimCompleteLiveInventory)
        assertEquals(1_000L, accepted.snapshot.observedAtEpochMillis)
        assertEquals(accepted.environment,
            RemoteBackendIdentity.of(RemoteBackendKind.TRANSMISSION, input.credentials.endpoint, "alice"))
    }

    @Test fun loginWithCookieCanReadQBittorrentWithoutInventingWriteCapabilities() = runBlocking {
        val input = config(RemoteBackendKind.QBITTORRENT, "qbittorrent/")
        val verified = RemoteProtocolReadOnlyClient(
            RemoteProtocolReadOnlyClient.Reader { qbittorrent() }, { 50_000L }
        ).inspect(input)
        assertEquals("5.0.1", verified.reportedVersion)
        assertEquals(500_000, verified.snapshot.transfers.single().progressPpm)
        assertTrue(verified.snapshot.canClaimCompleteLiveInventory)
        assertEquals(verified.environment, verified.capabilities.environment)
        assertEquals(CapabilityEvidence.UNKNOWN, verified.capabilities.of(EnvironmentOperation.LIST))
        assertTrue(verified.capabilities.decide(EnvironmentOperation.LIST, EnvironmentAvailability.LIVE).allowed)
        assertTrue(EnvironmentOperation.entries.filter { it != EnvironmentOperation.LIST }.all {
            !verified.capabilities.decide(it, EnvironmentAvailability.LIVE).allowed
        })
        assertFalse(verified.capabilities.decide(EnvironmentOperation.LIST, EnvironmentAvailability.OFFLINE).allowed)
        assertFalse(verified.toString().contains("private-password"))
    }

    @Test fun delugeMustReturnRequestedDaemonAndNeverInheritAnotherDaemonInventory() = runBlocking {
        val input = config(RemoteBackendKind.DELUGE, "deluge/json", username = "", daemon = "daemon-a")
        val wrong = RemoteProtocolReadOnlyClient(RemoteProtocolReadOnlyClient.Reader { deluge("daemon-b") })
        try { wrong.inspect(input); fail("Wrong daemon inherited session") }
        catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("different daemon"))
        }
        val accepted = RemoteProtocolReadOnlyClient(
            RemoteProtocolReadOnlyClient.Reader { deluge("daemon-a") }, { 200_000L }
        ).inspect(input)
        assertEquals("2.1.1", accepted.reportedVersion)
        assertEquals(0, accepted.snapshot.transfers.single().progressPpm)
        assertEquals(false, accepted.snapshot.transfers.single().started)
    }

    @Test fun wrongVendorResponseCannotGrantLiveSessionToConfiguredClient() = runBlocking {
        for (kind in listOf(RemoteBackendKind.TRANSMISSION, RemoteBackendKind.QBITTORRENT,
            RemoteBackendKind.DELUGE)) {
            val reader = RemoteProtocolReadOnlyClient.Reader { qbittorrent() }
            val input = config(kind, daemon = if (kind == RemoteBackendKind.DELUGE) "daemon-a" else null)
            if (kind == RemoteBackendKind.QBITTORRENT) continue
            try { RemoteProtocolReadOnlyClient(reader).inspect(input); fail("Wrong protocol accepted") }
            catch (expected: IOException) { assertTrue(expected.message.orEmpty().contains("another protocol")) }
        }
    }

    @Test fun malformedBackendIdentityAndLegacyRTorrentAreRefusedBeforeAnyNetworkAccess() = runBlocking {
        var attempted = false
        val client = RemoteProtocolReadOnlyClient(RemoteProtocolReadOnlyClient.Reader {
            attempted = true
            qbittorrent()
        })
        val refused = listOf(
            config(RemoteBackendKind.RTORRENT),
            config(RemoteBackendKind.DELUGE),
            config(RemoteBackendKind.QBITTORRENT, username = "bad\nprincipal"),
            RemoteProtocolReadOnlyClient.Configuration(RemoteBackendKind.QBITTORRENT,
                RemoteCredentials("https://user:private-password@seedbox.example/client/", "alice", "secret"))
        )
        for (input in refused) {
            try { client.inspect(input); fail("Invalid connection reached transport") }
            catch (expected: IllegalArgumentException) { assertFalse(expected.message.orEmpty().contains("private-password")) }
        }
        assertFalse(attempted)
    }

    @Test fun invalidClockAndEmptyButVerifiedInventoryDoNotFabricateProgress() = runBlocking {
        val input = config(RemoteBackendKind.QBITTORRENT)
        val empty = RemoteProtocolReadOnlyClient.ProtocolResponse.QBittorrent(
            QBittorrentReadOnlyRpc.Snapshot("5.0", "2.12", emptyList()))
        val wrongTime = RemoteProtocolReadOnlyClient(
            RemoteProtocolReadOnlyClient.Reader { empty }, { -1L })
        try { wrongTime.inspect(input); fail("Invalid freshness accepted") }
        catch (expected: IOException) { assertTrue(expected.message.orEmpty().contains("timestamp")) }

        val accepted = RemoteProtocolReadOnlyClient(
            RemoteProtocolReadOnlyClient.Reader { empty }, { 2L }).inspect(input)
        assertEquals(emptyList<UnifiedTransferObservation>(), accepted.snapshot.transfers)
        assertTrue(accepted.snapshot.canClaimCompleteLiveInventory)
        assertEquals(2L, accepted.snapshot.observedAtEpochMillis)
        assertEquals(CapabilityEvidence.MISSING, accepted.capabilities.of(EnvironmentOperation.SESSION_REMOVE))
    }

    @Test fun clientIdentityNeverCollapsesSameTorrentBetweenDifferentProtocolSessions() = runBlocking {
        val transmission = RemoteProtocolReadOnlyClient(
            RemoteProtocolReadOnlyClient.Reader { transmission(true) }, { 20L })
            .inspect(config(RemoteBackendKind.TRANSMISSION))
        val qb = RemoteProtocolReadOnlyClient(
            RemoteProtocolReadOnlyClient.Reader { qbittorrent() }, { 20L })
            .inspect(config(RemoteBackendKind.QBITTORRENT))
        val deluge = RemoteProtocolReadOnlyClient(
            RemoteProtocolReadOnlyClient.Reader { deluge("daemon-a") }, { 20L })
            .inspect(config(RemoteBackendKind.DELUGE, daemon = "daemon-a"))
        val rows = listOf(transmission, qb, deluge).flatMap { it.snapshot.transfers }
        assertEquals(setOf(hash), rows.map { it.key.infoHash }.toSet())
        assertEquals(3, rows.map { it.key }.toSet().size)
        assertEquals(3, rows.map { it.key.environment }.toSet().size)
    }
}
