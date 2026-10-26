package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.debug.FixtureDocumentsProvider
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.model.ZipExtractionStatus
import java.io.File
import java.util.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * A finished local transfer copied through the real ContentResolver into a
 * debug-only DocumentsProvider tree, while the engine keeps seeding the originals.
 * Not proof for vendor providers, SD cards or the system folder picker.
 */
@RunWith(AndroidJUnit4::class)
class LocalLibraryCopyInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val base get() = FixtureDocumentsProvider.baseDir(context)
    private val tree get() = FixtureDocumentsProvider.treeUri(context)
    private lateinit var work: File
    private var engine: LocalTorrentEngine? = null

    @Before fun setUp() {
        base.deleteRecursively(); base.mkdirs()
        work = File(context.filesDir, "library-copy-test").apply { deleteRecursively(); mkdirs() }
        File(context.filesDir, "library-copies").deleteRecursively()
    }

    @After fun tearDown() {
        engine?.close()
        base.deleteRecursively()
        work.deleteRecursively()
        File(context.filesDir, "library-copies").deleteRecursively()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    @Test fun finishedTransferIsCopiedIntoANewFolderAndKeepsSeeding() {
        val root = File(work, "Library fixture").apply { mkdirs() }
        val random = Random(11)
        val payload = linkedMapOf(
            "Episode 1.bin" to ByteArray(200_000).also { random.nextBytes(it) },
            "Extras/notes.txt" to "notes".toByteArray()
        )
        for ((path, bytes) in payload) File(root, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
        val torrent = TorrentBuilder().path(root).pieceSize(16 * 1024).generate().entry().bencode()
        val local = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0")).also { it.start(); engine = it }
        val added = (local.addStopped(torrent, work) as LocalAddResult.AddedStopped).status
        local.resume(added.infoHash)
        waitFor("seeding") { local.status(added.infoHash)?.state == LocalTransferState.SEEDING }

        val status = requireNotNull(local.status(added.infoHash))
        val source = assertNotNullAndGet(LocalLibraryCopySource.of(local, status))
        assertEquals(setOf("Episode 1.bin", "Extras/notes.txt"), source.files.map { it.outputPath }.toSet())

        // An existing folder of the same name must survive untouched.
        File(base, "Library fixture").mkdirs()
        File(base, "Library fixture/Episode 1.bin").writeText("user's own")

        val result = LocalZipExtraction.copyFinished(context, source.sourceRoot, source.files, status.name, tree,
            allowUnknownFreeSpace = true) { false }
        assertEquals(result.reason, ZipExtractionStatus.EXTRACTED, result.status)
        assertEquals("Library fixture (2)", result.folderName)
        for ((path, bytes) in payload) {
            assertArrayEquals(path, bytes, File(base, "Library fixture (2)/$path").readBytes())
            assertArrayEquals(path, bytes, File(root, path).readBytes())
        }
        assertEquals("user's own", File(base, "Library fixture/Episode 1.bin").readText())
        assertTrue(File(context.filesDir, "library-copies").list().orEmpty().isEmpty())
        assertEquals(LocalTransferState.SEEDING, local.status(added.infoHash)?.state)
    }

    @Test fun unfinishedTransferOffersNoCopySource() {
        val root = File(work, "Unfinished").apply { mkdirs() }
        File(root, "a.bin").writeBytes(ByteArray(40_000).also { Random(5).nextBytes(it) })
        val torrent = TorrentBuilder().path(root).pieceSize(16 * 1024).generate().entry().bencode()
        val elsewhere = File(work, "empty destination").apply { mkdirs() }
        val local = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0")).also { it.start(); engine = it }
        val added = (local.addStopped(torrent, elsewhere) as LocalAddResult.AddedStopped).status
        assertNull(LocalLibraryCopySource.of(local, requireNotNull(local.status(added.infoHash))))
    }

    @Test fun copyReviewUsesCopyWording() {
        rule.setContent {
            MaterialTheme {
                LocalZipExtractionConfirm(
                    LocalZipExtractionPlan("Library fixture", "Downloads", "Library fixture", 200_005, 2,
                        Long.MAX_VALUE / 4, copy = true),
                    onConfirm = {}, onDismiss = {})
            }
        }
        rule.onNodeWithText("Review copy").assertExists()
        rule.onNodeWithText("Transfer: Library fixture").assertExists()
        rule.onNodeWithText("Copy to new folder").assertIsEnabled()
        rule.onNodeWithText("The downloaded originals stay where they are and keep seeding.", substring = true).assertExists()
    }

    private fun <T : Any> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
