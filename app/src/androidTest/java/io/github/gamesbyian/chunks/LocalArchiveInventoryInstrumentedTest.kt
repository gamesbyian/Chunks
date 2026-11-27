package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import java.io.File
import java.util.Random
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

@RunWith(AndroidJUnit4::class)
class LocalArchiveInventoryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun canonicalDetailsShowsNamesOnlyAndGapsFromActualNativeRegistration() {
        val root = File(context.cacheDir, "rar-inventory-" + System.nanoTime()).apply { assertTrue(mkdir()) }
        val folder = File(root, "Source").apply { assertTrue(mkdir()) }
        // Deliberately not RAR archives: a filename and native completion
        // counter must never be represented as archive integrity.
        val contents = listOf("Extra.rar", "Extra.r00", "Film.part01.rar", "Film.part03.rar", "Unrelated.bin")
            .mapIndexed { index, name ->
                File(folder, name) to ByteArray(32_768).also { Random(100L + index).nextBytes(it) }
            }.toMap()
        contents.forEach { (file, bytes) -> file.writeBytes(bytes) }
        val engine = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0"))
        try {
            engine.start()
            val torrent = TorrentBuilder().path(folder).pieceSize(16 * 1024).generate().entry().bencode()
            val hash = (engine.addStopped(torrent, root) as LocalAddResult.AddedStopped).status.infoHash
            engine.resume(hash)
            val deadline = System.nanoTime() + 30_000_000_000L
            while (engine.status(hash)?.state != LocalTransferState.SEEDING) {
                if (System.nanoTime() >= deadline) fail("Native RAR inventory fixture did not seed")
                Thread.sleep(100)
            }
            val review = requireNotNull(LocalArchiveInventory.review(engine, hash))
            assertEquals(2, review.candidates.size)
            assertEquals(1_000_000, review.torrentProgressPpm)
            assertTrue(review.allFilesVerified)
            assertEquals(2, review.candidates.first().listedCompleteCount)
            assertEquals(1, review.candidates.last().missingCount)
            assertEquals(2, review.candidates.last().firstMissing)
            assertNull(LocalArchiveInventory.review(engine, "0".repeat(40)))

            val fence = LocalMutationFence.revision
            compose.waitUntil(5_000) {
                LocalDetailsRuns.running.isEmpty() &&
                    fence.acceptObservation(fence.snapshot(), emptyList<Any>())
            }
            val port = EngineLocalDetailsPort(engine)
            compose.setContent {
                ChunksTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        LocalDetailsOperations(hash, current = true, stale = false, engine = { port })
                    }
                }
            }
            compose.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_FILES).performScrollTo().performClick()
            compose.onNodeWithText("Review RAR archive volumes…").performScrollTo().performClick()
            val namesOnly = "All listed volumes report verified pieces; durable disk bytes, the final volume and archive integrity are unknown."
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText(namesOnly)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(namesOnly).assertExists()
            compose.onNodeWithText("Filename candidates and native hash-verified piece counts.", substring = true).assertExists()
            compose.onNodeWithText("Next group").performScrollTo().performClick()
            compose.onNodeWithText("Missing volume number 2 (1 gap in listed numbering).").assertExists()
            compose.onNodeWithText(namesOnly).assertDoesNotExist()
            assertEquals(LocalTransferState.SEEDING, engine.status(hash)?.state)
            contents.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        } finally {
            engine.close()
            root.deleteRecursively()
        }
    }
}
