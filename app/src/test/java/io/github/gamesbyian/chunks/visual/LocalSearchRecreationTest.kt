package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.onAllNodesWithText
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

/** Local search is navigation state that survives Activity recreation (rotation). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class LocalSearchRecreationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun enterExplicitDeviceView() {
        rule.runOnIdle {
            io.github.gamesbyian.chunks.CombinedViewPreferences(rule.activity).save(
                io.github.gamesbyian.chunks.model.UnifiedTransferQuery(
                    scope = io.github.gamesbyian.chunks.model.EnvironmentViewScope.Only(
                        setOf(io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL))))
        }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("Search names or hashes").fetchSemanticsNodes().isNotEmpty()
        }
    }


    @Test fun localSearchSurvivesRecreation() {
        enterExplicitDeviceView()
        rule.onNode(hasSetTextAction() and hasText("Search names or hashes"))
            .performScrollTo().performTextInput("debian")
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText("debian")).assertExists()
    }
}
