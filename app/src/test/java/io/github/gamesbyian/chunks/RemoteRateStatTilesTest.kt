package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
class RemoteRateStatTilesTest {
    @get:Rule val rule = createComposeRule()

    private val mixedDownload = RemoteRateSubtotal(
        reportedRows = 1, unknownRows = 3, knownBytesPerSecond = 120, beyondRange = false
    )
    private val absentUpload = RemoteRateSubtotal(
        reportedRows = 0, unknownRows = 4, knownBytesPerSecond = 0, beyondRange = false
    )

    private fun show(widthDp: Int, fontScale: Float) {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                ChunksTheme {
                    Surface {
                        Box(Modifier.width(widthDp.dp).testTag("stat-viewport")) {
                            RemoteRateStatTiles(mixedDownload, absentUpload, started = 24)
                        }
                    }
                }
            }
        }
    }

    @Test fun at320dp200PercentAllThreeFactsRemainFullWidthAndUnclipped() {
        show(320, 2f)
        val container = rule.onNodeWithTag("stat-viewport").getUnclippedBoundsInRoot()
        val down = rule.onNodeWithText("Known 120 B/s").getUnclippedBoundsInRoot()
        val up = rule.onNodeWithText("Not reported").getUnclippedBoundsInRoot()
        val started = rule.onNodeWithText("24").getUnclippedBoundsInRoot()
        assertTrue("Down and Up must stack at 200% text", up.top >= down.bottom)
        assertTrue("Up and Start must stack at 200% text", started.top >= up.bottom)
        listOf(down, up, started).forEach {
            assertTrue("long rate label must stay inside viewport", it.left >= container.left && it.right <= container.right)
        }
        rule.onNodeWithText("Down").assertExists()
        rule.onNodeWithText("Up").assertExists()
        rule.onNodeWithText("Start set").assertExists()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w600dp-h700dp-mdpi")
    fun wideDefaultTextKeepsCompactHorizontalStats() {
        show(550, 1f)
        val down = rule.onNodeWithText("Known 120 B/s").getUnclippedBoundsInRoot()
        val up = rule.onNodeWithText("Not reported").getUnclippedBoundsInRoot()
        val started = rule.onNodeWithText("24").getUnclippedBoundsInRoot()
        assertEquals("three compact stats share one row", down.top, up.top)
        assertEquals("three compact stats share one row", up.top, started.top)
        assertTrue(down.right < up.left)
        assertTrue(up.right < started.left)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun largerPhoneAt200PercentStillStacks() {
        show(412, 2f)
        val down = rule.onNodeWithText("Known 120 B/s").getUnclippedBoundsInRoot()
        val up = rule.onNodeWithText("Not reported").getUnclippedBoundsInRoot()
        assertTrue(up.top >= down.bottom)
    }
}
