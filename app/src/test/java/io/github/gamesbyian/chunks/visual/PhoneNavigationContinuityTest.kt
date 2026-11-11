package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * Fresh Welcome hides unused phone search; the canonical catalog instead
 * preserves its typed query across root Options/Transfers navigation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class PhoneNavigationContinuityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun catalogSearchSurvivesOptionsAndTransfersRootRoundTrip() {
        rule.waitForIdle()
        rule.onNodeWithText("Welcome to Chunks").assertExists()
        rule.onNodeWithText("Transfers").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("All environments").assertExists()
        rule.onNode(hasSetTextAction() and hasText("Search names or hashes"))
            .performScrollTo().performTextInput("ubuntu")
        rule.onNodeWithText("Options").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Options · this device").assertExists()
        rule.onNodeWithText("Transfers").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("All environments").assertExists()
        rule.onNode(hasSetTextAction() and hasText("ubuntu")).assertExists()
    }
}
