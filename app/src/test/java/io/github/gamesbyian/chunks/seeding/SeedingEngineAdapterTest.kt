package io.github.gamesbyian.chunks.seeding

import io.github.gamesbyian.chunks.engine.DesktopLibtorrent
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import java.io.File
import java.math.BigDecimal
import java.net.InetSocketAddress
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

/** The phone engine's per-transfer cap readback and real counters on a loopback swarm. */
class SeedingEngineAdapterTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = ArrayList<LocalTorrentEngine>()
    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engines.forEach { it.close() } }
    private fun engine() = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
        allowMultipleConnectionsPerIp = true)).also { it.start(); engines += it }

    @Test fun capIsReadBackAndCountersDriveTheDecision() {
        val root = File(temp.newFolder("seed"), "Fixture").apply { mkdirs() }
        File(root, "data.bin").writeBytes(ByteArray(600_000).also(Random(3)::nextBytes))
        val torrent = TorrentBuilder().path(root).pieceSize(16 * 1024).generate().entry().bencode()
        val seeder = engine()
        val seedHash = (seeder.addStopped(torrent, requireNotNull(root.parentFile)) as LocalAddResult.AddedStopped).status.infoHash
        seeder.resume(seedHash)
        waitFor { seeder.status(seedHash)?.state == LocalTransferState.SEEDING }

        assertTrue(seeder.setUploadLimit(seedHash, 24 * 1024L))
        assertEquals(24 * 1024, seeder.uploadLimit(seedHash))
        assertTrue("removing the cap is read back too", seeder.setUploadLimit(seedHash, null))
        assertEquals(0, seeder.uploadLimit(seedHash))
        assertFalse(seeder.setUploadLimit("0".repeat(40), 1000L))

        val leecher = engine()
        val leechHash = (leecher.addStopped(torrent, temp.newFolder("peer"),
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))) as LocalAddResult.AddedStopped).status.infoHash
        leecher.resume(leechHash)
        waitFor { leecher.status(leechHash)?.state == LocalTransferState.SEEDING }
        waitFor { (seeder.seedingObservation(seedHash)?.uploadedBytes ?: 0) >= 600_000 }

        val seederSeen = requireNotNull(seeder.seedingObservation(seedHash))
        assertEquals("the original seeder downloaded nothing", 0L, seederSeen.downloadedBytes)
        assertTrue(SeedingEnforcement.decide(SeedingPolicy(ratioGoal = BigDecimal.ONE), seederSeen)
            is SeedingEnforcement.Decision.CannotJudge)
        val leecherSeen = requireNotNull(leecher.seedingObservation(leechHash))
        assertTrue(leecherSeen.downloadedBytes >= 600_000)
        assertTrue(leecherSeen.seeding)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!condition()) { assertTrue("timed out", System.currentTimeMillis() < deadline); Thread.sleep(100) }
    }
}
