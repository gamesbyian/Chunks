package io.github.gamesbyian.chunks.create

import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder.Entry
import io.github.gamesbyian.chunks.create.TorrentMetainfoBuilder.Request
import io.github.gamesbyian.chunks.engine.DesktopLibtorrent
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo

class TorrentMetainfoBuilderTest {
    @get:Rule val temp = TemporaryFolder()

    private fun season(): File {
        val root = File(temp.newFolder("src"), "Public Season").apply { mkdirs() }
        val random = Random(2026)
        // Sizes that straddle piece boundaries, including an empty file.
        mapOf("E01.mkv" to 70_001, "E02.mkv" to 16_384, "Extras/notes.txt" to 0, "Extras/cover.jpg" to 5_000)
            .forEach { (path, size) ->
                File(root, path).apply { parentFile.mkdirs() }.writeBytes(ByteArray(size).also(random::nextBytes))
            }
        return root
    }

    private fun entry(root: File, path: List<String>) =
        File(root, path.joinToString("/")).let { file -> Entry(path, file.length()) { file.inputStream() } }

    @Test fun infoHashMatchesLibtorrentsOwnBuilderAndTheRealEngineVerifiesThePayload() {
        DesktopLibtorrent.ensureLoaded()
        val root = season()
        val reference = TorrentInfo.bdecode(TorrentBuilder().path(root).pieceSize(16 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode())
        // Same files in libtorrent's own order, so only our encoding can differ.
        val storage = reference.files()
        val paths = (0 until storage.numFiles()).filter { storage.fileSize(it) >= 0 }
            .map { storage.filePath(it).replace('\\', '/').split('/').drop(1) }
            .filter { File(root, it.joinToString("/")).isFile }
        val result = TorrentMetainfoBuilder.build(Request("Public Season", paths.map { entry(root, it) }, pieceLength = 16 * 1024))
        assertEquals(reference.infoHash().toHex(), result.infoHashV1)

        val preview = TorrentMetainfoReader.preview(result.torrent)
        assertEquals(result.infoHashV1, preview.infoHashV1)
        assertEquals(paths.size, preview.fileCount)
        assertEquals(91_385L, preview.sizeBytes)
        assertEquals(0, preview.trackerCount)

        // The real engine finds every piece of the existing payload valid.
        val engine = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0")).also { it.start() }
        try {
            val added = engine.addStopped(result.torrent, requireNotNull(root.parentFile)) as LocalAddResult.AddedStopped
            assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(added.status.infoHash).outcome)
            val deadline = System.currentTimeMillis() + 30_000
            while (engine.status(added.status.infoHash)?.progressPpm != 1_000_000) {
                assertTrue("engine did not verify the payload", System.currentTimeMillis() < deadline)
                Thread.sleep(100)
            }
        } finally { engine.close() }
    }

    @Test fun singleFileTrackersPrivacyAndMagnet() {
        val bytes = ByteArray(40_000) { it.toByte() }
        fun request(private: Boolean) = Request("clip one.mp4",
            listOf(Entry(listOf("clip one.mp4"), bytes.size.toLong()) { ByteArrayInputStream(bytes) }),
            trackerTiers = listOf(listOf("udp://tracker.example:6969/announce"), listOf("https://t2.example/announce")),
            private = private, pieceLength = 32 * 1024)
        val open = TorrentMetainfoBuilder.build(request(private = false))
        assertEquals(1, TorrentMetainfoReader.preview(open.torrent).fileCount)
        assertEquals(2, TorrentMetainfoReader.preview(open.torrent).trackerCount)
        assertEquals(2, open.pieceCount)
        assertTrue(open.magnet!!.startsWith("magnet:?xt=urn:btih:${open.infoHashV1}&dn=clip%20one.mp4&xl=40000&tr="))
        assertEquals(2, TorrentMetainfoReader.magnetTrackerCount(open.magnet!!))
        val closed = TorrentMetainfoBuilder.build(request(private = true))
        assertNull("private trackers are not shared through magnets", closed.magnet)
        assertNotEquals("the private flag changes identity", open.infoHashV1, closed.infoHashV1)
        assertTrue(String(closed.torrent, Charsets.ISO_8859_1).contains("7:privatei1e"))
        // Same input, same bytes: no clock or randomness unless asked for.
        assertArrayEquals(open.torrent, TorrentMetainfoBuilder.build(request(private = false)).torrent)
    }

    @Test fun aFileThatChangesWhileHashingFailsInsteadOfProducingWrongMetadata() {
        val shorter = Entry(listOf("a.bin"), 50_000) { ByteArrayInputStream(ByteArray(49_999)) }
        val longer = Entry(listOf("a.bin"), 50_000) { ByteArrayInputStream(ByteArray(50_001)) }
        for (e in listOf(shorter, longer)) try {
            TorrentMetainfoBuilder.build(Request("a.bin", listOf(e)))
            fail("size change must fail")
        } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("may have changed")) }
    }

    @Test fun cancellationStopsHashingAndProgressIsReported() {
        val bytes = ByteArray(200_000)
        val seen = ArrayList<Long>()
        try {
            TorrentMetainfoBuilder.build(Request("big.bin",
                listOf(Entry(listOf("big.bin"), bytes.size.toLong()) { ByteArrayInputStream(bytes) }), pieceLength = 16 * 1024),
                onProgress = { seen += it }, isCancelled = { seen.size >= 3 })
            fail("expected cancellation")
        } catch (_: TorrentMetainfoBuilder.Cancelled) { }
        assertEquals(listOf(16_384L, 32_768L, 49_152L), seen)
    }

    @Test fun refusesUnsafeNamesConflictsCredentialsAndBadPieceSizes() {
        fun one(path: List<String>) = Entry(path, 1) { ByteArrayInputStream(byteArrayOf(1)) }
        val bad = listOf(
            Request("..", listOf(one(listOf("x")))),
            Request("ok", listOf(one(listOf("a", "..", "b")))),
            Request("ok", listOf(one(listOf("a\\b")))),
            Request("ok", listOf(one(listOf("a")), one(listOf("a")))),
            Request("ok", listOf(one(listOf("a")), one(listOf("a", "b")))),
            Request("ok", listOf(Entry(listOf("e"), 0) { ByteArrayInputStream(ByteArray(0)) })),
            Request("ok", listOf(one(listOf("a"))), trackerTiers = listOf(listOf("https://user:pw@t.example/announce"))),
            Request("ok", listOf(one(listOf("a"))), trackerTiers = listOf(listOf("ftp://t.example/announce"))),
            Request("ok", listOf(one(listOf("a"))), pieceLength = 20_000),
            Request("ok", emptyList())
        )
        for (request in bad) try {
            TorrentMetainfoBuilder.build(request)
            fail("should refuse ${request.name} ${request.entries.map { it.path }}")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun automaticPieceSizeKeepsPieceCountsModest() {
        assertEquals(16 * 1024, TorrentMetainfoBuilder.autoPieceLength(1_000_000))
        assertEquals(1024 * 1024, TorrentMetainfoBuilder.autoPieceLength(2_000_000_000))
        assertEquals(16 * 1024 * 1024, TorrentMetainfoBuilder.autoPieceLength(1L shl 40))
    }
}
