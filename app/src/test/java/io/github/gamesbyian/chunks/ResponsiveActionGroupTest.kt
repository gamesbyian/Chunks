package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
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

    @Test fun narrowTwoHundredPercentActionsWrapTwoAcrossAndRemainClickable() {
        val calls = mutableListOf<String>()
        show(width = 288, fontScale = 2f, calls = calls)
        val container = rule.onNodeWithTag("action-group").getUnclippedBoundsInRoot()
        val labels = listOf("Start…", "Stop…", "Clear")
        val bounds = labels.map { label ->
            rule.onNodeWithText(label).assertExists().getUnclippedBoundsInRoot()
        }
        assertTrue("two actions occupy the first row at 200% text",
            bounds[0].top == bounds[1].top && bounds[0].right <= bounds[1].left)
        assertTrue("the third action wraps without adding a three-row stack",
            bounds[2].top >= bounds[0].bottom)
        bounds.forEach { b ->
            assertTrue("each target is at least 48dp high", b.bottom - b.top >= 48.dp)
            assertTrue("action clipped left", b.left >= container.left)
            assertTrue("action clipped right", b.right <= container.right)
        }
        labels.forEach { rule.onNodeWithText(it).performClick() }
        assertEquals(labels, calls)
    }

    @Test fun longDestructiveActionsAt288dpAnd200PercentWrapWithoutOverlapping() {
        val labels = listOf("Remove transfer and data", "Remove transfer", "Cancel")
        val calls = mutableListOf<String>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChunksTheme {
                    Box(Modifier.width(288.dp)) {
                        ResponsiveActionGroup(Modifier.testTag("action-group")) { actionModifier ->
                            labels.forEach { label ->
                                OutlinedButton(onClick = { calls += label }, modifier = actionModifier) {
                                    Text(label)
                                }
                            }
                        }
                    }
                }
            }
        }
        val region = rule.onNodeWithTag("action-group").getUnclippedBoundsInRoot()
        val targets = labels.map { rule.onNodeWithText(it).getUnclippedBoundsInRoot() }
        assertEquals("first two destructive choices must share a row", targets[0].top, targets[1].top)
        assertTrue("two destructive choices must never overlap", targets[0].right <= targets[1].left)
        assertTrue("third choice must begin below the tallest first-row action",
            targets[2].top >= maxOf(targets[0].bottom, targets[1].bottom))
        targets.forEach {
            assertTrue("even long labels retain 48dp height", it.bottom - it.top >= 48.dp)
            assertTrue("no horizontal clipping in the shared action group",
                it.left >= region.left && it.right <= region.right)
        }
        labels.forEach { rule.onNodeWithText(it).performClick() }
        assertEquals("every operation retains its independent callback", labels, calls)
    }

    @Config(qualifiers = "w600dp-h700dp-mdpi")
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

    /** Details' environment-named actions cap at two across even with room for three. */
    @Test fun longLabelActionsCapAtTwoAcrossOnAWidePhone() {
        val labels = listOf("Review Start / Stop", "Open Seedbox two transfers", "Refresh Seedbox two")
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                ChunksTheme {
                    Box(Modifier.width(380.dp)) {
                        ResponsiveActionGroup(maxColumns = 2) { cell ->
                            labels.forEach { OutlinedButton(onClick = {}, modifier = cell) { Text(it) } }
                        }
                    }
                }
            }
        }
        val b = labels.map { rule.onNodeWithText(it).getUnclippedBoundsInRoot() }
        assertEquals(b[0].top, b[1].top)
        assertTrue(b[0].right <= b[1].left)
        assertTrue("third wraps", b[2].top >= maxOf(b[0].bottom, b[1].bottom))
    }
}
