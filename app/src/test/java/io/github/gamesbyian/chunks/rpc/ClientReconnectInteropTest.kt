package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * One long-lived client per REAL daemon survives that daemon restarting
 * underneath it (qBittorrent forgets its SID, Deluge's Web UI loses its daemon
 * connection, Transmission issues a new session id): after the restart the
 * SAME instance reads, starts and removes the exact job it added before.
 *
 * Manual rig only: set CHUNKS_RECONNECT_SIGNAL_DIR; the test writes
 * `ready-<KIND>` and waits for `restarted-<KIND>` (an operator or script
 * restarts the daemons in between). Never runs in ordinary CI.
 */
class ClientReconnectInteropTest {
    @Test fun survivesDaemonRestarts(): Unit = runBlocking {
        val signals = System.getenv("CHUNKS_RECONNECT_SIGNAL_DIR").orEmpty()
        assumeTrue(signals.isNotBlank())
        val kinds = RemoteBackendKind.entries.filter { env(it, "URL").isNotBlank() && env(it, "PASS").isNotBlank() }
        val prepared = kinds.map { it to prepare(it) }
        File(signals, "ready").writeText(kinds.joinToString(","))
        val deadline = System.currentTimeMillis() + 300_000
        while (!File(signals, "restarted").exists()) {
            check(System.currentTimeMillis() < deadline) { "No restart signal" }
            delay(500)
        }
        for ((kind, job) in prepared) {
            val (ops, hash) = job
            println("CHUNKS_RECONNECT: ${kind.name} after restart")
            var present: ClientTorrent? = null
            // The daemon may still be coming back: reads only, bounded.
            for (i in 0 until 60) {
                present = runCatching { ops.client.torrent(hash) }.getOrNull()
                if (present != null) break
                delay(1_000)
            }
            assertTrue("${kind.name}: same instance re-read the exact job", present != null)
            val started = ops.setStarted(hash, true)
            println("CHUNKS_RECONNECT: ${kind.name} start=${started::class.simpleName}")
            assertTrue("${kind.name}: $started", started is ClientOperationOutcome.Verified)
            val removed = ops.remove(hash, deleteData = false)
            println("CHUNKS_RECONNECT: ${kind.name} remove=${removed::class.simpleName}")
            assertTrue("${kind.name}: $removed", removed is ClientOperationOutcome.Verified)
            assertNull(ops.client.torrent(hash))
        }
    }

    private fun env(kind: RemoteBackendKind, name: String) =
        System.getenv("CHUNKS_INTEROP_" + kind.name + "_" + name).orEmpty()

    private suspend fun prepare(kind: RemoteBackendKind): Pair<ClientOperations, String> {
        val credentials = RemoteCredentials(env(kind, "URL"), env(kind, "USER"), env(kind, "PASS"))
        val client: TorrentClient = when (kind) {
            RemoteBackendKind.TRANSMISSION -> TransmissionClient(credentials)
            RemoteBackendKind.QBITTORRENT -> QBittorrentClient(credentials)
            RemoteBackendKind.DELUGE -> DelugeClient(credentials, env(kind, "DAEMON_ID").ifBlank {
                DelugeReadOnlyRpc(credentials, "unselected").registeredDaemonIds().single()
            })
            RemoteBackendKind.RTORRENT -> RTorrentClientAdapter(RTorrentRemote(credentials))
        }
        val ops = ClientOperations(client, 40, 500)
        val bytes = ByteArray(50_000).also(SecureRandom()::nextBytes)
        val torrent = TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(
            "chunks-reconnect-" + System.nanoTime().toString(36),
            listOf(TorrentMetainfoBuilder.Entry(listOf("x.bin"), bytes.size.toLong()) { bytes.inputStream() }),
            trackerTiers = listOf(listOf("http://127.0.0.1:1/announce")), pieceLength = 16 * 1024)).torrent
        val source = ClientAddSource.TorrentFile(torrent)
        val added = ops.add(ClientAddRequest(source, null, startNow = false))
        println("CHUNKS_RECONNECT: ${kind.name} before restart add=${added::class.simpleName}")
        assertTrue("${kind.name}: $added", added is ClientOperationOutcome.Verified)
        return ops to source.infoHash
    }
}
