package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.onNodeWithTag
import io.github.gamesbyian.chunks.ChunksSemantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.gamesbyian.chunks.MainActivity
import io.github.gamesbyian.chunks.PhoneOptionsShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phone options search finds settings and tools that already exist on the phone screen. A result
 * opens or scrolls to that existing place; searching never changes a value.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class PhoneOptionsSearchTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun indexAnswersOnlyTypedQuestionsByWordPrefix() {
        assertTrue(PhoneOptionsShortcut.search("").isEmpty())
        assertEquals(listOf(PhoneOptionsShortcut.SPEED_LIMITS), PhoneOptionsShortcut.search("throttle"))
        assertTrue(PhoneOptionsShortcut.DHT in PhoneOptionsShortcut.search("dht"))
        assertTrue(PhoneOptionsShortcut.MEDIA in PhoneOptionsShortcut.search("poster"))
        assertTrue(PhoneOptionsShortcut.ZIP_REVIEW in PhoneOptionsShortcut.search("unz"))
        assertTrue(PhoneOptionsShortcut.search("zzqx").isEmpty())
    }

    private fun search(text: String) {
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_OPTIONS).performClick()
        rule.onNode(hasSetTextAction() and hasText("Search options")).performScrollTo().performTextInput(text)
        rule.waitForIdle()
    }

    @Test fun speedResultScrollsToTheExistingSpeedLimitsPanel() {
        search("speed")
        rule.onNodeWithText("Download and upload limits for this device · in Options").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Now: unlimited download and upload (default).").assertIsDisplayed()
        assertEquals("Choosing a result clears the search", 0,
            rule.onAllNodesWithText("Download and upload limits for this device · in Options").fetchSemanticsNodes().size)
    }

    @Test fun collectionNamesResultScrollsFarDownToTheExistingPanel() {
        search("collection")
        val text = "Create empty collection"
        rule.onNodeWithText("Rename or remove collection names · in Options").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(text).assertIsDisplayed()
    }

    @Test fun dhtSearchOpensTheSingleDeviceSettingInOptions() {
        search("dht")
        rule.onNodeWithText("Find peers without trackers · in Options").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Find peers without trackers (DHT)").assertIsDisplayed()
        rule.onNodeWithText("Find peers without trackers · in Options").assertDoesNotExist()
    }

    @Test fun clipboardPrefillLivesInCentralOptionsAndIsAbsentFromAdd() {
        assertTrue(PhoneOptionsShortcut.CLIPBOARD_PREFILL in PhoneOptionsShortcut.search("clipboard"))
        search("clipboard")
        rule.onNodeWithText("Fill an empty Add source from the clipboard · in Options")
            .performScrollTo().performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Fill in a copied magnet or torrent link when Add opens")
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Fill in a copied magnet or torrent link when Add opens")
            .assertIsDisplayed()
        // Moving to Add must not repeat the durable opt-out beside its source.
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).performClick()
        rule.onNodeWithText("Fill in a copied magnet or torrent link when Add opens")
            .assertDoesNotExist()
    }

    @Test fun mediaResultOpensArtworkAndInfo() {
        search("artwork")
        rule.onNodeWithText("Artwork & media info").performScrollTo().performClick()
        // Artwork & info reads its saved settings off the main thread before drawing its switches.
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Show artwork", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun noMatchExplainsHowToSeeEverything() {
        search("zzqx")
        rule.onNodeWithText("No matching option", substring = true).assertExists()
        // Filtering must not leave the entire settings tree behind the results.
        rule.onNodeWithText("Create a torrent…").assertDoesNotExist()
        rule.onNodeWithText("Now: unlimited download and upload (default).").assertDoesNotExist()
    }
}
