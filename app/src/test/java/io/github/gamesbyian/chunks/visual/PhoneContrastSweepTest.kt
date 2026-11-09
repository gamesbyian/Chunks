package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.gamesbyian.chunks.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every fully visible, enabled text on the production phone screen, its Add
 * form and Phone options measures at least 4.5:1 against its own rendered
 * background, at 320dp with 200% text. Disabled controls are exempt (WCAG
 * 1.4.3) and skipped by walking each text node's ancestors.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-xxhdpi", fontScale = 2.0f)
class PhoneContrastSweepTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val failures = ArrayList<String>()

    private fun disabled(node: SemanticsNode): Boolean {
        var n: SemanticsNode? = node
        while (n != null) {
            if (n.config.contains(SemanticsProperties.Disabled)) return true
            n = n.parent
        }
        return false
    }

    private fun sweep(tag: String) {
        rule.waitForIdle()
        val h = rule.onRoot().fetchSemanticsNode().size.height
        val density = rule.activity.resources.displayMetrics.density
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        var measured = 0
        for (i in 0 until nodes.fetchSemanticsNodes().size) {
            val n = nodes[i].fetchSemanticsNode()
            val b = n.boundsInRoot
            if (b.height < 12f * density || b.width < 4f || b.top < 0f || b.bottom > h || disabled(n)) continue
            val c = nodes[i].renderedContrast()
            measured++
            if (c < 4.5) failures += "$tag ${"%.2f".format(c)}:1 " +
                n.config.getOrNull(SemanticsProperties.Text)?.joinToString()?.take(70)
        }
        assertTrue("$tag measured nothing", measured > 3)
    }

    @Test fun phoneInventoryAddAndOptionsReadAtAA() {
        sweep("phone")
        rule.onNodeWithText("+ Add").performClick()
        sweep("add-top")
        rule.onNode(hasText("Optional phone download subfolder")).performScrollTo()
        sweep("add-place")
        rule.onNodeWithContentDescription("Options").performClick()
        sweep("options")
        rule.onNode(hasText("Save speed limits")).performScrollTo()
        sweep("options-speed")
        assertTrue("Text below 4.5:1: $failures", failures.isEmpty())
    }
}
