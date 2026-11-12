package io.github.gamesbyian.chunks

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalEngineAboutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun privacyFactIsAlwaysShownAndBackgroundDetailIsOneLabelledTapAway() {
        rule.setContent { ChunksTheme { LocalEngineAbout() } }
        rule.onNodeWithText("Peers and trackers can see this device's IP address", substring = true)
            .assertExists()
        rule.onNodeWithText("about 6 hours a day", substring = true).assertDoesNotExist()
        rule.onNodeWithText("About local downloads in the background")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .performClick()
        rule.onNodeWithText("about 6 hours a day", substring = true).assertExists()
        rule.onNodeWithText("Hide background details")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    }
}
