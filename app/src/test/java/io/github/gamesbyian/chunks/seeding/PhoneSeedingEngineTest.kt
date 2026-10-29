package io.github.gamesbyian.chunks.seeding

import io.github.gamesbyian.chunks.engine.DesktopLibtorrent
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import java.io.File
import java.net.InetSocketAddress
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder

/** The controller driving the real phone engine: verified cap, then a verified pause at a time limit. */
class PhoneSeedingEngineTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = ArrayList<LocalTorrentEngine>()
    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engines.forEach { it.close() } }
    private fun engine() = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
        allowMultipleConnectionsPerIp = true)).also { it.start(); engines += it }

    private class MemStore : SeedingController.AssignmentStore {
        var epochMs: Long? = 0
        val map = HashMap<String, SeedingController.Assignment>()
        override fun epoch() = epochMs
        override fun setEpoch(ms: Long): Boolean { epochMs = ms; return true }
        override fun get(infoHash: String) = map[infoHash]
        override fun put(infoHash: String, assignment: SeedingController.Assignment): Boolean { map[infoHash] = assignment; return true }
    }

    @Test fun capIsAppliedAndTheTimeLimitPausesTheRealTransfer() {
        val root = File(temp.newFolder("seed"), "Fixture").apply { mkdirs() }
        File(root, "data.bin").writeBytes(ByteArray(300_000).also(Random(5)::nextBytes))
        val torrent = TorrentBuilder().path(root).pieceSize(16 * 1024)
            .addTracker("udp://tracker.example:6969/announce").generate().entry().bencode()
        val seeder = engine()
        val seedHash = (seeder.addStopped(torrent, requireNotNull(root.parentFile)) as LocalAddResult.AddedStopped).status.infoHash
        seeder.resume(seedHash)
        waitFor { seeder.status(seedHash)?.state == LocalTransferState.SEEDING }

        val leecher = engine()
        val hash = (leecher.addStopped(torrent, temp.newFolder("peer"),
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))) as LocalAddResult.AddedStopped).status.infoHash
        leecher.resume(hash)
        waitFor { leecher.status(hash)?.state == LocalTransferState.SEEDING }

        val oneSecond = SeedingPolicy(maxSeedSeconds = 1, uploadLimit = RateLimit.parse("24 KiB/s").getOrThrow())
        val rule = SeedingRule("r", "Example", false, setOf("tracker.example"), policy = oneSecond, environments = setOf("phone"))
        val store = MemStore()
        val controller = SeedingController(LocalSeedingPort(leecher), store)
        controller.tick("phone", listOf(rule), SeedingPolicy(), System.currentTimeMillis())
        assertEquals("tracker identity matched the rule", "r", store.map.getValue(hash).ruleId)
        assertEquals(24 * 1024, leecher.uploadLimit(hash))
        waitFor { (leecher.seedingObservation(hash)?.seedingSeconds ?: 0) >= 1 }
        controller.tick("phone", listOf(rule), SeedingPolicy(), System.currentTimeMillis())
        assertTrue("paused by the rule", leecher.status(hash)!!.stopped)
        assertNotNull(store.map.getValue(hash).stoppedByRule)
        assertNotNull("never removed", leecher.status(hash))
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 60_000
        while (!condition()) { assertTrue("timed out", System.currentTimeMillis() < deadline); Thread.sleep(100) }
    }
}
