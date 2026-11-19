package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner
import io.github.gamesbyian.chunks.engine.FinishInOrderRecord
import io.github.gamesbyian.chunks.engine.FinishInOrderRunner
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Finish in Order on each REAL protocol client through the shared workflow:
 * the planner's detected episode order, one journaled verified priority write
 * per step, the client's own readback as the authority, and no write at all
 * when nothing needs to change. Env-gated like SharedAddFileChoicesInteropTest.
 */
class ClientFinishInOrderInteropTest {
    @Test fun transmission() = journey(RemoteBackendKind.TRANSMISSION)
    @Test fun qbittorrent() = journey(RemoteBackendKind.QBITTORRENT)
    @Test fun deluge() = journey(RemoteBackendKind.DELUGE)

    private fun env(kind: RemoteBackendKind, name: String) =
        System.getenv("CHUNKS_INTEROP_" + kind.name + "_" + name).orEmpty()

    private fun journey(kind: RemoteBackendKind): Unit = runBlocking {
        assumeTrue(env(kind, "URL").isNotBlank() && env(kind, "PASS").isNotBlank())
        val credentials = RemoteCredentials(env(kind, "URL"), env(kind, "USER"), env(kind, "PASS"))
        val client: TorrentClient = when (kind) {
            RemoteBackendKind.TRANSMISSION -> TransmissionClient(credentials)
            RemoteBackendKind.QBITTORRENT -> QBittorrentClient(credentials)
            RemoteBackendKind.DELUGE -> DelugeClient(credentials, env(kind, "DAEMON_ID").ifBlank {
                DelugeReadOnlyRpc(credentials, "unselected").registeredDaemonIds().single()
            })
            RemoteBackendKind.RTORRENT -> error("rTorrent has its own Finish in Order port")
        }
        val ops = ClientOperations(client, 40, 500)
        val random = SecureRandom()
        val name = "chunks-fio-" + System.nanoTime().toString(36)
        // Deliberately not in metainfo order, so the detected episode order matters.
        val files = listOf("Show S01E03.bin", "Show S01E01.bin", "Show S01E02.bin")
            .map { it to ByteArray(24_000).also(random::nextBytes) }
        val torrent = TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(name,
            files.map { (f, b) -> TorrentMetainfoBuilder.Entry(listOf(f), b.size.toLong()) { b.inputStream() } },
            trackerTiers = listOf(listOf("http://127.0.0.1:1/announce")), pieceLength = 16 * 1024)).torrent
        val source = ClientAddSource.TorrentFile(torrent)
        val hash = source.infoHash
        try {
            val added = ops.add(ClientAddRequest(source, null, startNow = false))
            assertTrue("$added", added is ClientOperationOutcome.Verified)
            val session = ops.session()
            val storesLow = ClientFilePriority.LOW in session.filePriorities
            val clientFiles = ops.files(hash)
            val asPlanner = clientFiles.map(ClientFinishInOrderPort::asPlannerFile)
            val suggestion = requireNotNull(FinishInOrderPlanner.suggest(asPlanner)) { "episode order detected" }
            val byName = clientFiles.associate { it.path.substringAfterLast('/') to it.index }
            assertEquals("E01, E02, E03 regardless of metainfo order",
                listOf("Show S01E01.bin", "Show S01E02.bin", "Show S01E03.bin").map(byName::getValue),
                suggestion.order)

            val saved = mutableMapOf<String, FinishInOrderRecord>()
            val port = ClientFinishInOrderPort(ops, session.filePriorities) { h, r -> saved[h] = r; true }
            val policy = FinishInOrderPlanner.policyFor(FinishInOrderPlanner.Mode.BALANCED, asPlanner, suggestion.order)
            val first = FinishInOrderRunner(port).step(hash, FinishInOrderRecord(policy))
            println("CHUNKS_FIO: ${kind.name} first=${first::class.simpleName} low=$storesLow")
            assertTrue("$first", first is FinishInOrderRunner.Step.Applied)

            val after = ops.files(hash).associateBy { it.index }
            val (e1, e2, e3) = suggestion.order
            assertEquals("the current episode is High", ClientFilePriority.HIGH, after.getValue(e1).priority)
            assertEquals("the next one is lookahead Normal", ClientFilePriority.NORMAL, after.getValue(e2).priority)
            assertEquals("later episodes are Low only where the client stores Low",
                if (storesLow) ClientFilePriority.LOW else ClientFilePriority.NORMAL, after.getValue(e3).priority)

            val record = requireNotNull(saved[hash]) { "the runner journaled and recorded the step" }
            assertTrue("no pending journal after a verified write", record.pending.isEmpty())
            val second = FinishInOrderRunner(port).step(hash, record)
            println("CHUNKS_FIO: ${kind.name} second=${second::class.simpleName}")
            assertTrue("nothing left to change sends no write: $second", second is FinishInOrderRunner.Step.Idle)
            assertEquals(ClientRunState.STOPPED, client.torrent(hash)?.runState)
        } finally {
            val withData = runCatching { ClientCapability.REMOVE_WITH_DATA in ops.session().capabilities }.getOrDefault(false)
            runCatching { ops.remove(hash, deleteData = withData) }
        }
    }
}
