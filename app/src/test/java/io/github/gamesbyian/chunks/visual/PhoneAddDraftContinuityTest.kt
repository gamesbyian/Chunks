package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.gamesbyian.chunks.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A typed phone magnet draft survives a visit to All environments.
 * Nothing is added: the draft is only text in process memory.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class PhoneAddDraftContinuityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun artworkOptOutSurvivesAllEnvironmentsRoundTripWithoutAdding() {
        rule.onNodeWithContentDescription("+ Add").performClick()
        rule.onNode(hasSetTextAction() and hasText("Or paste a magnet or torrent link"))
            .performScrollTo().performTextInput("magnet:?xt=urn:btih:" + "b".repeat(40))
        rule.onNodeWithText("Artwork & metadata").performScrollTo().performClick()
        val choice = "Automatically identify and fetch artwork & metadata"
        rule.onNodeWithText(choice).performScrollTo().performClick().assertIsOff()
        rule.onNodeWithText("Options").performScrollTo().performClick()
        rule.onNodeWithText("+ Add").performClick()
        rule.onNodeWithText("Artwork & metadata").performScrollTo().performClick()
        rule.onNodeWithText(choice).performScrollTo().assertIsOff()
    }

    @Test fun magnetDraftSurvivesAllEnvironmentsRoundTrip() {
        val draft = "magnet:?xt=urn:btih:" + "a".repeat(40) + "&dn=draft"
        rule.waitForIdle()
        rule.onNodeWithContentDescription("+ Add").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText("Or paste a magnet or torrent link"))
            .performScrollTo().performTextInput(draft)
        rule.onNodeWithText("Options").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("+ Add").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText(draft)).assertExists()
    }
}
