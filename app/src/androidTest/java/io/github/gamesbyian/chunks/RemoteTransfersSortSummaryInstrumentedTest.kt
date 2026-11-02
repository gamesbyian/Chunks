package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Presentation-only regression: the narrow remote list must not spend a
 * separate row on the count while keeping sort and count audible and tappable.
 * Synthetic dimensions/font scale here are component evidence, not an
 * installed 320x700/200%-system-font acceptance receipt.
 */
@RunWith(AndroidJUnit4::class)
class RemoteTransfersSortSummaryInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun narrowLargeTypeCombinesCountAndSortIntoOneAccessibleButton() {
        var sortTaps = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        RemoteTransfersSortSummary(32, "Date added", onCycleSort = { sortTaps++ })
                    }
                }
            }
        }
        val button = rule.onNodeWithContentDescription(
            "32 transfers shown. Sort by Date added. Change sort"
        )
        button.assertIsDisplayed()
        rule.onNodeWithText("32 shown · Sort: Date added", useUnmergedTree = true)
            .assertExists()
        assertTrue("A compact sort control still needs a 48dp touch target",
            button.fetchSemanticsNode().boundsInRoot.height >= 48f)
        rule.onNodeWithText("32 shown", useUnmergedTree = true).assertDoesNotExist()
        button.performClick()
        assertEquals(1, sortTaps)
    }

    @Test fun normalWidthRetainsSeparateCountAndSort() {
        var sortTaps = 0
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(412.dp)) {
                    RemoteTransfersSortSummary(0, "Name", onCycleSort = { sortTaps++ })
                }
            }
        }
        rule.onNodeWithText("0 shown").assertIsDisplayed()
        rule.onNodeWithText("Sort: Name").assertIsDisplayed().performClick()
        assertEquals(1, sortTaps)
    }
}
