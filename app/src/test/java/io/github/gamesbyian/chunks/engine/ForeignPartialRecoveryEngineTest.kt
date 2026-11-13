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

/** Real libtorrent over loopback: a foreign `.!qB` partial is adopted, verified and only the gap refetched. */
class ForeignPartialRecoveryEngineTest {
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
    private fun added(r: LocalAddResult) = (r as LocalAddResult.AddedStopped).status

    @Test fun adoptedForeignPartialIsHashCheckedAndOnlyMissingPiecesAreFetched() {
        val pieceSize = 64 * 1024
        val seedParent = temp.newFolder("seed")
        val root = File(seedParent, "Fixture").apply { mkdirs() }
        val data = ByteArray(1_000_000).also { Random(7).nextBytes(it) }
        File(root, "movie.bin").writeBytes(data)
        val torrent = TorrentBuilder().path(root).pieceSize(pieceSize).generate().entry().bencode()

        val seeder = engine()
        val s = added(seeder.addStopped(torrent, seedParent))
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(s.infoHash).outcome)
        waitFor { seeder.status(s.infoHash)?.state == LocalTransferState.SEEDING }

        val dest = temp.newFolder("dest")
        val keptPieces = 6
        File(dest, "Fixture").mkdirs()
        File(dest, "Fixture/movie.bin.!qB").writeBytes(data.copyOf(keptPieces * pieceSize))

        val found = ForeignPartialRecovery.scan(torrent, dest) as ForeignPartialLayoutScanner.Result.Found
        val candidate = found.candidates.single()
        assertEquals(ForeignPartialLayoutScanner.Kind.QBITTORRENT, candidate.kind)
        assertEquals("Fixture/movie.bin", candidate.declaredPath)

        val adopted = ForeignPartialRebind.adopt(dest, listOf(candidate))
        assertTrue(adopted.toString(), adopted is ForeignPartialRebind.Result.Adopted)

        val receiver = engine()
        val r = added(receiver.addStoppedChecked(torrent, dest))
        assertEquals(0, r.progressPpm)
        assertEquals(LocalCommandOutcome.VERIFIED, receiver.resume(r.infoHash).outcome)
        assertTrue(receiver.connectPeer(r.infoHash, InetSocketAddress("127.0.0.1", seeder.listenPort())))
        waitFor { receiver.status(r.infoHash)?.progressPpm == 1_000_000 }
        val downloaded = requireNotNull(receiver.status(r.infoHash)).payloadDownloadedBytes
        assertTrue("reused pieces must not be refetched: $downloaded",
            downloaded <= data.size - keptPieces * pieceSize.toLong() + pieceSize)
    }
}
