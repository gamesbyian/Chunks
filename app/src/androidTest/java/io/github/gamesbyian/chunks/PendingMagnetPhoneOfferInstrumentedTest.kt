package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
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
class PendingMagnetPhoneOfferInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test fun pendingMagnetMayBeReviewedOnPhoneWithoutLoggingIntoServer() {
        var selectedPhone = 0
        var discarded = 0
        rule.setContent {
            MaterialTheme {
                PendingMagnetPhoneOffer(true, false,
                    onReviewPhone = { selectedPhone++ },
                    onDiscard = { discarded++ })
            }
        }
        rule.onNodeWithText("Magnet link received").assertExists()
        rule.onNodeWithText("Review magnet on this phone").performClick()
        assertEquals(1, selectedPhone)
        assertEquals(0, discarded)
    }

    @Test fun disconnectDuringLoginCannotRedirectAnInflightConnectionToPhone() {
        var selectedPhone = 0
        rule.setContent {
            MaterialTheme {
                PendingMagnetPhoneOffer(true, true,
                    onReviewPhone = { selectedPhone++ }, onDiscard = {})
            }
        }
        rule.onNodeWithText("Review magnet on this phone").assertIsNotEnabled()
        rule.onNodeWithText("Discard pending magnet").assertIsNotEnabled()
        assertEquals(0, selectedPhone)
    }

    @Test fun ordinaryConnectionScreenWithoutPendingMagnetRendersNoShareControls() {
        rule.setContent {
            MaterialTheme {
                PendingMagnetPhoneOffer(false, false,
                    onReviewPhone = {}, onDiscard = {})
            }
        }
        rule.onNodeWithText("Magnet link received").assertDoesNotExist()
    }
}
