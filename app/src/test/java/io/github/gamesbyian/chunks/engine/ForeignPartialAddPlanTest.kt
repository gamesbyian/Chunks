package io.github.gamesbyian.chunks.engine

import java.io.File
import java.net.InetSocketAddress
import java.util.Random
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

/**
 * Universal Add's recovery plan: read-only offer, explicit adoption of exactly the reviewed
 * set, refusal on any change, undo after a refused Add, and real libtorrent verification of
 * the adopted bytes. JVM + loopback libtorrent tier only.
 */
class ForeignPartialAddPlanTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = ArrayList<LocalTorrentEngine>()
    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engines.forEach { it.close() } }

    private val pieceSize = 64 * 1024
    private val data = ByteArray(700_000).also { Random(11).nextBytes(it) }

    private fun torrent(): ByteArray {
        val root = File(temp.newFolder("source"), "Show").apply { mkdirs() }
        File(root, "a.mkv").writeBytes(data)
        File(root, "b.mkv").writeBytes(data.copyOf(200_000))
        return TorrentBuilder().path(root).pieceSize(pieceSize).generate().entry().bencode()
    }

    private fun destWith(vararg files: Pair<String, ByteArray>): File {
        val dest = temp.newFolder()
        File(dest, "Show").mkdirs()
        files.forEach { (name, bytes) -> File(dest, "Show/$name").writeBytes(bytes) }
        return dest
    }

    @Test fun offerIsReadOnlyAndSeparatesReusableAmbiguousAndAuxiliaryState() {
        val t = torrent()
        val dest = destWith("a.mkv.!qB" to data.copyOf(100_000), "b.mkv.part" to ByteArray(10),
            "b.mkv.!ut" to ByteArray(10), "a.mkv.aria2" to ByteArray(5))
        val before = dest.walk().map { it.relativeTo(dest).path }.sorted().toList()
        val offer = requireNotNull(ForeignPartialAddPlan.offer(t, dest))
        assertEquals(listOf("Show/a.mkv"), offer.reusable.map { it.declaredPath })
        assertEquals(1, offer.ambiguousFiles)
        assertEquals(1, offer.untouchedAuxiliary)
        assertEquals(100_000L, offer.reusableBytes)
        assertEquals(before, dest.walk().map { it.relativeTo(dest).path }.sorted().toList())
    }

    @Test fun nothingRecognisableMeansNoOfferAndNoRequestMeansNoRename() {
        val t = torrent()
        val dest = destWith("unrelated.txt" to ByteArray(3))
        assertNull(ForeignPartialAddPlan.offer(t, dest))
        assertNull(ForeignPartialAddPlan.offer(t, File(dest, "missing")))
        assertEquals(ForeignPartialAddPlan.Adoption.NotRequested, ForeignPartialAddPlan.adoptReviewed(t, dest, null))
    }

    @Test fun adoptionRefusesAChangedFolderOrCandidateAndTouchesNothing() {
        val t = torrent()
        val dest = destWith("a.mkv.!qB" to data.copyOf(100_000))
        val offer = requireNotNull(ForeignPartialAddPlan.offer(t, dest))
        val other = destWith("a.mkv.!qB" to data.copyOf(100_000))
        assertTrue(ForeignPartialAddPlan.adoptReviewed(t, other, offer) is ForeignPartialAddPlan.Adoption.Refused)
        assertTrue(File(other, "Show/a.mkv.!qB").isFile)
        // The other client kept writing after review: a new review is required.
        File(dest, "Show/a.mkv.!qB").appendBytes(ByteArray(10))
        assertTrue(ForeignPartialAddPlan.adoptReviewed(t, dest, offer) is ForeignPartialAddPlan.Adoption.Refused)
        assertTrue(File(dest, "Show/a.mkv.!qB").isFile)
        assertFalse(File(dest, "Show/a.mkv").exists())
    }

    @Test fun undoRestoresOriginalNamesAfterARefusedAdd() {
        val t = torrent()
        val dest = destWith("a.mkv.!qB" to data.copyOf(100_000))
        val offer = requireNotNull(ForeignPartialAddPlan.offer(t, dest))
        val adoption = ForeignPartialAddPlan.adoptReviewed(t, dest, offer)
        assertTrue(adoption is ForeignPartialAddPlan.Adoption.Renamed)
        assertTrue(File(dest, "Show/a.mkv").isFile)
        assertTrue(ForeignPartialAddPlan.undo(dest, adoption))
        assertTrue(File(dest, "Show/a.mkv.!qB").isFile)
        assertFalse(File(dest, "Show/a.mkv").exists())
    }

    @Test fun adoptedPartialIsVerifiedByTheCheckedAddWithCorruptPiecesRejected() {
        val t = torrent()
        val partial = data.copyOf(4 * pieceSize).also { it[pieceSize + 3] = (it[pieceSize + 3] + 1).toByte() }
        val dest = destWith("a.mkv.!qB" to partial)
        val offer = requireNotNull(ForeignPartialAddPlan.offer(t, dest))
        assertTrue(ForeignPartialAddPlan.adoptReviewed(t, dest, offer) is ForeignPartialAddPlan.Adoption.Renamed)
        val engine = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0"))
            .also { it.start(); engines += it }
        val added = engine.addStoppedChecked(t, dest) as LocalAddResult.AddedStopped
        assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(added.status.infoHash).outcome)
        // Pieces 0, 2 and 3 verify; corrupted piece 1 does not. No peer is attached, so the
        // verified count comes only from the adopted bytes.
        val end = System.currentTimeMillis() + 60_000
        var verified = -1L
        while (System.currentTimeMillis() < end) {
            val s = engine.status(added.status.infoHash)
            if (s != null && s.state != LocalTransferState.CHECKING && s.doneBytes > 0) {
                verified = s.doneBytes; break
            }
            Thread.sleep(100)
        }
        assertEquals(3L * pieceSize, verified)
    }
}
