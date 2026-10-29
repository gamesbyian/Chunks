package io.github.gamesbyian.chunks.engine

import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.Change
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.Mode
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

/**
 * Controlled loopback swarm (#415 item 5): one rate-limited real libtorrent
 * seeder, a baseline leecher with default priorities and a Finish in Order
 * leecher driven by the real runner. The order exactly reverses native file
 * order, so index-order picking cannot finish the first target early by accident.
 *
 * Asserted: under Finish first, when the first ordered file is hash-verified
 * complete, the other files together hold under three quarters of their bytes.
 * Timings are printed as measurements only; one seeder on loopback says
 * nothing about real swarms, peer churn or a phone's radio.
 */
class FinishInOrderSwarmTimingTest {
    @get:Rule val temp = TemporaryFolder()
    private val engines = ArrayList<LocalTorrentEngine>()

    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engines.forEach { it.close() } }

    private class Run(val firstMs: Long, val allMs: Long, val othersAtFirst: Double)

    private fun leecher() = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
        allowMultipleConnectionsPerIp = true)).also { it.start(); engines += it }

    @Test fun finishFirstCompletesTheFirstOrderedFileBeforeMostOtherBytes() {
        val root = File(temp.newFolder("seed"), "Season 1").apply { mkdirs() }
        val random = Random(415)
        for (episode in 1..6) File(root, "Show S01E0$episode.mkv")
            .writeBytes(ByteArray(256 * 1024).also { random.nextBytes(it) })
        val torrent = TorrentBuilder().path(root).pieceSize(16 * 1024)
            .flags(TorrentBuilder.V1_ONLY).generate().entry().bencode()

        val seeder = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
            allowMultipleConnectionsPerIp = true, rateLimitLocalPeers = true)).also { it.start(); engines += it }
        seeder.setRateLimits(LocalRateLimits(uploadBytesPerSecond = 384 * 1024))
        val seed = (seeder.addStopped(torrent, requireNotNull(root.parentFile)) as LocalAddResult.AddedStopped).status
        seeder.resume(seed.infoHash)
        wait(30_000) { seeder.status(seed.infoHash)?.state == LocalTransferState.SEEDING }
        val peer = listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))

        fun measure(label: String, policyMode: Mode?): Run {
            val engine = leecher()
            val hash = (engine.addStopped(torrent, temp.newFolder(label), peer) as LocalAddResult.AddedStopped)
                .status.infoHash
            val files = requireNotNull(engine.files(hash))
            // A user-arranged order that exactly reverses native file order, so
            // index-order piece picking would finish the first target last.
            val order = files.sortedByDescending { it.index }.map { it.index }
            var saved: FinishInOrderRecord? = null
            var record = policyMode?.let { FinishInOrderRecord(FinishInOrderPlanner.policyFor(it, files, order)) }
            val runner = FinishInOrderRunner(object : FinishInOrderRunner.Port {
                override suspend fun observe(infoHash: String) = engine.observeFinishInOrder(infoHash)
                override suspend fun apply(infoHash: String, record: FinishInOrderRecord, changes: List<Change>) =
                    engine.applyFinishInOrder(infoHash, record, changes)
                override fun save(infoHash: String, record: FinishInOrderRecord): Boolean {
                    saved = record; return true
                }
            })
            fun step() { record?.let { current ->
                kotlinx.coroutines.runBlocking { runner.step(hash, current) }
                record = saved ?: current
            } }
            step() // Priorities are in place before the first piece request.
            val started = System.nanoTime()
            assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(hash).outcome)
            var firstMs = -1L
            var othersAtFirst = -1.0
            val othersTotal = files.filter { it.index != order.first() }.sumOf { it.sizeBytes }.toDouble()
            wait(120_000) {
                val seen = requireNotNull(engine.observeFinishInOrder(hash))
                if (firstMs < 0 && seen.single { it.file.index == order.first() }.complete) {
                    firstMs = (System.nanoTime() - started) / 1_000_000
                    othersAtFirst = seen.filter { it.file.index != order.first() }
                        .sumOf { it.verifiedBytes } / othersTotal
                }
                step()
                seen.all { it.complete }
            }
            val run = Run(firstMs, (System.nanoTime() - started) / 1_000_000, othersAtFirst)
            println("FIO_SWARM_TIMING $label: first ordered file ${run.firstMs} ms, all ${run.allMs} ms, " +
                "other files %.0f%% verified when it finished".format(run.othersAtFirst * 100))
            return run
        }

        val baseline = measure("baseline", null)
        val ordered = measure("finish-first", Mode.FINISH_FIRST)
        // libtorrent keeps ~3 s of block requests queued per peer, so once every
        // piece of the active file is requested, Low files are requested too and
        // the seeder serves them in queue order. Strict order is therefore not
        // possible; a random position for the target would average ~83%.
        assertTrue("other files were ${ordered.othersAtFirst} complete", ordered.othersAtFirst < 0.75)
        println("FIO_SWARM_TIMING ratio: first-file time finish-first/baseline = %.2f"
            .format(ordered.firstMs.toDouble() / baseline.firstMs.coerceAtLeast(1)))
    }

    private fun wait(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for engine state")
            Thread.sleep(100)
        }
    }
}
