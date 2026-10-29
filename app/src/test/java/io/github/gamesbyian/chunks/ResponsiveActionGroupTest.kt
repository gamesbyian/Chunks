package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class ResponsiveActionGroupTest {
    @get:Rule val rule = createComposeRule()

    private fun show(width: Int, fontScale: Float, calls: MutableList<String>) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = fontScale)) {
                ChunksTheme {
                    Box(Modifier.width(width.dp)) {
                        ResponsiveActionGroup(Modifier.testTag("action-group")) { actionModifier ->
                            listOf("Start…", "Stop…", "Clear").forEach { label ->
                                OutlinedButton(onClick = { calls += label }, modifier = actionModifier) {
                                    Text(label)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun narrowTwoHundredPercentActionsStackInsideViewportAndRemainClickable() {
        val calls = mutableListOf<String>()
        show(width = 288, fontScale = 2f, calls = calls)
        val container = rule.onNodeWithTag("action-group").getUnclippedBoundsInRoot()
        val labels = listOf("Start…", "Stop…", "Clear")
        val bounds = labels.map { label ->
            rule.onNodeWithText(label).assertExists().getUnclippedBoundsInRoot()
        }
        assertTrue("buttons must stack instead of competing for horizontal pixels",
            bounds[0].bottom < bounds[1].top && bounds[1].bottom < bounds[2].top)
        bounds.forEach { b ->
            assertTrue("each target is at least 48dp high", b.height >= 48.dp)
            assertTrue("action clipped left", b.left >= container.left)
            assertTrue("action clipped right", b.right <= container.right)
        }
        labels.forEach { rule.onNodeWithText(it).performClick() }
        assertEquals(labels, calls)
    }

    @Test fun ordinaryWideTextRetainsCompactHorizontalActions() {
        val calls = mutableListOf<String>()
        show(width = 500, fontScale = 1f, calls = calls)
        val bounds = listOf("Start…", "Stop…", "Clear").map {
            rule.onNodeWithText(it).getUnclippedBoundsInRoot()
        }
        assertEquals(bounds[0].top, bounds[1].top)
        assertEquals(bounds[1].top, bounds[2].top)
        assertTrue(bounds[0].right < bounds[1].left)
        assertTrue(bounds[1].right < bounds[2].left)
    }
}
