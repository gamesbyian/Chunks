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
 * The installed phone-first route on a Pixel-class viewport: orientation
 * (environment identity and the All environments switch) and the actual
 * transfer inventory share the first screen, while optional tools are
 * reached from the persistent Options action instead of preceding the list.
 * Nothing here adds, starts or removes a torrent.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class PhoneInventoryFirstViewportTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun orientationAndInventoryShareTheFirstViewport() {
        rule.waitForIdle()
        rule.onNodeWithText("On this phone (experimental)").assertIsDisplayed()
        rule.onNodeWithText("Phone transfers").assertIsDisplayed()
        rule.onNodeWithText("Search local names, aliases, folders or hashes").assertIsDisplayed()
    }

    @Test fun footerOptionsBringsPhoneOptionsAndToolsIntoView() {
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Options on this phone").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Phone options").assertIsDisplayed()
        // Returning to the list is one tap from the same persistent footer.
        rule.onNodeWithContentDescription("Show transfers on this phone").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Phone transfers").assertIsDisplayed()
    }
}
