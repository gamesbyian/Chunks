package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
        // This test explicitly chooses the globally enabled branch. The app
        // intentionally hides the Add choice when global artwork is disabled.
        // Never change provider consent, and restore pre-existing preferences.
        val media = io.github.gamesbyian.chunks.media.ChunksMedia.get(rule.activity)
        val original = media.loadSettings()
        media.updateSettings(original.copy(showArtwork = true, downloadArtwork = true))
        try {
            rule.onNodeWithText("+ Add").performClick()
            rule.onNode(hasSetTextAction() and hasText("Or paste a magnet or torrent link"))
                .performScrollTo().performTextInput("magnet:?xt=urn:btih:" + "b".repeat(40))
            // AddArtworkChoice deliberately waits for a local IO settings read.
            // The actual choice must materialize before any user interaction.
            rule.waitUntil(10_000) {
                rule.onAllNodesWithText("Artwork & metadata").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Artwork & metadata").performScrollTo().performClick()
            val choice = "Automatically identify and fetch artwork & metadata"
            rule.onNodeWithText(choice).performScrollTo().performClick().assertIsOff()
            rule.onNodeWithText("Options").performClick()
            rule.onNodeWithText("+ Add").performClick()
            rule.waitUntil(10_000) {
                rule.onAllNodesWithText("Artwork & metadata").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Artwork & metadata").performScrollTo().performClick()
            rule.onNodeWithText(choice).performScrollTo().assertIsOff()
        } finally {
            media.updateSettings(original)
        }
    }

    @Test fun magnetDraftSurvivesAllEnvironmentsRoundTrip() {
        val draft = "magnet:?xt=urn:btih:" + "a".repeat(40) + "&dn=draft"
        rule.waitForIdle()
        rule.onNodeWithText("+ Add").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText("Or paste a magnet or torrent link"))
            .performScrollTo().performTextInput(draft)
        rule.onNodeWithText("Options").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("+ Add").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText(draft)).assertExists()
    }
}
