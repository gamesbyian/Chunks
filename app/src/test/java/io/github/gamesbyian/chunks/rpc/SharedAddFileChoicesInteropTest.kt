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
 * Universal Add's reviewed file skips and Low/High through the ONE shared workflow on each
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
            "skip.bin" to ByteArray(30_000).also(random::nextBytes),
            "low.bin" to ByteArray(20_000).also(random::nextBytes))
        val torrent = TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(name,
            files.map { (f, b) -> TorrentMetainfoBuilder.Entry(listOf(f), b.size.toLong()) { b.inputStream() } },
            trackerTiers = listOf(listOf("http://127.0.0.1:1/announce")), pieceLength = 16 * 1024)).torrent
        val reviewed = requireNotNull(AddFileChoices.declared(TorrentMetainfoReader.preview(torrent)))
        val skip = reviewed.single { it.path.endsWith("skip.bin") }
        val high = reviewed.single { it.path.endsWith("keep.bin") }
        val low = reviewed.single { it.path.endsWith("low.bin") }
        // Low only where this client really stores it (never a silent Low -> Normal).
        val storesLow = ClientFilePriority.LOW in ops.session().filePriorities
        val levels = buildMap {
            put(high.index, ReviewedFilePriority(high.path, ClientFilePriority.HIGH))
            if (storesLow) put(low.index, ReviewedFilePriority(low.path, ClientFilePriority.LOW))
        }
        val source = ClientAddSource.TorrentFile(torrent)
        try {
            val outcome = ops.add(ClientAddRequest(source, null, startNow = true,
                skippedFiles = mapOf(skip.index to skip.path), filePriorities = levels))
            println("CHUNKS_FILECHOICE: ${kind.name} ${outcome::class.simpleName} low=${storesLow}")
            assertTrue("$outcome", outcome is ClientOperationOutcome.Verified)
            val after = ops.files(source.infoHash).associateBy { it.index }
            assertEquals(ClientFilePriority.SKIP, after.getValue(skip.index).priority)
            assertEquals(ClientFilePriority.HIGH, after.getValue(high.index).priority)
            assertEquals(if (storesLow) ClientFilePriority.LOW else ClientFilePriority.NORMAL,
                after.getValue(low.index).priority)
            assertEquals(true, client.torrent(source.infoHash)?.runState?.started)
            assertReviewedFilePriorityConformance(ops, source.infoHash, low.index)
        } finally {
            // This run's own unique fixture only: its (sparse) files go too where supported.
            val withData = runCatching { ClientCapability.REMOVE_WITH_DATA in ops.session().capabilities }.getOrDefault(false)
            runCatching { ops.remove(source.infoHash, deleteData = withData) }
        }
    }
}


/** Same reviewed Details contract for native libtorrent and all four actual daemons. */
internal suspend fun assertReviewedFilePriorityConformance(ops: ClientOperations, hash: String, target: Int) {
    val files = ops.files(hash)
    val before = files.associate { it.index to it.priority }
    val selected = files.single { it.index == target }
    val untouched = files.first { it.index != target }
    val changed = mapOf(target to ClientFilePriority.HIGH)
    val invalidReviews = listOf(
        files + untouched,
        files.filterNot { it.index == untouched.index },
        files.map { if (it.index == untouched.index) it.copy(path = "wrong/" + it.path) else it },
        files.map { if (it.index == untouched.index) it.copy(sizeBytes = it.sizeBytes + 1) else it },
        files.map { if (it.index == target) it.copy(priority = ClientFilePriority.SKIP) else it }
    )
    for (review in invalidReviews) {
        val blocked = ops.setReviewedFilePriorities(hash, review, changed)
        assertTrue("Stale reviewed priority mutation was not refused: $blocked",
            blocked is ClientOperationOutcome.Refused)
        assertEquals("Refusal must leave the daemon priorities unchanged", before,
            ops.files(hash).associate { it.index to it.priority })
    }
    val applied = ops.setReviewedFilePriorities(hash, files, changed)
    assertTrue("Exact reviewed edit failed: $applied", applied is ClientOperationOutcome.Verified)
    val observed = ops.files(hash)
    assertEquals(before + changed, observed.associate { it.index to it.priority })
    val restored = ops.setReviewedFilePriorities(hash, observed, mapOf(target to selected.priority))
    assertTrue("Exact reviewed restore failed: $restored", restored is ClientOperationOutcome.Verified)
    assertEquals(before, ops.files(hash).associate { it.index to it.priority })
}
