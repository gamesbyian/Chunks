package io.github.gamesbyian.chunks.engine

import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.util.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.create_flags_t

/**
 * SPEC-COVERAGE "Existing files reused by local Add/Start": the on-disk shapes a person
 * actually has, beyond a short file with a bad piece. Real libtorrent and a loopback
 * seeder throughout. Each case adds stopped over exact-path data, lets libtorrent's own
 * check run while stopped (nothing transferred or written), asserts exactly which pieces
 * verified, then Starts and requires byte-exact payload with only missing pieces fetched.
 */
class ExistingPayloadReuseFixturesTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = ArrayList<LocalTorrentEngine>()

    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engines.forEach { it.close() } }

    private fun engine() = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
        allowMultipleConnectionsPerIp = true)).also { it.start(); engines += it }

    private fun waitFor(timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for engine state")
            Thread.sleep(100)
        }
    }

    private class Payload(val root: File, val torrent: ByteArray, val files: Map<String, ByteArray>) {
        val total get() = files.values.sumOf { it.size.toLong() }
    }

    private fun payload(name: String, sizes: List<Pair<String, Int>>, piece: Int, flags: create_flags_t?): Payload {
        val root = File(temp.newFolder("seed-$name"), name).apply { mkdirs() }
        val files = LinkedHashMap<String, ByteArray>()
        sizes.forEachIndexed { i, (path, size) ->
            files[path] = ByteArray(size).also { Random(100L + i).nextBytes(it) }
            File(root, path).apply { parentFile.mkdirs() }.writeBytes(files.getValue(path))
        }
        val builder = TorrentBuilder().path(root).pieceSize(piece)
        if (flags != null) builder.flags(flags)
        return Payload(root, builder.generate().entry().bencode(), files)
    }

    private fun seeder(payload: Payload): Pair<LocalTorrentEngine, String> {
        val seeder = engine()
        val seed = (seeder.addStopped(payload.torrent, payload.root.parentFile) as LocalAddResult.AddedStopped).status
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seed.infoHash).outcome)
        waitFor { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }
        return seeder to seed.infoHash
    }

    /** Stopped checked Add + stopped verification; returns the settled stopped status. */
    private fun verifyStopped(local: LocalTorrentEngine, payload: Payload, destination: File,
                              seederPort: Int): LocalTransferStatus {
        val added = (local.addStoppedChecked(payload.torrent, destination,
            listOf(InetSocketAddress("127.0.0.1", seederPort))) as LocalAddResult.AddedStopped).status
        assertTrue(local.beginStoppedVerification(added.infoHash))
        waitFor { local.status(added.infoHash)?.let { it.stopped && !it.stopsAfterCheck &&
            it.state != LocalTransferState.CHECKING } == true }
        Thread.sleep(1_000)
        val settled = requireNotNull(local.status(added.infoHash))
        assertTrue("still stopped: $settled", settled.stopped)
        assertEquals("nothing transferred while stopped: $settled", 0L, settled.payloadDownloadedBytes)
        return settled
    }

    private fun startAndRequireExact(local: LocalTorrentEngine, hash: String, payload: Payload,
                                     destination: File, seederPort: Int, reusedBytes: Long, piece: Int) {
        assertEquals(LocalCommandOutcome.VERIFIED, local.resume(hash).outcome)
        local.connectPeer(hash, InetSocketAddress("127.0.0.1", seederPort))
        waitFor { local.status(hash)?.let { it.progressPpm == 1_000_000 &&
            it.state in setOf(LocalTransferState.FINISHED, LocalTransferState.SEEDING) } == true }
        assertTrue(local.flushCompletedForLibraryRead(hash, timeoutMs = 30_000L))
        for ((path, bytes) in payload.files)
            assertArrayEquals(path, bytes, File(destination, payload.root.name + "/" + path).readBytes())
        val downloaded = requireNotNull(local.status(hash)).payloadDownloadedBytes
        assertTrue("verified pieces were not fetched again: downloaded=$downloaded reused=$reusedBytes",
            downloaded <= payload.total - reusedBytes + piece)
    }

    private fun place(destination: File, payload: Payload, path: String): File =
        File(destination, payload.root.name + "/" + path).apply { parentFile.mkdirs() }

    /** A full-size preallocated (sparse, zero-filled) file with only its first pieces written. */
    @Test fun preallocatedFullSizeFileVerifiesOnlyWrittenPieces() {
        val piece = 64 * 1024
        val payload = payload("Prealloc", listOf("video.bin" to 1_000_000), piece, flags = null)
        val (seeder, _) = seeder(payload)
        val destination = temp.newFolder("prealloc-dest")
        val written = 6
        RandomAccessFile(place(destination, payload, "video.bin"), "rw").use { f ->
            f.setLength(1_000_000L)
            f.write(payload.files.getValue("video.bin"), 0, written * piece)
        }
        val local = engine()
        val stopped = verifyStopped(local, payload, destination, seeder.listenPort())
        assertEquals("hybrid identity reported by its v1 hash", 40, stopped.infoHash.length)
        assertEquals("zero-filled preallocation is not counted as data", written.toLong() * piece, stopped.doneBytes)
        startAndRequireExact(local, stopped.infoHash, payload, destination, seeder.listenPort(),
            written.toLong() * piece, piece)
    }

    /** Complete existing data: verified to 100% while stopped, then Start seeds without downloading. */
    @Test fun completeExistingDataVerifiesFullyAndStartSeedsWithoutDownloading() {
        val piece = 32 * 1024
        val payload = payload("Complete", listOf("a.bin" to 300_000, "sub/b.bin" to 77_777), piece,
            TorrentBuilder.V1_ONLY)
        val (seeder, _) = seeder(payload)
        val destination = temp.newFolder("complete-dest")
        for ((path, bytes) in payload.files) place(destination, payload, path).writeBytes(bytes)
        val local = engine()
        val stopped = verifyStopped(local, payload, destination, seeder.listenPort())
        assertEquals(payload.total, stopped.doneBytes)
        assertEquals(1_000_000, stopped.progressPpm)
        assertEquals(LocalCommandOutcome.VERIFIED, local.resume(stopped.infoHash).outcome)
        waitFor { local.status(stopped.infoHash)?.state == LocalTransferState.SEEDING }
        assertEquals(0L, requireNotNull(local.status(stopped.infoHash)).payloadDownloadedBytes)
        for ((path, bytes) in payload.files)
            assertArrayEquals(path, bytes, File(destination, payload.root.name + "/" + path).readBytes())
    }

    /**
     * v1 pieces span file boundaries: with the middle file missing, exactly the pieces that
     * lie wholly inside present files verify; every piece touching the missing file does not.
     */
    @Test fun v1CrossFilePiecesVerifyOnlyWhenEveryFileTheyTouchIsPresent() {
        val piece = 16 * 1024
        val sizes = listOf("1.bin" to 100_000, "2.bin" to 150_000, "3.bin" to 200_000)
        val payload = payload("CrossFile", sizes, piece, TorrentBuilder.V1_ONLY)
        val (seeder, _) = seeder(payload)
        val destination = temp.newFolder("cross-dest")
        place(destination, payload, "1.bin").writeBytes(payload.files.getValue("1.bin"))
        place(destination, payload, "3.bin").writeBytes(payload.files.getValue("3.bin"))
        val local = engine()
        val stopped = verifyStopped(local, payload, destination, seeder.listenPort())

        // libtorrent's own file order and offsets (directory order, not name order).
        val layout = TorrentInfo.bdecode(payload.torrent).files()
        val missing = (0 until layout.numFiles()).single { layout.filePath(it).endsWith("2.bin") }
        val missingStart = layout.fileOffset(missing)
        val missingEnd = missingStart + layout.fileSize(missing)
        val total = payload.total
        val pieces = ((total + piece - 1) / piece).toInt()
        var expectedBytes = 0L
        for (i in 0 until pieces) {
            val start = i.toLong() * piece
            val end = minOf(start + piece, total)
            val expected = end <= missingStart || start >= missingEnd
            if (expected) expectedBytes += end - start
            assertEquals("piece $i [$start,$end)", expected, local.hasVerifiedPiece(stopped.infoHash, i))
        }
        assertEquals(expectedBytes, stopped.doneBytes)
        assertTrue("the missing file was not created while stopped",
            !File(destination, "CrossFile/2.bin").exists() || File(destination, "CrossFile/2.bin").length() == 0L)
        startAndRequireExact(local, stopped.infoHash, payload, destination, seeder.listenPort(), expectedBytes, piece)
    }

    /**
     * BitTorrent v2-only (per-file merkle trees, identity = SHA-256 truncated to 160 bits,
     * BEP 52). libtorrent 2.x's own check credits a complete v2 file at once, but a partial
     * v2 file's valid leading pieces may stay unverified until downloading completes that
     * file's tree (observed 2026-11-13, libtorrent4j 2.1.0-39; v1 and hybrid credit them).
     * Whatever libtorrent credits, Chunks never claims more than the truly valid bytes and
     * Start ends byte-exact. No bespoke hashing is added for this.
     */
    private fun v2Case(name: String, tail: Int, preallocated: Boolean) {
        val payload = payload(name, listOf("first.bin" to 8 * V2_PIECE, "second.bin" to 12 * V2_PIECE + tail),
            V2_PIECE, TorrentBuilder.V2_ONLY)
        val hashes = TorrentInfo.bdecode(payload.torrent).infoHashes()
        assertTrue("fixture is genuinely v2-only", !hashes.hasV1() && hashes.hasV2())
        val (seeder, _) = seeder(payload)
        val destination = temp.newFolder("$name-dest")
        place(destination, payload, "first.bin").writeBytes(payload.files.getValue("first.bin"))
        val second = payload.files.getValue("second.bin")
        RandomAccessFile(place(destination, payload, "second.bin"), "rw").use { f ->
            if (preallocated) f.setLength(second.size.toLong())
            f.write(second, 0, 5 * V2_PIECE)
        }
        val local = engine()
        val stopped = verifyStopped(local, payload, destination, seeder.listenPort())
        assertEquals("v2-only identity is libtorrent's best hash: SHA-256 truncated to 160 bits",
            hashes.swig().getV2().to_hex().take(40).lowercase(), stopped.infoHash)
        assertTrue("the complete file verified and nothing beyond the valid bytes: ${stopped.doneBytes}",
            stopped.doneBytes in 8L * V2_PIECE..13L * V2_PIECE)
        startAndRequireExact(local, stopped.infoHash, payload, destination, seeder.listenPort(),
            stopped.doneBytes, V2_PIECE)
    }

    @Test fun v2OnlyShortPartialFileNeverOverclaimsAndStartIsExact() =
        v2Case("V2Short", tail = 5_000, preallocated = false)

    @Test fun v2OnlyPreallocatedFileNeverOverclaimsAndStartIsExact() =
        v2Case("V2Prealloc", tail = 7_000, preallocated = true)

    private companion object { const val V2_PIECE = 32 * 1024 }
}
