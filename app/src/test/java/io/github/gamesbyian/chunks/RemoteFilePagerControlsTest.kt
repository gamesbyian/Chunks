package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
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
class RemoteFilePagerControlsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun onePageWithUpToFortyFilesHasNoRedundantPagingChrome() {
        rule.setContent {
            ChunksTheme {
                Box(Modifier.width(320.dp)) {
                    RemoteFilePagerControls(FilePages.select(40, 0), 40, {}, {})
                }
            }
        }
        rule.onNodeWithText("Showing 1–40 of 40 files").assertDoesNotExist()
        rule.onNodeWithText("Previous 40").assertDoesNotExist()
        rule.onNodeWithText("Next 40").assertDoesNotExist()
        rule.onNodeWithText("Page 1 of 1").assertDoesNotExist()
    }

    @Test fun largeTextStacksButtonsAndPreservesExactPageNavigation() {
        var selected = 0
        rule.setContent {
            var page by remember { mutableStateOf(0) }
            selected = page
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChunksTheme {
                    Box(Modifier.width(320.dp)) {
                        RemoteFilePagerControls(
                            FilePages.select(81, page),
                            81,
                            onPrevious = { page-- },
                            onNext = { page++ }
                        )
                    }
                }
            }
        }
        val previous = rule.onNodeWithText("Previous 40")
        val next = rule.onNodeWithText("Next 40")
        previous.assertIsNotEnabled()
        val backBounds = previous.getUnclippedBoundsInRoot()
        val forwardBounds = next.getUnclippedBoundsInRoot()
        assertTrue("Narrow navigation controls must stack", forwardBounds.top >= backBounds.bottom)
        assertTrue("Tap targets stay at least 48dp", forwardBounds.bottom - forwardBounds.top >= 48.dp)
        assertEquals(backBounds.left, forwardBounds.left)
        assertEquals(backBounds.right, forwardBounds.right)
        next.performClick()
        rule.onNodeWithText("Showing 41–80 of 81 files").assertExists()
        assertEquals(1, selected)
        rule.onNodeWithText("Previous 40").performClick()
        rule.onNodeWithText("Showing 1–40 of 81 files").assertExists()
        assertEquals(0, selected)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w600dp-h700dp-mdpi")
    fun wideTextKeepsTwoButtonsOnOneRow() {
        rule.setContent {
            ChunksTheme {
                Box(Modifier.width(550.dp)) {
                    RemoteFilePagerControls(FilePages.select(81, 1), 81, {}, {})
                }
            }
        }
        val previous = rule.onNodeWithText("Previous 40").getUnclippedBoundsInRoot()
        val next = rule.onNodeWithText("Next 40").getUnclippedBoundsInRoot()
        assertEquals(previous.top, next.top)
        assertTrue(previous.right < next.left)
    }
}
