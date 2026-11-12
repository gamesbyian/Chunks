package io.github.gamesbyian.chunks

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed local-first app driven as a keyboard user: a real Tab key event
 * takes the window out of touch mode, as a hardware keyboard does. Opening a
 * Local options tool and coming Back returns keyboard focus to the button
 * that opened it. Navigation only; nothing is created, saved or added.
 */
@RunWith(AndroidJUnit4::class)
class LocalToolFocusReturnInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    // A fresh install opens on the evidence-gated Welcome, not the device pane. The
    // capability under test (keyboard focus returns after leaving an Options tool) only
    // needs the persistent footer's Options root, found by its stable id.
    private fun waitForLocal() = rule.waitUntil(20_000) {
        rule.onAllNodesWithTag(ChunksSemantics.FOOTER_OPTIONS).fetchSemanticsNodes().isNotEmpty()
    }

    private fun keyboardReturn(button: String, paneTitle: String) {
        waitForLocal()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_OPTIONS).performClick()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
        rule.waitForIdle()
        rule.onNode(hasText(button) and hasClickAction()).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(10_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, paneTitle))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasText("Back") and hasClickAction()).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(10_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Options"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText(button) and hasClickAction() and
                SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasText(button) and hasClickAction()).assertIsFocused()
    }

    @Test fun keyboardFocusReturnsToSeedingRules() = keyboardReturn("Seeding rules…", "Seeding rules")

    @Test fun keyboardFocusReturnsToZipReview() = keyboardReturn("Review ZIP archives…", "ZIP review")
}
