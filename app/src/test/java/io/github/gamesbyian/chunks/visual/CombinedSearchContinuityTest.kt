package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToNode
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
 * Opening the phone list from All environments and coming Back keeps the
 * Combined search. Navigation only; nothing is added or changed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class CombinedSearchContinuityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun enterExplicitDeviceView() {
        rule.runOnIdle {
            io.github.gamesbyian.chunks.CombinedViewPreferences(rule.activity).save(
                io.github.gamesbyian.chunks.model.UnifiedTransferQuery(
                    scope = io.github.gamesbyian.chunks.model.EnvironmentViewScope.Only(
                        setOf(io.github.gamesbyian.chunks.model.EnvironmentSnapshots.PHONE))))
        }
        rule.activityRule.scenario.recreate()
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText("Search names or hashes").fetchSemanticsNodes().isNotEmpty()
        }
    }


    private fun pane(title: String) = rule.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)).assertExists()

    @Test fun canonicalScopedSearchSurvivesOptionsAndTransfersRoundTrip() {
        enterExplicitDeviceView()
        pane("All environments")
        rule.onNode(hasSetTextAction() and hasText("Search names or hashes"))
            .performScrollTo().performTextInput("ubuntu")
        rule.onNodeWithContentDescription("Options").performClick()
        rule.waitForIdle()
        pane("Options")
        rule.onNodeWithText("Transfers").performClick()
        rule.waitForIdle()
        pane("All environments")
        rule.onNode(hasSetTextAction() and hasText("ubuntu")).assertExists()
    }

}
