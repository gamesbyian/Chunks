package io.github.gamesbyian.chunks

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import java.io.File
import java.net.InetSocketAddress
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Smoke test that must also pass against the R8-shrunk qaMinified build: the
 * JNI engine (session, alert callbacks, peer wire, hash checks, cache flush)
 * moves exact bytes between two in-process engines over loopback, and the
 * Compose UI renders. Runs in the ordinary debug suite too.
 */
@RunWith(AndroidJUnit4::class)
class MinifiedBuildSmokeInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var work: File
    private val engines = ArrayList<LocalTorrentEngine>()

    @Before fun setUp() { work = File(context.filesDir, "minified-smoke").apply { deleteRecursively(); mkdirs() } }
    @After fun tearDown() { engines.forEach { it.close() }; work.deleteRecursively() }

    private fun engine() = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0",
        allowMultipleConnectionsPerIp = true)).also { it.start(); engines += it }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for engine state" }
            Thread.sleep(100)
        }
    }

    @Test fun nativeEngineMovesExactBytesAndComposeRenders() {
        // The Connect list composes lazily; at large text the field starts below the fold.
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Seedbox hostname, ruTorrent URL or RPC address"))
        rule.onNodeWithText("Seedbox hostname, ruTorrent URL or RPC address").assertExists()

        val source = File(work, "seed/Smoke").apply { mkdirs() }
        val payload = ByteArray(300_000).also { Random(29).nextBytes(it) }
        File(source, "payload.bin").writeBytes(payload)
        val torrent = TorrentBuilder().path(source).pieceSize(16 * 1024).generate().entry().bencode()

        val seeder = engine()
        val seed = (seeder.addStopped(torrent, requireNotNull(source.parentFile)) as LocalAddResult.AddedStopped).status
        assertEquals(LocalCommandOutcome.VERIFIED, seeder.resume(seed.infoHash).outcome)
        waitFor(30_000) { seeder.status(seed.infoHash)?.progressPpm == 1_000_000 }

        val leecher = engine()
        val destination = File(work, "leech").apply { mkdirs() }
        val leech = (leecher.addStopped(torrent, destination,
            listOf(InetSocketAddress("127.0.0.1", seeder.listenPort()))) as LocalAddResult.AddedStopped).status
        assertEquals(seed.infoHash, leech.infoHash)
        assertEquals(LocalCommandOutcome.VERIFIED, leecher.resume(leech.infoHash).outcome)
        waitFor(60_000) { leecher.status(leech.infoHash)?.progressPpm == 1_000_000 }
        assertTrue(leecher.flushCompletedForLibraryRead(leech.infoHash, timeoutMs = 30_000L))
        assertArrayEquals(payload, File(destination, "Smoke/payload.bin").readBytes())
    }
}
