package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
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
class CombinedTransfersHeaderTest {
    @get:Rule val rule = createComposeRule()

    private fun show(width: Int, fontScale: Float, clicks: IntArray) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                ChunksTheme {
                    Box(Modifier.width(width.dp).testTag("header-container")) {
                        CombinedTransfersHeader { clicks[0]++ }
                    }
                }
            }
        }
    }

    @Test fun twoHundredPercentNarrowLayoutKeepsBackBelowTitleInBounds() {
        val clicks = intArrayOf(0)
        show(288, 2f, clicks)
        val title = rule.onNodeWithText("All environments").getUnclippedBoundsInRoot()
        val back = rule.onNodeWithText("Back").getUnclippedBoundsInRoot()
        val container = rule.onNodeWithTag("header-container").getUnclippedBoundsInRoot()
        assertTrue("Back must be below the wrapped title", back.top >= title.bottom)
        assertTrue(back.left >= container.left && back.right <= container.right)
        assertTrue("Back needs a 48dp-class target", back.bottom - back.top >= 48.dp)
        rule.onNodeWithText("Back").performClick()
        assertEquals(1, clicks[0])
    }

    @Config(qualifiers = "w600dp-h700dp-mdpi")
    @Test fun widerDefaultTextKeepsHeaderHorizontal() {
        val clicks = intArrayOf(0)
        show(500, 1f, clicks)
        val title = rule.onNodeWithText("All environments").getUnclippedBoundsInRoot()
        val back = rule.onNodeWithText("Back").getUnclippedBoundsInRoot()
        assertTrue("Back must follow title on wide layouts", title.right < back.left)
        rule.onNodeWithText("Back").performClick()
        assertEquals(1, clicks[0])
    }
}
