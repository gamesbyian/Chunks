package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gamesbyian.chunks.media.ChunksMedia
import io.github.gamesbyian.chunks.media.MediaPrivacySettings
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Installed Add-choice semantics only; no provider requests or BitTorrent writes. */
@RunWith(AndroidJUnit4::class)
class AddArtworkChoiceInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val media by lazy {
        ChunksMedia.get(InstrumentationRegistry.getInstrumentation().targetContext)
    }
    private lateinit var previous: MediaPrivacySettings
    private val label = "Automatically identify and fetch artwork & metadata"

    @Before fun preserveSettings() {
        previous = media.loadSettings()
    }

    @After fun restoreSettings() {
        media.updateSettings(previous)
    }

    @Test fun defaultCheckedInAddWithoutOnlineConsentAndExplicitOptOutWorks() {
        media.updateSettings(MediaPrivacySettings(
            onlineLookupConsent = false, automaticLookup = false,
            showArtwork = true, downloadArtwork = true))
        val automatic = mutableStateOf(true)
        rule.setContent {
            MaterialTheme {
                AddArtworkChoice(automatic = automatic.value, enabled = true,
                    onAutomatic = { automatic.value = it })
            }
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("Artwork & metadata").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Artwork & metadata").performClick()
        rule.onNodeWithText(label).assertIsOn()
        rule.onNodeWithText(label).performClick().assertIsOff()
        rule.runOnIdle {
            org.junit.Assert.assertFalse(automatic.value)
            org.junit.Assert.assertFalse(media.loadSettings().onlineLookupConsent)
        }
    }

    @Test fun globallyHiddenArtworkDoesNotOfferAddChoice() {
        media.updateSettings(MediaPrivacySettings(
            onlineLookupConsent = true, automaticLookup = true,
            showArtwork = false, downloadArtwork = true))
        rule.setContent {
            MaterialTheme {
                AddArtworkChoice(automatic = true, enabled = true, onAutomatic = {})
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Artwork & metadata").assertDoesNotExist()
        rule.onNodeWithText(label).assertDoesNotExist()
    }

    @Test fun globallyDisabledArtworkCollectionDoesNotOfferAddChoice() {
        media.updateSettings(MediaPrivacySettings(
            onlineLookupConsent = true, automaticLookup = true,
            showArtwork = true, downloadArtwork = false))
        rule.setContent {
            MaterialTheme {
                AddArtworkChoice(automatic = true, enabled = true, onAutomatic = {})
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Artwork & metadata").assertDoesNotExist()
        rule.onNodeWithText(label).assertDoesNotExist()
    }
}
