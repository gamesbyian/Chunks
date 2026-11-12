package io.github.gamesbyian.chunks

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalOutcomeNoticeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun outcomeIsAnnouncedPolitelyWithItsExactWording() {
        val text = "Added and started. Check peer availability and downloaded bytes in the transfer list."
        rule.setContent { ChunksTheme { LocalOutcomeNotice(text) } }
        rule.onNodeWithText(text).assertExists()
        // The text is merged into the live-region node itself, so its change is announced.
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
            .and(hasText(text)))
            .assertExists()
    }

    @Test fun anAddOutcomeCanOfferOneViewOnlyFollowUpAsItsOwnTarget() {
        var shown = 0
        rule.setContent { ChunksTheme {
            LocalOutcomeNotice("Magnet added.", actionLabel = "Show new transfer", onAction = { shown++ })
        } }
        val action = rule.onNodeWithText("Show new transfer")
        val box = action.getUnclippedBoundsInRoot()
        assertTrue("follow-up is a 48dp-class target", box.bottom - box.top >= 48.dp)
        action.performClick()
        assertEquals(1, shown)
    }

    @Test fun ordinaryOutcomesOfferNoAction() {
        rule.setContent { ChunksTheme { LocalOutcomeNotice("Nothing was added.") } }
        rule.onAllNodesWithText("Show new transfer").assertCountEquals(0)
    }

    @Test fun problemOutcomesSayNeedsAttentionInWordsAndOthersDoNot() {
        var problem by androidx.compose.runtime.mutableStateOf(true)
        rule.setContent { ChunksTheme {
            LocalOutcomeNotice("Magnet Add was not confirmed.", needsAttention = problem)
        } }
        rule.onNodeWithText("Needs attention").assertExists()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
            .and(hasText("Needs attention", substring = true))
            .and(hasText("Magnet Add was not confirmed.", substring = true))).assertExists()
        problem = false
        rule.waitForIdle()
        rule.onAllNodesWithText("Needs attention").assertCountEquals(0)
    }
}
