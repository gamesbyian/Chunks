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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.RemoteFile
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
class RemoteFilePageRowTest {
    @get:Rule val rule = createComposeRule()
    private val file = RemoteFile("Season 1/Episode 01 The Long Title.mkv", 12345L, 1)

    @Test fun atTwoHundredPercentFileAndActionStayLegibleWithExactTalkBackIdentity() {
        val clicks = intArrayOf(0)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChunksTheme {
                    Box(Modifier.width(288.dp).testTag("file-container")) {
                        RemoteFilePageRow(7, file, "Normal", false, true) { clicks[0]++ }
                    }
                }
            }
        }
        val facts = rule.onNodeWithText("8. Season 1/Episode 01 The Long Title.mkv · 12.1 KiB · Normal")
            .getUnclippedBoundsInRoot()
        val action = rule.onNodeWithContentDescription(
            "Change priority for file 8: Season 1/Episode 01 The Long Title.mkv")
            .getUnclippedBoundsInRoot()
        assertTrue("large text must not compete with the action for horizontal space", action.top >= facts.bottom)
        assertTrue("action height must accommodate accessibility", action.bottom - action.top >= 48.dp)
        val container = rule.onNodeWithTag("file-container").getUnclippedBoundsInRoot()
        assertTrue("action must stay inside its file row", action.left >= container.left && action.right <= container.right)
        rule.onNodeWithContentDescription(
            "Change priority for file 8: Season 1/Episode 01 The Long Title.mkv").performClick()
        assertEquals(1, clicks[0])
    }

    @Config(qualifiers = "w600dp-h700dp-mdpi")
    @Test fun normalWideLayoutKeepsCompactRowAndCanLabelCloseAction() {
        val clicks = intArrayOf(0)
        rule.setContent {
            ChunksTheme {
                Box(Modifier.width(500.dp)) {
                    RemoteFilePageRow(7, file, "High", true, true) { clicks[0]++ }
                }
            }
        }
        val facts = rule.onNodeWithText("8. Season 1/Episode 01 The Long Title.mkv · 12.1 KiB · High")
            .getUnclippedBoundsInRoot()
        val action = rule.onNodeWithContentDescription(
            "Close priority options for file 8: Season 1/Episode 01 The Long Title.mkv")
            .getUnclippedBoundsInRoot()
        assertTrue("wide presentation is horizontal", facts.right < action.left)
        rule.onNodeWithContentDescription(
            "Close priority options for file 8: Season 1/Episode 01 The Long Title.mkv").performClick()
        assertEquals(1, clicks[0])
    }
}
