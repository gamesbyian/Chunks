package io.github.gamesbyian.chunks

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalRateLimits
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in real Internet swarm on Android: the app's native libtorrent engine
 * downloads a bounded, hash-verified byte budget of a public torrent from
 * ordinary peers. tools/android_public_swarm.sh copies a collector-sanitized
 * torrent (no web seeds, only the source's official tracker) into the app's
 * cache and passes its name; without it the test is skipped. An emulator
 * behind the runner's NAT, not a physical handset or mobile network.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPublicSwarmInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun nativeEngineDownloadsAVerifiedBudgetFromOrdinaryPublicPeers() {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("chunksSwarmTorrent").orEmpty()
        assumeTrue("Only in the manual Android public swarm workflow", name.isNotBlank())
        require(Regex("[A-Za-z0-9._-]{1,64}").matches(name))
        val budget = args.getString("chunksSwarmBudgetBytes")?.toLongOrNull() ?: (16L shl 20)
        val seconds = args.getString("chunksSwarmSeconds")?.toLongOrNull() ?: 600L
        require(budget in 1L..(256L shl 20) && seconds in 30L..1800L)
        val torrent = File(context.cacheDir, name).readBytes()

        val engine = LocalTorrentEngine(LocalEngineConfig(enableDht = true))
        try {
            engine.start()
            engine.setRateLimits(LocalRateLimits(uploadBytesPerSecond = 256 * 1024))
            val target = File(context.cacheDir, "swarm-${System.nanoTime()}").apply { mkdirs() }
            val added = (engine.addStopped(torrent, target) as LocalAddResult.AddedStopped).status
            val exported = requireNotNull(engine.exportTorrentFile(added.infoHash))
            assertFalse("The swarm fixture must carry no web seeds",
                String(exported, Charsets.ISO_8859_1).contains("8:url-list"))
            val goal = minOf(budget, added.wantedBytes)
            assertTrue(goal > 0)

            assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(added.infoHash).outcome)
            val started = System.nanoTime()
            var maxPeers = 0
            var latest = requireNotNull(engine.status(added.infoHash))
            while (latest.doneBytes < goal && System.nanoTime() - started < seconds * 1_000_000_000L) {
                Thread.sleep(2_000)
                latest = requireNotNull(engine.status(added.infoHash))
                maxPeers = maxOf(maxPeers, latest.peers)
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000_000L
            val summary = "hash=${added.infoHash} verified=${latest.doneBytes} target=$goal " +
                "maxPeers=$maxPeers trackers=${latest.trackers} seconds=$elapsed"
            assertEquals(LocalCommandOutcome.VERIFIED, engine.pause(added.infoHash).outcome)
            target.deleteRecursively()
            if (latest.doneBytes < goal) {
                Log.i("ChunksSwarm", "CHUNKS_ANDROID_SWARM: INCONCLUSIVE $summary")
                fail("INCONCLUSIVE peer availability: $summary")
            }
            // doneBytes counts only pieces libtorrent hash-checked; with no
            // web seeds every one came from a peer.
            Log.i("ChunksSwarm", "CHUNKS_ANDROID_SWARM: VERIFIED $summary")
        } finally {
            engine.close()
        }
    }
}
