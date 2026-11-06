package io.github.gamesbyian.chunks.visual

import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.gamesbyian.chunks.*
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
/**
 * Every fully visible text node on Combined (top controls, rows, a row's
 * extra actions) and on its Transfer details measures at least 4.5:1 against
 * its own rendered background. Catches theme defaults such as lavender
 * outlined text on a coloured card. Partially clipped lines at the viewport
 * edge are skipped; no control here is disabled.
 */
class CombinedContrastSweepTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val failures = ArrayList<String>()

    private fun sweep(tag: String) {
        rule.waitForIdle()
        val h = rule.onRoot().fetchSemanticsNode().size.height
        val density = rule.activity.resources.displayMetrics.density
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        var measured = 0
        for (i in 0 until nodes.fetchSemanticsNodes().size) {
            val n = nodes[i].fetchSemanticsNode()
            val b = n.boundsInRoot
            // Whole lines only: a line cut by the viewport edge has no text pixels to measure.
            if (b.height < 12f * density || b.width < 4f || b.top < 0f || b.bottom > h) continue
            val c = nodes[i].renderedContrast()
            measured++
            if (c < 4.5) failures += "$tag ${"%.2f".format(c)}:1 " +
                n.config.getOrNull(SemanticsProperties.Text)?.joinToString()?.take(70)
        }
        assertTrue("$tag measured nothing", measured > 5)
    }

    @Test fun combined() {
        val scope = "https://box.example/rutorrent2/RPC2\u0000ian"
        val rows = listOf(RemoteTransfer("1".repeat(40), "Big Archive", 900, 900, true, "/d", 0, 10),
            RemoteTransfer("2".repeat(40), "Small Episode", 10, 5, false, "/d", null, null))
        rule.setContent { ChunksTheme { Surface(color = ChunksColors.Background) {
            CombinedTransfersScreen(server = CombinedServerSource(scope, rows, stale = false, fullView = true,
                observedAtEpochMillis = System.currentTimeMillis(), lifecycle = CombinedServerLifecycle(
                    begin = { false }, settled = {}, setStarted = { _, _ -> error("no") })),
                savedProfiles = emptyList(), onOpenPhone = {}, onOpenServer = {}, onBack = {})
        } } }
        sweep("combined-top")
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Small Episode"))
        sweep("combined-rows")
        val label = serverEnvironmentLabel(scope)
        rule.onNodeWithContentDescription("Expand Big Archive on $label").performClick()
        sweep("combined-more")
        rule.onNodeWithContentDescription("All details for Big Archive on $label").performClick()
        sweep("detail")
        assertTrue("Text below 4.5:1: $failures", failures.isEmpty())
    }
}
