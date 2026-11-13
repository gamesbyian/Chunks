package io.github.gamesbyian.chunks

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import java.io.File
import java.util.Random
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.libtorrent4j.TorrentBuilder

/**
 * Canonical local Details operations against the Android native engine (emulator, shipped
 * POSIX backend): the file list, Remove keeping data, and Remove with exact payload deletion
 * that spares an unrelated sibling. Not physical-device or SAF/MediaStore proof.
 */
@RunWith(AndroidJUnit4::class)
class LocalDetailsOperationsInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var work: File
    private val engines = ArrayList<LocalTorrentEngine>()

    @Before fun setUp() {
        work = File(context.filesDir, "details-ops-test").apply { deleteRecursively(); mkdirs() }
        val fence = LocalMutationFence.revision
        fence.acceptObservation(fence.snapshot(), emptyList<Any>())
    }

    @After fun tearDown() {
        engines.forEach { it.close() }
        work.deleteRecursively()
    }

    private fun seededJob(name: String): Triple<LocalTorrentEngine, String, File> {
        val parent = File(work, name).apply { mkdirs() }
        val root = File(parent, "Chunks details fixture").apply { mkdirs() }
        File(root, "a.bin").writeBytes(ByteArray(200_000).also { Random(1).nextBytes(it) })
        File(root, "b.bin").writeBytes(ByteArray(120_000).also { Random(2).nextBytes(it) })
        val torrent = TorrentBuilder().path(root).pieceSize(16 * 1024).generate().entry().bencode()
        val engine = LocalTorrentEngine(LocalEngineConfig(listenInterfaces = "127.0.0.1:0")).also { it.start(); engines += it }
        val job = (engine.addStopped(torrent, parent) as LocalAddResult.AddedStopped).status
        assertEquals(LocalCommandOutcome.VERIFIED, engine.resume(job.infoHash).outcome)
        rule.waitUntil(60_000) { engine.status(job.infoHash)?.state == LocalTransferState.SEEDING }
        assertEquals(LocalCommandOutcome.VERIFIED, engine.pause(job.infoHash).outcome)
        return Triple(engine, job.infoHash, root)
    }

    private fun show(engine: LocalTorrentEngine, hash: String) = rule.setContent {
        ChunksTheme { LocalDetailsOperations(hash, stale = false, engine = { EngineLocalDetailsPort(engine) }) }
    }

    private fun waitText(text: String) = rule.waitUntil(30_000) {
        rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun filesThenRemoveKeepingDataLeavesEveryByte() {
        val (engine, hash, root) = seededJob("keep")
        val before = root.listFiles()!!.associate { it.name to it.readBytes() }
        show(engine, hash)
        rule.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_FILES).performScrollTo().performClick()
        waitText("b.bin")
        rule.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_REMOVE).performScrollTo().performClick()
        waitText("Remove from this device:")
        rule.onNodeWithText("Remove transfer, keep data").performScrollTo().performClick()
        waitText("Downloaded files were kept")
        assertNull(engine.status(hash))
        for ((name, bytes) in before) assertArrayEquals(name, bytes, File(root, name).readBytes())
    }

    @Test fun removeWithDataDeletesExactlyThePayloadAndSparesASibling() {
        val (engine, hash, root) = seededJob("data")
        val sibling = File(root.parentFile, "unrelated.txt").apply { writeText("keep me") }
        show(engine, hash)
        rule.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_REMOVE).performScrollTo().performClick()
        waitText("Remove from this device:")
        rule.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_REMOVE_DATA).performScrollTo().performClick()
        waitText("Verified: the transfer was removed")
        assertNull(engine.status(hash))
        assertFalse(File(root, "a.bin").exists())
        assertFalse(File(root, "b.bin").exists())
        assertTrue("unrelated sibling kept", sibling.isFile && sibling.readText() == "keep me")
    }
}
