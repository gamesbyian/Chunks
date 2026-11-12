package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.onNodeWithTag
import io.github.gamesbyian.chunks.ChunksSemantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.gamesbyian.chunks.ChunksColors
import io.github.gamesbyian.chunks.ChunksTheme
import io.github.gamesbyian.chunks.CombinedServerSource
import io.github.gamesbyian.chunks.CombinedTransfersScreen
import io.github.gamesbyian.chunks.MainActivity
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Names every clickable node whose touch target is under 48dp in either dimension. */
internal fun SemanticsNodeInteractionsProvider.smallTouchTargets(density: Float): List<String> =
    onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { node: SemanticsNode ->
        // Compose always expands pointer touch bounds to 48dp, so audit the laid-out
        // size (which Material's minimumInteractiveComponentSize also raises).
        val visible = node.boundsInRoot
        val w = node.size.width / density
        val h = node.size.height / density
        // A clipped, off-screen node reports zero visible bounds; audit it after scrolling.
        if (visible.width == 0f || visible.height == 0f) null
        else if (w + 0.5f >= 48f && h + 0.5f >= 48f) null else {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            "${desc ?: text ?: "?"} (${w.toInt()}x${h.toInt()}dp)"
        }
    }

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class LocalTouchTargetAuditTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun everyLocalScreenActionHasA48dpTarget() {
        rule.waitForIdle()
        val density = rule.activity.resources.displayMetrics.density
        val small = rule.smallTouchTargets(density).toMutableList()
        // Traverse the real Options destination before auditing its lower controls.
        rule.onNodeWithTag(ChunksSemantics.FOOTER_OPTIONS).performClick()
        rule.onNodeWithText("Save speed limits").performScrollTo()
        rule.waitForIdle()
        small += rule.smallTouchTargets(density)
        rule.onNodeWithText("More local tools").performScrollTo().performClick()
        rule.waitForIdle()
        small += rule.smallTouchTargets(density)
        assertTrue("Touch targets under 48dp: ${small.distinct()}", small.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class CombinedTouchTargetAuditTest {
    @get:Rule val rule = createComposeRule()

    @Test fun everyCombinedActionHasA48dpTarget() {
        val rows = (1..3).map { i -> RemoteTransfer(i.toString(16).padStart(40, '0'),
            "Episode $i", 900, 300, i % 2 == 0, "/d", 0, 10) }
        rule.setContent { ChunksTheme { Surface(color = ChunksColors.Background) {
            CombinedTransfersScreen(server = CombinedServerSource(
                "https://box.example/RPC2\u0000ian", rows, stale = false, fullView = true,
                observedAtEpochMillis = System.currentTimeMillis()),
                savedProfiles = emptyList(), onOpenLocal = {}, onOpenServer = {}, onBack = {})
        } } }
        rule.waitForIdle()
        val density = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>().resources.displayMetrics.density
        val small = rule.smallTouchTargets(density).toMutableList()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Environment colours…"))
        rule.waitForIdle()
        small += rule.smallTouchTargets(density)
        assertTrue("Touch targets under 48dp: ${small.distinct()}", small.isEmpty())
    }
}

/** Negative control: the audit really reports an undersized clickable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TouchTargetAuditSelfTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aTwentyDpClickableIsReported() {
        rule.setContent {
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.size(20.dp)
                    .clickable(onClickLabel = "tiny") {}
                    .semantics { contentDescription = "Tiny action" })
        }
        val density = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>().resources.displayMetrics.density
        val small = rule.smallTouchTargets(density)
        assertTrue("expected the tiny action, got $small", small.any { it.startsWith("Tiny action") })
    }
}
