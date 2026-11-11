package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import io.github.gamesbyian.chunks.ChunksSemantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.gamesbyian.chunks.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Installed screen's actual phone-first navigation, reproduced in JVM Compose.
 * This verifies visible Add access at 320dp and 200% text without submitting
 * a torrent, probing a remote server or opening any privileged file picker.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi", fontScale = 2.0f)
class PhoneFirstFooterAddRenderTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForWelcome() = rule.waitUntil(20_000) {
        rule.onAllNodesWithTag(ChunksSemantics.WELCOME_HEADING).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun freshPhoneLaunchKeepsOrientationAndReachesInventoryInOneTap() {
        waitForWelcome()
        // Launch does not scroll away from where-am-I and Back, even here.
        rule.onNodeWithTag(ChunksSemantics.WELCOME_HEADING).assertIsDisplayed()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_TRANSFERS)
            .assertIsDisplayed().performClick()
        rule.onNodeWithTag(ChunksSemantics.WELCOME_HEADING).assertExists()
        rule.onNodeWithText("Search names or hashes").assertDoesNotExist()
        // The persistent footer retains direct Add without needing to scroll
        // back through secondary tools.
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).assertIsDisplayed()
    }

    @Test fun footerAddRevealsActualPhoneAddFormAboveFooter() {
        waitForWelcome()
        rule.onNodeWithTag(ChunksSemantics.WELCOME_HEADING).assertExists()
        val add = rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD)
        add.assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Add a transfer").assertExists()
        // At 320dp/200% the destination belongs to the SAME vertically
        // scrollable source-first shell; prove the actual option is reachable
        // rather than assuming every control must fit in the first viewport.
        rule.onNodeWithTag(ChunksSemantics.ADD_TARGET_DEVICE).performScrollTo().assertIsDisplayed()
        // The button remains actionable after opening the actual in-place form.
        rule.onNodeWithTag(ChunksSemantics.FOOTER_ADD).assertIsDisplayed()
    }
}
