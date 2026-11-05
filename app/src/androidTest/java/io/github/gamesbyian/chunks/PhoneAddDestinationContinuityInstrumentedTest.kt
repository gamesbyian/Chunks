package io.github.gamesbyian.chunks

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses actual Android exclusive-file/write probes; Robolectric cannot grant this capability. */
@RunWith(AndroidJUnit4::class)
class PhoneAddDestinationContinuityInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun awaitPlace() = rule.waitUntil(20_000) {
        rule.onAllNodesWithText("For this Add only. Saving a phone default is a separate action.")
            .fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun oneTimeDestinationSubfolderAndStoppedChoiceSurviveNavigationAndRecreation() {
        rule.onNodeWithContentDescription("Add torrent on this phone").performClick()
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("Change download location").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Change download location").performScrollTo().performClick()
        rule.onNodeWithText("Chunks app folder").performScrollTo().assertIsEnabled().performClick()
        awaitPlace()
        rule.onNodeWithText("Optional phone download subfolder").performScrollTo().performTextInput("chosen-once")
        rule.onNodeWithText("Start downloading after Add (recommended)").performScrollTo().performClick()
        rule.onNodeWithText("Or paste a magnet or torrent link").performScrollTo()
            .performTextInput("magnet:?xt=urn:btih:" + "b".repeat(40))
        rule.onNodeWithText("All environments…").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Back").performClick()
        awaitPlace()
        rule.onNodeWithText("Optional phone download subfolder").assertTextContains("chosen-once")
        rule.onNodeWithText("Start downloading after Add (recommended)").assertIsOff()
        rule.activityRule.scenario.recreate()
        awaitPlace()
        rule.onNodeWithText("Optional phone download subfolder").assertTextContains("chosen-once")
        rule.onNodeWithText("Start downloading after Add (recommended)").assertIsOff()
    }
}
