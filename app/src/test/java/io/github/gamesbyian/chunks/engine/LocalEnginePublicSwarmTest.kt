package io.github.gamesbyian.chunks.engine

import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Opt-in real Internet evidence for the phone engine core: download a bounded
 * byte budget of a licensed Internet Archive or official Debian netinst
 * torrent from ordinary BitTorrent peers. tools/public_fixture_collector.py
 * strips every web seed and keeps only the source's official tracker, so any
 * hash-verified byte here came from a peer, never HTTP. No peer delivering within the time limit is reported as
 * inconclusive peer availability, not as an engine defect.
 */
class LocalEnginePublicSwarmTest {
    @get:Rule val temp = TemporaryFolder()
    private var engine: LocalTorrentEngine? = null

    @Before fun native() = DesktopLibtorrent.ensureLoaded()
    @After fun stop() { engine?.close() }

    @Test fun downloadsAVerifiedByteBudgetFromOrdinaryPublicPeers() {
        val torrentPath = System.getenv("CHUNKS_PUBLIC_SWARM_TORRENT").orEmpty()
        assumeTrue("Only in the manual public swarm workflow", torrentPath.isNotBlank())
        val budget = System.getenv("CHUNKS_PUBLIC_SWARM_BUDGET_BYTES")?.toLongOrNull() ?: (16L shl 20)
        val seconds = System.getenv("CHUNKS_PUBLIC_SWARM_SECONDS")?.toLongOrNull() ?: 600L
        require(budget in 1L..(256L shl 20) && seconds in 30L..1800L)

        val instance = LocalTorrentEngine(LocalEngineConfig(enableDht = true)).also { it.start(); engine = it }
        // Be a modest, honest peer while measuring: share, but not unboundedly.
        instance.setRateLimits(LocalRateLimits(uploadBytesPerSecond = 256 * 1024))
        val added = (instance.addStopped(File(torrentPath).readBytes(), temp.newFolder("swarm"))
            as LocalAddResult.AddedStopped).status
        val exported = requireNotNull(instance.exportTorrentFile(added.infoHash))
        assertFalse("The swarm fixture must carry no web seeds",
            String(exported, Charsets.ISO_8859_1).contains("8:url-list"))
        val target = minOf(budget, added.wantedBytes)
        assertTrue(target > 0)

        assertEquals(LocalCommandOutcome.VERIFIED, instance.resume(added.infoHash).outcome)
        val started = System.nanoTime()
        var maxPeers = 0
        var latest = requireNotNull(instance.status(added.infoHash))
        while (latest.doneBytes < target && System.nanoTime() - started < seconds * 1_000_000_000L) {
            Thread.sleep(2_000)
            latest = requireNotNull(instance.status(added.infoHash))
            maxPeers = maxOf(maxPeers, latest.peers)
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000_000L
        val summary = "hash=${added.infoHash} verified=${latest.doneBytes} target=$target " +
            "maxPeers=$maxPeers payloadCounter=${latest.payloadDownloadedBytes} " +
            "trackers=${latest.trackers} seconds=$elapsed"
        assertEquals(LocalCommandOutcome.VERIFIED, instance.pause(added.infoHash).outcome)
        if (latest.doneBytes < target) {
            println("CHUNKS_PUBLIC_SWARM: INCONCLUSIVE $summary")
            fail("INCONCLUSIVE peer availability: $summary")
        }
        // doneBytes counts only pieces libtorrent hash-checked against the
        // torrent; with no web seeds every such piece came from a peer. The
        // network counter lags the stats tick, so it is reported, not asserted.
        println("CHUNKS_PUBLIC_SWARM: VERIFIED $summary")
    }
}
