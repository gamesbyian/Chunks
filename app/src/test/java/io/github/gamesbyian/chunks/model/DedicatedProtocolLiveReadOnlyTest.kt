package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.DelugeReadOnlyRpc
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Actual dedicated development Seedhost protocol acceptance, READ ONLY.
 *
 * Ordinary PR checks SKIP all tests. A manually dispatched trusted-main
 * workflow sets exactly one backend and obtains its credentials from GitHub
 * Secrets. Never print endpoints, credentials, torrent identities, names,
 * paths, tracker URLs, request bodies or cookies.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DedicatedProtocolLiveReadOnlyTest {
    private fun selected(kind: RemoteBackendKind): RemoteProtocolReadOnlyClient.Configuration {
        val selected = System.getenv("CHUNKS_PROTOCOL_LIVE_BACKEND").orEmpty()
        assumeTrue("Only the owner-authorized manual live workflow", selected == kind.name)
        val url = System.getenv("CHUNKS_PROTOCOL_LIVE_URL").orEmpty()
        val pass = System.getenv("CHUNKS_PROTOCOL_LIVE_PASS").orEmpty()
        val user = System.getenv("CHUNKS_PROTOCOL_LIVE_USER").orEmpty()
        val daemon = System.getenv("CHUNKS_PROTOCOL_LIVE_DAEMON_ID").orEmpty()
        require(url.isNotBlank() && pass.isNotBlank()) {
            "Live protocol fixtures are not configured; no network attempted"
        }
        val uri = try { URI(url) } catch (_: Exception) {
            throw IllegalArgumentException("Invalid live protocol fixture URL")
        }
        require(uri.scheme.equals("https", true) &&
            (uri.host?.equals("seedhost.eu", true) == true ||
                uri.host?.endsWith(".seedhost.eu", true) == true) &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "Live protocol fixture must use a credential-free HTTPS Seedhost URL"
        }
        val credentials = RemoteCredentials(url, user, pass)
        val daemonId = if (kind != RemoteBackendKind.DELUGE) null
        else daemon.ifBlank {
            // No configured ID: adopt one only when the Web UI registers
            // exactly one daemon. Several daemons remain an explicit choice.
            val registered = runBlocking {
                DelugeReadOnlyRpc(credentials, "unselected").registeredDaemonIds()
            }
            require(registered.size == 1) {
                "Deluge fixture must select one exact registered daemon (found " +
                    registered.size + ")"
            }
            println("CHUNKS_PROTOCOL_LIVE: deluge_daemon=discovered_single")
            registered.single()
        }
        return RemoteProtocolReadOnlyClient.Configuration(kind, credentials, daemonId)
    }

    private suspend fun verify(kind: RemoteBackendKind) {
        val config = selected(kind)
        val start = System.nanoTime()
        val receipt = RemoteProtocolReadOnlyClient().inspect(config)
        assertEquals(kind, receipt.backend)
        assertEquals(
            RemoteBackendIdentity.of(kind, config.credentials.endpoint,
                config.credentials.username, config.delugeDaemonHostId),
            receipt.environment
        )
        assertTrue(receipt.snapshot.canClaimCompleteLiveInventory)
        assertTrue(receipt.reportedVersion.isNotBlank())
        assertEquals(receipt.environment, receipt.capabilities.environment)
        assertTrue(receipt.capabilities.decide(
            EnvironmentOperation.LIST, EnvironmentAvailability.LIVE).allowed)
        EnvironmentOperation.entries.filter { it != EnvironmentOperation.LIST }.forEach {
            assertFalse("Read-only fixture must never authorize " + it.name,
                receipt.capabilities.decide(it, EnvironmentAvailability.LIVE).allowed)
        }
        val rows = receipt.snapshot.transfers
        assertEquals(rows.map { it.key }.distinct().size, rows.size)
        assertTrue(rows.all { it.key.environment == receipt.environment })
        assertTrue(rows.all { it.sizeBytes == null || it.sizeBytes >= 0L })
        assertTrue(rows.all { it.progressPpm == null || it.progressPpm in 0..1_000_000 })
        assertTrue(rows.all { it.downloadBytesPerSecond == null || it.downloadBytesPerSecond >= 0L })
        val elapsed = (System.nanoTime() - start) / 1_000_000L
        println("CHUNKS_PROTOCOL_LIVE: accepted backend=" + kind.name +
            " complete=true transfers=" + rows.size + " duration_ms=" + elapsed +
            " capabilities=read_only")
    }

    @Test fun actualTransmissionInventoryCannotClaimAWriteCapability() = runBlocking {
        verify(RemoteBackendKind.TRANSMISSION)
    }

    @Test fun actualQBittorrentInventoryCannotClaimAWriteCapability() = runBlocking {
        verify(RemoteBackendKind.QBITTORRENT)
    }

    @Test fun actualDelugeDaemonInventoryCannotClaimAWriteCapability() = runBlocking {
        verify(RemoteBackendKind.DELUGE)
    }
}
