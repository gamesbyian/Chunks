package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.AddFileChoices
import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Universal Add's reviewed file skips through the ONE shared workflow on each
 * REAL remote client: stopped Add → exact layout match → verified skip →
 * verified Start. Env-gated like RealClientInteropTest; prints no secrets.
 */
class SharedAddFileChoicesInteropTest {
    @Test fun transmission() = journey(RemoteBackendKind.TRANSMISSION)
    @Test fun qbittorrent() = journey(RemoteBackendKind.QBITTORRENT)
    @Test fun deluge() = journey(RemoteBackendKind.DELUGE)
    @Test fun rtorrent() = journey(RemoteBackendKind.RTORRENT)

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
            RemoteBackendKind.RTORRENT -> RTorrentClientAdapter(RTorrentRemote(credentials))
        }
        val ops = ClientOperations(client, 40, 500)
        val random = SecureRandom()
        val name = "chunks-filechoice-" + System.nanoTime().toString(36)
        val files = listOf("keep.bin" to ByteArray(40_000).also(random::nextBytes),
            "skip.bin" to ByteArray(30_000).also(random::nextBytes))
        val torrent = TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(name,
            files.map { (f, b) -> TorrentMetainfoBuilder.Entry(listOf(f), b.size.toLong()) { b.inputStream() } },
            trackerTiers = listOf(listOf("http://127.0.0.1:1/announce")), pieceLength = 16 * 1024)).torrent
        val reviewed = requireNotNull(AddFileChoices.declared(TorrentMetainfoReader.preview(torrent)))
        val skip = reviewed.single { it.path.endsWith("skip.bin") }
        val source = ClientAddSource.TorrentFile(torrent)
        try {
            val outcome = ops.add(ClientAddRequest(source, null, startNow = true, skippedFiles = mapOf(skip.index to skip.path)))
            println("CHUNKS_FILECHOICE: ${kind.name} ${outcome::class.simpleName}")
            assertTrue("$outcome", outcome is ClientOperationOutcome.Verified)
            val after = ops.files(source.infoHash).associateBy { it.index }
            assertEquals(ClientFilePriority.SKIP, after.getValue(skip.index).priority)
            assertTrue(after.values.filter { it.index != skip.index }.none { it.priority == ClientFilePriority.SKIP })
            assertEquals(true, client.torrent(source.infoHash)?.runState?.started)
        } finally {
            // This run's own unique fixture only: its (sparse) files go too where supported.
            val withData = runCatching { ClientCapability.REMOVE_WITH_DATA in ops.session().capabilities }.getOrDefault(false)
            runCatching { ops.remove(source.infoHash, deleteData = withData) }
        }
    }
}
