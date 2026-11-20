package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stable, client-neutral Save to and one-shot Add controls at the review boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UniversalAddReviewControlsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun onlyTheSelectedClientsPlaceAppearsAndDoesNotWrite() {
        val place = mutableStateOf("This device / Downloads")
        val clicks = mutableStateOf(0)
        rule.setContent {
            ChunksTheme {
                Surface {
                    androidx.compose.foundation.layout.Column {
                        UniversalAddSaveToSection { Text(place.value) }
                        UniversalAddSubmitAction(onClick = { clicks.value++ }, enabled = true)
                    }
                }
            }
        }
        rule.onNodeWithText("Save to").assertExists()
        rule.onNodeWithText("This device / Downloads").assertExists()
        rule.runOnIdle { place.value = "Wood / incoming" }
        rule.onNodeWithText("This device / Downloads").assertDoesNotExist()
        rule.onNodeWithText("Wood / incoming").assertExists()
        rule.runOnIdle { assertEquals(0, clicks.value) }
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, clicks.value) }
    }

    @Test fun unavailableAddIsVisibleExplainedAndNeverDispatches() {
        val clicks = mutableStateOf(0)
        rule.setContent {
            ChunksTheme {
                UniversalAddSubmitAction(
                    onClick = { clicks.value++ },
                    enabled = false,
                    unavailableReason = "Choose a verified location")
            }
        }
        rule.onNodeWithTag(ChunksSemantics.ADD_SUBMIT).assertIsNotEnabled()
        rule.onNode(hasStateDescription("Unavailable: Choose a verified location")).assertExists()
        rule.runOnIdle { assertEquals(0, clicks.value) }
    }
}
