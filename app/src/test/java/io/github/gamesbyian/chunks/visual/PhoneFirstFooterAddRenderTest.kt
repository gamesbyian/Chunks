package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    @Test fun freshPhoneLaunchKeepsOrientationAndReachesInventoryInOneTap() {
        rule.waitForIdle()
        // Launch does not scroll away from where-am-I and Back, even here.
        rule.onNodeWithText("This device").assertIsDisplayed()
        rule.onNodeWithText("Transfers")
            .assertIsDisplayed().performClick()
        rule.onNodeWithText("Search transfers on this device").assertIsDisplayed()
        // The persistent footer retains direct Add without needing to scroll
        // back through secondary tools.
        rule.onNodeWithText("+ Add").assertIsDisplayed()
    }

    @Test fun footerAddRevealsActualPhoneAddFormAboveFooter() {
        rule.onNodeWithText("This device").assertExists()
        val add = rule.onNodeWithText("+ Add")
        add.assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Add a download on this device").assertIsDisplayed()
        // The button remains actionable after opening the actual in-place form.
        rule.onNodeWithText("+ Add").assertIsDisplayed()
    }
}
