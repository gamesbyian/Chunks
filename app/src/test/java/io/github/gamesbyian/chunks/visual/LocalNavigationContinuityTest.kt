package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.onNodeWithTag
import io.github.gamesbyian.chunks.ChunksSemantics
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
 * Leaving the local device list for All environments and returning keeps the
 * person's typed search (navigation state only; no command or selection).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class LocalNavigationContinuityTest {
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


    @Test fun explicitDeviceScopeAndSearchSurviveRootNavigation() {
        enterExplicitDeviceView()
        rule.onNode(hasSetTextAction() and hasText("Search names or hashes"))
            .performTextInput("ubuntu")
        rule.onNodeWithContentDescription("Options").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(io.github.gamesbyian.chunks.OptionsCategory.CONNECTIONS.tag).assertExists()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_TRANSFERS).performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText("ubuntu")).assertExists()
    }

}
