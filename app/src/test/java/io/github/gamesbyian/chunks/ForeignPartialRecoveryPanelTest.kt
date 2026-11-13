package io.github.gamesbyian.chunks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.ForeignPartialAddPlan
import io.github.gamesbyian.chunks.engine.ForeignPartialLayoutScanner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The recovery choice is off by default, an accessible switch, and disabled while busy. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class ForeignPartialRecoveryPanelTest {
    @get:Rule val rule = createComposeRule()
    private val offer = ForeignPartialAddPlan.Offer("/x", listOf(
        ForeignPartialLayoutScanner.Candidate("Show/a.mkv", "Show/a.mkv.!qB", ForeignPartialLayoutScanner.Kind.QBITTORRENT, 2048)),
        ambiguousFiles = 1, untouchedAuxiliary = 2, truncated = false)

    @Test fun reuseIsOptInAndUntouchedStateIsDisclosed() {
        var use by mutableStateOf(false)
        val choices = ArrayList<Boolean>()
        rule.setContent { ChunksTheme { ForeignPartialRecoveryPanel(offer, use, true) { use = it; choices += it } } }
        rule.onNodeWithText("Found 1 partial file", substring = true).assertExists()
        rule.onNodeWithText("qBittorrent (.!qB)", substring = true).assertExists()
        rule.onNodeWithText("1 file has more than one possible partial copy", substring = true).assertExists()
        rule.onNodeWithText("2 control or fragment files are not supported", substring = true).assertExists()
        val toggle = rule.onNodeWithTag(ChunksSemantics.ADD_FOREIGN_PARTIAL_REUSE)
        toggle.assertIsOff().assertHeightIsAtLeast(48.dp)
        toggle.performClick()
        toggle.assertIsOn()
        assertEquals(listOf(true), choices)
    }

    @Test fun busyReviewCannotChangeTheChoice() {
        rule.setContent { ChunksTheme { ForeignPartialRecoveryPanel(offer, false, false) { error("no change while busy") } } }
        rule.onNodeWithTag(ChunksSemantics.ADD_FOREIGN_PARTIAL_REUSE).assertIsNotEnabled()
    }
}
