package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.onNodeWithTag
import io.github.gamesbyian.chunks.ChunksSemantics
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.gamesbyian.chunks.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tools opened from app Options return to Options, not to the
 * server-connection entry. Navigation only; nothing is created or saved.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class PhoneToolReturnTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun onPhone() = rule.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Transfers on this device")).assertExists()

    private fun openAndReturn(button: String, paneTitle: String) {
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_OPTIONS).performClick()
        rule.onNodeWithText(button).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, paneTitle)).assertExists()
        rule.onNode(hasText("Back") and hasClickAction()).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Options")).assertExists()
    }

    @Test fun seedingRulesReturnsToThePhone() = openAndReturn("Seeding rules…", "Seeding rules")

    @Test fun createReturnsToThePhone() = openAndReturn("Create a torrent…", "Create")

    @Test fun zipReviewReturnsToThePhone() = openAndReturn("Review ZIP archives…", "ZIP review")
}
