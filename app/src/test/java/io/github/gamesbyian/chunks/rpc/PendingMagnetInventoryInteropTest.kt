package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.model.RemoteProtocolReadOnlyClient
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A stopped magnet that has no metadata yet (dead tracker, no peers) must not
 * make the app's whole read-only inventory of that REAL client unavailable.
 * qBittorrent 4.6.3 reports total_size -1 for such a job, which previously
 * blanked every qBittorrent transfer in Combined. Env-gated like
 * RealClientInteropTest; uses the exact inspector the Transfers feed uses.
 */
class PendingMagnetInventoryInteropTest {
    @Test fun transmission() = journey(RemoteBackendKind.TRANSMISSION)
    @Test fun qbittorrent() = journey(RemoteBackendKind.QBITTORRENT)
    @Test fun deluge() = journey(RemoteBackendKind.DELUGE)

    private fun env(kind: RemoteBackendKind, name: String) =
        System.getenv("CHUNKS_INTEROP_" + kind.name + "_" + name).orEmpty()

    private fun journey(kind: RemoteBackendKind): Unit = runBlocking {
        assumeTrue(env(kind, "URL").isNotBlank() && env(kind, "PASS").isNotBlank())
        val credentials = RemoteCredentials(env(kind, "URL"), env(kind, "USER"), env(kind, "PASS"))
        val daemon = if (kind == RemoteBackendKind.DELUGE) env(kind, "DAEMON_ID").ifBlank {
            DelugeReadOnlyRpc(credentials, "unselected").registeredDaemonIds().single()
        } else null
        val ops = ClientOperations(RemoteTorrentClients.create(kind, credentials, daemon), 20, 500)
        val hash = ByteArray(20).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val magnet = ClientAddSource.Magnet("magnet:?xt=urn:btih:$hash&dn=chunks-pending&tr=http%3A%2F%2F127.0.0.1%3A1%2Fannounce")
        val added = ops.add(ClientAddRequest(magnet, null, startNow = false))
        try {
            println("CHUNKS_PENDING: ${kind.name} add=${added::class.simpleName}")
            assertTrue("$added", added !is ClientOperationOutcome.Refused)
            val read = RemoteProtocolReadOnlyClient().inspect(
                RemoteProtocolReadOnlyClient.Configuration(kind, credentials, daemon))
            val row = read.snapshot.transfers.firstOrNull { it.key.infoHash == hash }
            println("CHUNKS_PENDING: ${kind.name} inventory=${read.snapshot.transfers.size} pendingListed=${row != null}")
            assertTrue("${kind.name}: the pending magnet is listed in a readable inventory", row != null)
        } finally {
            runCatching { ops.remove(hash, deleteData = false) }
        }
    }
}
