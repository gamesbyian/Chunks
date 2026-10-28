package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalMagnetDiscoveryPromptInstrumentedTest {
    @get:Rule val rule = createComposeRule()
    private val noTracker = "magnet:?xt=urn:btih:" + "b".repeat(40)
    private val tracked = noTracker +
        "&tr=https%3A%2F%2Ftracker.example%2Fannounce"

    @Test fun trackerlessMagnetOffersExplicitOneTapDhtConsent() {
        var enabledCalls = 0
        rule.setContent {
            MaterialTheme {
                LocalMagnetDiscoveryPrompt(
                    magnet = noTracker,
                    dhtEnabled = false,
                    applying = false,
                    blocked = false,
                    onEnable = { enabledCalls++ }
                )
            }
        }
        rule.onNodeWithText("Enable DHT to help this magnet find peers").performClick()
        assertEquals(1, enabledCalls)
        rule.onNodeWithText(
            "Opt-in: DHT shares this phone's IP address with public DHT nodes. " +
                "This does not guarantee peers, metadata or download progress."
        ).assertExists()
    }

    @Test fun existingDhtOrUsableTrackerNeedsNoPrivacyPrompt() {
        rule.setContent {
            MaterialTheme {
                LocalMagnetDiscoveryPrompt(
                    magnet = tracked, dhtEnabled = false,
                    applying = false, blocked = false, onEnable = {})
                LocalMagnetDiscoveryPrompt(
                    magnet = noTracker, dhtEnabled = true,
                    applying = false, blocked = false, onEnable = {})
            }
        }
        rule.onNodeWithText("Enable DHT to help this magnet find peers").assertDoesNotExist()
    }

    @Test fun busyOrPendingSettingCannotSubmitDhtTwice() {
        rule.setContent {
            MaterialTheme {
                LocalMagnetDiscoveryPrompt(
                    magnet = noTracker, dhtEnabled = false,
                    applying = true, blocked = false, onEnable = {})
            }
        }
        rule.onNodeWithText("Enabling DHT…").assertIsNotEnabled()
    }
}
