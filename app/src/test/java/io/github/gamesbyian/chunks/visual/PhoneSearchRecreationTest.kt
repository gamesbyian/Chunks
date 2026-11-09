package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.gamesbyian.chunks.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Phone search is navigation state that survives Activity recreation (rotation). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class PhoneSearchRecreationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun phoneSearchSurvivesRecreation() {
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText("Search transfers on this device"))
            .performScrollTo().performTextInput("debian")
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText("debian")).assertExists()
    }
}
