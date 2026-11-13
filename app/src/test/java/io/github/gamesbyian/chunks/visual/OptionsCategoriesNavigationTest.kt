package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.ChunksSemantics
import io.github.gamesbyian.chunks.MainActivity
import io.github.gamesbyian.chunks.OptionsCategory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual app routing and rendering; no provider, engine or preference writes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi", fontScale = 1.0f)
class OptionsCategoriesNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun indexShowsAllEightFilledCardsAndEveryLocalDestinationReturns() {
        rule.onNodeWithTag(ChunksSemantics.FOOTER_OPTIONS).performClick()
        for (category in OptionsCategory.entries) {
            rule.onNodeWithTag(category.tag).performScrollTo().assertIsDisplayed()
            if (category !in setOf(OptionsCategory.CONNECTIONS, OptionsCategory.ARTWORK)) {
                rule.onNodeWithTag(category.tag).performClick()
                rule.onNodeWithTag(ChunksSemantics.FOOTER_OPTIONS).assertIsSelected()
                rule.onNodeWithText("All options").performScrollTo().performClick()
            }
        }
        rule.onNode(hasText("Options") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).performScrollTo()
        rule.activity.window.decorView.invalidate()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/options-filled-index.png")
        rule.onNodeWithTag(OptionsCategory.ABOUT.tag).performScrollTo()
        rule.activity.window.decorView.invalidate()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/options-filled-index-bottom.png")
    }

    @Test fun settingSearchReturnsToItsQueryAfterVisitingTheCanonicalControl() {
        rule.onNodeWithTag(ChunksSemantics.FOOTER_OPTIONS).performClick()
        rule.onNode(hasSetTextAction() and hasText("Search options")).performTextInput("speed")
        rule.onNodeWithText("Download and upload limits for this device · in Options")
            .performScrollTo().performClick()
        rule.onNodeWithText("Now: unlimited download and upload (default).").assertIsDisplayed()
        rule.onNodeWithText("All options").performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and hasText("speed")).assertExists()
        rule.onNodeWithText("Download and upload limits for this device · in Options").assertExists()
    }
}
