package io.github.gamesbyian.chunks

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalDeleteDataDispatch
import io.github.gamesbyian.chunks.engine.LocalDeleteDataReceipt
import io.github.gamesbyian.chunks.engine.LocalFileBatchOutcome
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Canonical Details for a Local Device job carries files & priorities and Remove,
 * reusing the guarded device-screen components. Writes take the shared local lock,
 * use the identity shown in the confirmation, read back once and never retry.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1600dp-xxhdpi")
class LocalDetailsOperationsTest {
    @get:Rule val rule = createComposeRule()
    private val hash = "ab".repeat(20)
    private val status = LocalTransferStatus(hash, "Show S01", LocalTransferState.SEEDING, true,
        1_000_000, 10, 10, 0, 0, 0, "/storage/Download/Chunks Downloads", null, nativeReviewToken = "gen-7")

    private class FakePort(var current: LocalTransferStatus?) : LocalDetailsPort {
        val calls = CopyOnWriteArrayList<String>()
        var dataReceipt = LocalDeleteDataReceipt(LocalDeleteDataDispatch.REFUSED, reason = "Folder changed")
        val fileList = listOf(LocalTransferFile(0, "Show S01/e01.mkv", 5, 5, true),
            LocalTransferFile(1, "Show S01/e02.mkv", 5, 0, true))
        override fun status(infoHash: String) = current
        override fun files(infoHash: String) = fileList
        override fun setFileWanted(infoHash: String, index: Int, wanted: Boolean) =
            LocalCommandOutcome.VERIFIED.also { calls += "wanted:$index:$wanted" }
        override fun setFilePriority(infoHash: String, reviewed: LocalTransferFile, priority: Int) =
            LocalFileBatchOutcome.VERIFIED.also { calls += "priority" }
        override fun setFilesWantedBatch(infoHash: String, reviewed: List<LocalTransferFile>, wanted: Boolean) =
            LocalFileBatchOutcome.VERIFIED.also { calls += "batch" }
        override fun removeKeepingDataIfMatching(infoHash: String, name: String, savePath: String, token: String): Boolean {
            calls += "remove:$infoHash:$name:$savePath:$token"
            current = null
            return true
        }
        override fun removeWithDataIfMatching(infoHash: String, name: String, savePath: String, token: String) =
            dataReceipt.also { calls += "removeData:$token" }
    }

    @Before fun freshLocalReadback() {
        val fence = LocalMutationFence.revision
        fence.acceptObservation(fence.snapshot(), emptyList<Any>())
    }

    private fun show(port: FakePort, stale: Boolean = false) = rule.setContent {
        ChunksTheme { LocalDetailsOperations(hash, stale = stale, engine = { port }) }
    }
    private fun waitText(text: String) = rule.waitUntil(5_000) {
        rule.onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun removeKeepingDataUsesTheReviewedIdentityAndReportsReadback() {
        val port = FakePort(status)
        show(port)
        rule.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_REMOVE).assertHeightIsAtLeast(48.dp).performClick()
        waitText("Remove from this device: Show S01")
        rule.onNodeWithText("Infohash: $hash").assertExists()
        rule.onNodeWithText("Remove transfer, keep data").performClick()
        waitText("Removed from this device. Downloaded files were kept.")
        assertEquals(listOf("remove:$hash:Show S01:/storage/Download/Chunks Downloads:gen-7"), port.calls.toList())
        val fence = LocalMutationFence.revision
        fence.acceptObservation(fence.snapshot(), emptyList<Any>())
    }

    @Test fun removeWithDataReportsARefusalAndNeverRetries() {
        val port = FakePort(status)
        show(port)
        rule.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_REMOVE).performClick()
        waitText("Remove from this device: Show S01")
        rule.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_REMOVE_DATA).assertHeightIsAtLeast(48.dp).performClick()
        waitText("Deletion was not requested: Folder changed")
        assertEquals(listOf("removeData:gen-7"), port.calls.toList())
        val fence = LocalMutationFence.revision
        fence.acceptObservation(fence.snapshot(), emptyList<Any>())
    }

    @Test fun aStaleListOrHeldLockWritesNothing() {
        val port = FakePort(status)
        show(port, stale = true)
        rule.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_REMOVE).assertIsNotEnabled()
        rule.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_FILES).assertIsEnabled()
        assertTrue(port.calls.isEmpty())
    }

    @Test fun anotherLocalChangeInFlightRefusesTheWrite() {
        val port = FakePort(status)
        show(port)
        rule.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_REMOVE).performClick()
        waitText("Remove from this device: Show S01")
        val fence = LocalMutationFence.revision
        assertTrue(fence.beginMutation())
        try {
            rule.onNodeWithText("Remove transfer, keep data").performClick()
            waitText("Another change to this device's transfers is in progress")
            assertTrue("nothing written while another change holds the lock", port.calls.isEmpty())
        } finally {
            fence.settledMutation()
            fence.acceptObservation(fence.snapshot(), emptyList<Any>())
        }
    }

    @Test fun filesDisclosureShowsTheExactJobsFiles() {
        val port = FakePort(status)
        show(port)
        rule.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_FILES).assertHeightIsAtLeast(48.dp).performClick()
        waitText("e02.mkv")
        rule.onNodeWithText("Hide files & priorities").assertExists()
        assertTrue("reading files writes nothing", port.calls.isEmpty())
    }
}
