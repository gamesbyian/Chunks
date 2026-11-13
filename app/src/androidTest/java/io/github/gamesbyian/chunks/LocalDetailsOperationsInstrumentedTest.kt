package io.github.gamesbyian.chunks

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.foundation.verticalScroll
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
        // Let this test's own change settle, then accept a readback so the next test starts unlocked.
        runCatching { rule.waitUntil(30_000) { LocalDetailsRuns.running.isEmpty() } }
        LocalMutationFence.revision.let { it.acceptObservation(it.snapshot(), emptyList<Any>()) }
        LocalDetailsRuns.outcomes.clear()
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

    // Hosted in a vertical scroll, as canonical Details is.
    private fun show(engine: LocalTorrentEngine, hash: String) = rule.setContent {
        ChunksTheme {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier
                .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                LocalDetailsOperations(hash, current = true, stale = false, engine = { EngineLocalDetailsPort(engine) })
            }
        }
    }

    /** Wait for an enabled control, then tap it once; a timeout reports the semantics tree. */
    private fun tap(tag: String) {
        try {
            rule.waitUntil(30_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes()
                .any { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Disabled) { null } == null } }
        } catch (timeout: Throwable) {
            throw AssertionError("$tag never became actionable; fence=${LocalMutationFence.revision.blocksMutations()} " +
                "running=${LocalDetailsRuns.running.keys} tree=" +
                runCatching { rule.onRoot(useUnmergedTree = true).printToString() }.getOrDefault("?"), timeout)
        }
        rule.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun waitText(text: String) = rule.waitUntil(30_000) {
        rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun filesThenRemoveKeepingDataLeavesEveryByte() {
        val (engine, hash, root) = seededJob("keep")
        val before = root.listFiles()!!.associate { it.name to it.readBytes() }
        show(engine, hash)
        tap(ChunksSemantics.DETAILS_LOCAL_FILES)
        waitText("b.bin")
        tap(ChunksSemantics.DETAILS_LOCAL_REMOVE)
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
        tap(ChunksSemantics.DETAILS_LOCAL_REMOVE)
        waitText("Remove from this device:")
        tap(ChunksSemantics.DETAILS_LOCAL_REMOVE_DATA)
        waitText("Verified: the reviewed transfer was removed")
        assertNull(engine.status(hash))
        assertFalse(File(root, "a.bin").exists())
        assertFalse(File(root, "b.bin").exists())
        assertTrue("unrelated sibling kept", sibling.isFile && sibling.readText() == "keep me")
    }
}
