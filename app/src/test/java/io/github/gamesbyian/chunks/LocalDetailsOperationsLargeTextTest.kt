package io.github.gamesbyian.chunks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalDeleteDataDispatch
import io.github.gamesbyian.chunks.engine.LocalDeleteDataReceipt
import io.github.gamesbyian.chunks.engine.LocalFileBatchOutcome
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Secondary 320dp / 200% text check: every local Details action stays 48dp, unclipped and reachable. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h2400dp-xhdpi")
class LocalDetailsOperationsLargeTextTest {
    @get:Rule val rule = createComposeRule()
    private val hash = "cd".repeat(20)

    @Before fun fresh() {
        LocalDetailsRuns.outcomes.clear()
        val fence = LocalMutationFence.revision
        fence.acceptObservation(fence.snapshot(), emptyList<Any>())
    }

    @Test fun removeConfirmationFitsAtTwoHundredPercent() {
        val status = LocalTransferStatus(hash, "A rather long season pack name for wrapping", LocalTransferState.SEEDING,
            true, 1_000_000, 10, 10, 0, 0, 0, "/storage/emulated/0/Download/Chunks Downloads", null, nativeReviewToken = "t")
        val port = object : LocalDetailsPort {
            override fun status(infoHash: String) = status
            override fun files(infoHash: String) = listOf(LocalTransferFile(0, "pack/e01.mkv", 5, 5, true))
            override fun setFileWanted(infoHash: String, index: Int, wanted: Boolean) = LocalCommandOutcome.VERIFIED
            override fun setFilePriority(infoHash: String, reviewed: LocalTransferFile, priority: Int) = LocalFileBatchOutcome.VERIFIED
            override fun setFilesWantedBatch(infoHash: String, reviewed: List<LocalTransferFile>, wanted: Boolean) = LocalFileBatchOutcome.VERIFIED
            override fun focusEpisodesOnce(infoHash: String, plan: io.github.gamesbyian.chunks.engine.LocalEpisodeFocusPlanner.Plan) = LocalFileBatchOutcome.VERIFIED
            override fun removeKeepingDataIfMatching(infoHash: String, name: String, savePath: String, token: String) = true
            override fun removeWithDataIfMatching(infoHash: String, name: String, savePath: String, token: String) =
                LocalDeleteDataReceipt(LocalDeleteDataDispatch.REFUSED)
        }
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                ChunksTheme { LocalDetailsOperations(hash, current = true, stale = false, engine = { port }) }
            }
        }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_REMOVE).performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Remove transfer, keep data").assertExists() }.isSuccess }
        val root = rule.onRoot().getUnclippedBoundsInRoot()
        for (target in listOf(rule.onNodeWithText("Remove transfer, keep data"), rule.onNodeWithText("Cancel"),
                rule.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_REMOVE_DATA), rule.onNodeWithTag(ChunksSemantics.DETAILS_LOCAL_FILES))) {
            target.assertHeightIsAtLeast(48.dp)
            val b = target.getUnclippedBoundsInRoot()
            assertTrue("not clipped horizontally: $b in $root", b.left >= root.left && b.right <= root.right)
        }
    }
}
