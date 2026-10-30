package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
class RemoteFilePriorityChoicesTest {
    @get:Rule val rule = createComposeRule()
    private val path = "Season 1/Episode 01 Long Name.mkv"

    private fun show(width: Int, fontScale: Float, chosen: MutableList<Int>) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                ChunksTheme {
                    Box(Modifier.width(width.dp).testTag("container")) {
                        RemoteFilePriorityChoices(fileIndex = 7, filePath = path,
                            desired = 1, onChoose = { chosen += it })
                    }
                }
            }
        }
    }

    private fun description(name: String) = "Choose $name priority for file 8: $path"

    @Test fun narrowTwoHundredPercentOptionsStackInBoundsAndSelectExactChoice() {
        val chosen = mutableListOf<Int>()
        show(288, 2f, chosen)
        val container = rule.onNodeWithTag("container").getUnclippedBoundsInRoot()
        val boxes = listOf("Skip", "Normal", "High").map {
            rule.onNodeWithContentDescription(description(it)).getUnclippedBoundsInRoot()
        }
        assertTrue(boxes[0].bottom <= boxes[1].top)
        assertTrue(boxes[1].bottom <= boxes[2].top)
        boxes.forEach {
            assertTrue("48dp-class target", it.bottom - it.top >= 48.dp)
            assertTrue("control within file panel", it.left >= container.left && it.right <= container.right)
        }
        rule.onNodeWithContentDescription(description("High")).performClick()
        assertEquals(listOf(2), chosen)
    }

    @Config(qualifiers = "w600dp-h700dp-mdpi")
    @Test fun wideDefaultTextUsesOneRowAndKeepsExactFileDescriptions() {
        val chosen = mutableListOf<Int>()
        show(500, 1f, chosen)
        val boxes = listOf("Skip", "Normal", "High").map {
            rule.onNodeWithContentDescription(description(it)).getUnclippedBoundsInRoot()
        }
        assertEquals(boxes[0].top, boxes[1].top)
        assertEquals(boxes[1].top, boxes[2].top)
        assertTrue(boxes[0].right < boxes[1].left)
        assertTrue(boxes[1].right < boxes[2].left)
        rule.onNodeWithContentDescription(description("Skip")).performClick()
        assertEquals(listOf(0), chosen)
    }
}
