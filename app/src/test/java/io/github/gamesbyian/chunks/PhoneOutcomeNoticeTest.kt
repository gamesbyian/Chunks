package io.github.gamesbyian.chunks

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneOutcomeNoticeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun outcomeIsAnnouncedPolitelyWithItsExactWording() {
        val text = "Added and started. Check peer availability and downloaded bytes in the transfer list."
        rule.setContent { ChunksTheme { PhoneOutcomeNotice(text) } }
        rule.onNodeWithText(text).assertExists()
        // The text is merged into the live-region node itself, so its change is announced.
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
            .and(hasText(text)))
            .assertExists()
    }
}
