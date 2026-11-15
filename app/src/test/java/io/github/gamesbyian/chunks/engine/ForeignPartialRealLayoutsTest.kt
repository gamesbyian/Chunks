package io.github.gamesbyian.chunks.engine

import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder
import io.github.gamesbyian.chunks.engine.ForeignPartialLayoutScanner as S

/**
 * Real-world interrupted foreign-client layouts (FOREIGN-PARTIAL-TORRENT-RECOVERY P1/P2):
 * BitTorrent `.!bt`, aria2's actual control-file placement beside exact payload, hard-linked
 * partial copies, and qBittorrent's mixed per-file state where completed files already have
 * their final names. The engine cases use real libtorrent over loopback, so every reused
 * piece is hash-verified rather than inferred from names or sizes.
 */
class ForeignPartialRealLayoutsTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = ArrayList<LocalTorrentEngine>()
    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engines.forEach { it.close() } }

    private fun engine() = LocalTorrentEngine(LocalEngineConfig(
        listenInterfaces = "127.0.0.1:0", allowMultipleConnectionsPerIp = true
    )).also { it.start(); engines += it }
    private fun waitFor(ms: Long = 60_000, c: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!c()) { if (System.currentTimeMillis() > end) fail("timeout"); Thread.sleep(100) }
    }
    /** libtorrent refreshes all-time counters on its second tick: wait until the value is stable. */
    private fun settledDownloaded(e: LocalTorrentEngine, hash: String): Long {
        var last = -1L; var stableSince = System.currentTimeMillis()
        val end = stableSince + 15_000
        while (System.currentTimeMillis() < end) {
            val now = requireNotNull(e.status(hash)).payloadDownloadedBytes
            if (now != last) { last = now; stableSince = System.currentTimeMillis() }
            else if (now > 0 && System.currentTimeMillis() - stableSince >= 2_500) return now
            Thread.sleep(100)
        }
        return last
    }
    private fun added(r: LocalAddResult) = (r as LocalAddResult.AddedStopped).status
    private fun file(root: File, rel: String, bytes: ByteArray = ByteArray(3)) =
        File(root, rel).also { it.parentFile.mkdirs(); it.writeBytes(bytes) }
    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to sha(it) }

    private fun seed(root: File, parent: File, pieceSize: Int): Pair<ByteArray, LocalTorrentEngine> {
        val torrent = TorrentBuilder().path(root).pieceSize(pieceSize).generate().entry().bencode()
        val seeder = engine()
        val s = added(seeder.addStopped(torrent, parent))
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(s.infoHash).outcome)
        waitFor { seeder.status(s.infoHash)?.state == LocalTransferState.SEEDING }
        return torrent to seeder
    }

    @Test fun bitTorrentClassicSuffixIsAnImportableCandidate() {
        val root = temp.newFolder()
        file(root, "T/a.mkv.!bt", ByteArray(9))
        val r = S.scan(root, listOf("T/a.mkv")) as S.Result.Found
        val c = r.candidates.single()
        assertEquals(S.Kind.BITTORRENT, c.kind)
        assertTrue(c.kind.importable)
        assertFalse(c.sharedOrUnknownLinks)
        assertEquals("BitTorrent (.!bt)", ForeignPartialAddPlan.clientLabel(c.kind))
    }

    @Test fun aria2ControlFilesAreDisclosedWhereAria2PutsThemBesideExactPayload() {
        // Multi-file: aria2 names the control file after the torrent's top directory.
        val multi = temp.newFolder("multi")
        file(multi, "Show/e01.mkv"); file(multi, "Show/e02.mkv"); file(multi, "Show.aria2", ByteArray(40))
        val before = snapshot(multi)
        val m = S.scan(multi, listOf("Show/e01.mkv", "Show/e02.mkv")) as S.Result.Found
        assertEquals(listOf("Show.aria2"), m.candidates.map { it.relativeCandidatePath })
        assertEquals(S.Kind.ARIA2_CONTROL, m.candidates.single().kind)
        val offer = requireNotNull(ForeignPartialAddPlan.offerForPaths(listOf("Show/e01.mkv", "Show/e02.mkv"), multi))
        assertTrue("exact files are ordinary reuse, never renamed", offer.reusable.isEmpty())
        assertEquals(1, offer.untouchedAuxiliary)
        assertEquals(before, snapshot(multi))

        // Single file: `<file>.aria2` beside the exact (preallocated) payload.
        val single = temp.newFolder("single")
        file(single, "movie.mkv"); file(single, "movie.mkv.aria2")
        val s = S.scan(single, listOf("movie.mkv")) as S.Result.Found
        assertEquals(listOf("movie.mkv.aria2"), s.candidates.map { it.relativeCandidatePath })
        assertFalse(s.candidates.single().kind.importable)

        // A symlinked or directory control file is never followed or reported.
        val linked = temp.newFolder("linked")
        file(linked, "Show/e01.mkv")
        Files.createSymbolicLink(File(linked, "Show.aria2").toPath(), temp.newFile().toPath())
        assertTrue((S.scan(linked, listOf("Show/e01.mkv")) as S.Result.Found).candidates.isEmpty())
    }

    @Test fun hardLinkedPartialIsDisclosedButNeverAdopted() {
        val root = temp.newFolder("root")
        val other = file(root, "library/keep.bin", ByteArray(4096) { 7 })
        Files.createDirectories(File(root, "T").toPath())
        Files.createLink(File(root, "T/a.bin.!qB").toPath(), other.toPath())
        file(root, "T/b.bin.!qB", ByteArray(10))
        val paths = listOf("T/a.bin", "T/b.bin")
        val scan = S.scan(root, paths) as S.Result.Found
        assertTrue(scan.candidates.first { it.declaredPath == "T/a.bin" }.sharedOrUnknownLinks)
        assertFalse(scan.candidates.first { it.declaredPath == "T/b.bin" }.sharedOrUnknownLinks)

        val offer = requireNotNull(ForeignPartialAddPlan.offerForPaths(paths, root))
        assertEquals(listOf("T/b.bin"), offer.reusable.map { it.declaredPath })
        assertEquals(1, offer.linkedFiles)

        // Directly approving the linked candidate is refused with nothing renamed.
        val before = snapshot(root)
        val refused = ForeignPartialRebind.adopt(root, scan.candidates)
        assertTrue(refused is ForeignPartialRebind.Result.Refused)
        assertEquals(before, snapshot(root))
    }

    @Test fun aHardLinkCreatedAfterReviewBlocksAdoption() {
        val root = temp.newFolder("root")
        file(root, "T/a.bin.!qB", ByteArray(10))
        val paths = listOf("T/a.bin")
        val offer = requireNotNull(ForeignPartialAddPlan.offerForPaths(paths, root))
        assertEquals(1, offer.reusable.size)
        Files.createLink(File(root, "elsewhere.bin").toPath(), File(root, "T/a.bin.!qB").toPath())
        val before = snapshot(root)
        val result = ForeignPartialAddPlan.adoptReviewedPaths(paths, root, offer)
        assertTrue(result.toString(), result is ForeignPartialAddPlan.Adoption.Refused)
        assertEquals(before, snapshot(root))
        assertFalse(File(root, "T/a.bin").exists())
    }

    /**
     * qBittorrent with "Append .!qB extension to incomplete files" renames each file when THAT
     * file completes, so an interrupted multi-file job holds final names and `.!qB` names side
     * by side. Pieces straddle file boundaries; one piece inside the partial is corrupt.
     */
    @Test fun qBittorrentMixedMultiFileStateReusesOnlyVerifiedPiecesAcrossFileBoundaries() {
        val pieceSize = 32 * 1024
        val seedParent = temp.newFolder("seed")
        val root = File(seedParent, "Album").apply { mkdirs() }
        val rnd = Random(11)
        // Sizes are not piece multiples, so pieces straddle a/b and b/c.
        val a = ByteArray(100_000).also(rnd::nextBytes)
        val b = ByteArray(150_000).also(rnd::nextBytes)
        val c = ByteArray(90_000).also(rnd::nextBytes)
        File(root, "01.flac").writeBytes(a); File(root, "02.flac").writeBytes(b); File(root, "03.flac").writeBytes(c)
        val (torrent, seeder) = seed(root, seedParent, pieceSize)

        val dest = temp.newFolder("dest")
        file(dest, "Album/01.flac", a) // completed: already renamed by qBittorrent
        val partial = b.copyOf(120_000) // interrupted inside 02
        for (i in 70_000 until 71_000) partial[i] = (partial[i].toInt() xor 0x5a).toByte()
        file(dest, "Album/02.flac.!qB", partial)
        // 03 never started: no file at all.

        val offer = requireNotNull(ForeignPartialAddPlan.offer(torrent, dest))
        assertEquals(listOf("Album/02.flac"), offer.reusable.map { it.declaredPath })
        assertEquals(sha(File(dest, "Album/01.flac")), sha(File(seedParent, "Album/01.flac")))

        val adoption = ForeignPartialAddPlan.adoptReviewed(torrent, dest, offer)
        assertTrue(adoption.toString(), adoption is ForeignPartialAddPlan.Adoption.Renamed)
        assertTrue(File(dest, "Album/02.flac").isFile)
        assertFalse(File(dest, "Album/02.flac.!qB").exists())

        val receiver = engine()
        val r = added(receiver.addStoppedChecked(torrent, dest))
        assertEquals("nothing is claimed before the Start check", 0, r.progressPpm)
        assertEquals(LocalCommandOutcome.VERIFIED, receiver.resume(r.infoHash).outcome)
        assertTrue(receiver.connectPeer(r.infoHash, InetSocketAddress("127.0.0.1", seeder.listenPort())))
        waitFor { receiver.status(r.infoHash)?.progressPpm == 1_000_000 }

        assertArrayEquals(a, File(dest, "Album/01.flac").readBytes())
        assertArrayEquals(b, File(dest, "Album/02.flac").readBytes())
        assertArrayEquals(c, File(dest, "Album/03.flac").readBytes())
        val total = (a.size + b.size + c.size).toLong()
        val validPrefix = a.size + 70_000L // bytes before the corruption
        val downloaded = settledDownloaded(receiver, r.infoHash)
        // At most: the corrupt piece(s), the never-written tail and boundary rounding.
        val bound = total - validPrefix - (120_000 - 71_000) + 3L * pieceSize
        assertTrue("reused verified pieces must not be refetched: $downloaded > $bound", downloaded <= bound)
        assertTrue("the corrupt region and missing tail must actually be fetched: $downloaded",
            downloaded >= total - (a.size + 120_000L))
    }

    /**
     * aria2 preallocates BitTorrent payload at the exact declared names and keeps a control
     * file beside the top directory. The exact files are reused through libtorrent's own
     * check (zeroed preallocated regions fail it and are fetched); the control file is never
     * read, trusted, rewritten or removed.
     */
    @Test fun aria2PreallocatedExactLayoutIsVerifiedAndItsControlFileIsLeftUntouched() {
        val pieceSize = 32 * 1024
        val seedParent = temp.newFolder("seed")
        val root = File(seedParent, "Pack").apply { mkdirs() }
        val x = ByteArray(200_000).also(Random(3)::nextBytes)
        val y = ByteArray(64_000).also(Random(4)::nextBytes)
        File(root, "x.bin").writeBytes(x); File(root, "y.bin").writeBytes(y)
        val (torrent, seeder) = seed(root, seedParent, pieceSize)

        val dest = temp.newFolder("dest")
        val prealloc = ByteArray(x.size).also { System.arraycopy(x, 0, it, 0, 96_000) }
        file(dest, "Pack/x.bin", prealloc)
        file(dest, "Pack/y.bin", ByteArray(y.size))
        val control = file(dest, "Pack.aria2", ByteArray(321) { (it % 251).toByte() })
        val controlSha = sha(control)

        val offer = requireNotNull(ForeignPartialAddPlan.offer(torrent, dest))
        assertTrue(offer.reusable.isEmpty())
        assertEquals(1, offer.untouchedAuxiliary)
        assertTrue(ForeignPartialAddPlan.adoptReviewed(torrent, dest, offer) is ForeignPartialAddPlan.Adoption.NotRequested)

        val receiver = engine()
        val r = added(receiver.addStoppedChecked(torrent, dest))
        assertEquals(LocalCommandOutcome.VERIFIED, receiver.resume(r.infoHash).outcome)
        assertTrue(receiver.connectPeer(r.infoHash, InetSocketAddress("127.0.0.1", seeder.listenPort())))
        waitFor { receiver.status(r.infoHash)?.progressPpm == 1_000_000 }
        assertArrayEquals(x, File(dest, "Pack/x.bin").readBytes())
        assertArrayEquals(y, File(dest, "Pack/y.bin").readBytes())
        val downloaded = settledDownloaded(receiver, r.infoHash)
        // Only whole pieces inside the preallocated prefix can verify; allow one piece of
        // end-game redundancy, which libtorrent's all-time payload counter includes.
        val total = (x.size + y.size).toLong()
        val wholeReusable = (96_000L / pieceSize) * pieceSize
        assertTrue("verified preallocated pieces must be reused: $downloaded",
            downloaded <= total - wholeReusable + pieceSize && downloaded < total)
        assertTrue("zeroed preallocated regions must actually be fetched: $downloaded",
            downloaded >= total - 96_000L - pieceSize)
        assertEquals("aria2 control file is never modified", controlSha, sha(control))
    }
}
