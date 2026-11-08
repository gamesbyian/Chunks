package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
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

    @Test fun deviceInventoryOwnsTheFirstViewportWithoutSettingsAboveIt() {
        rule.waitForIdle()
        rule.onNodeWithText("This device").assertIsDisplayed()
        rule.onNodeWithText("Search transfers on this device").assertIsDisplayed()
        rule.onNodeWithText("Options · this device").assertDoesNotExist()
        rule.onNodeWithText("Privacy & authentication").assertDoesNotExist()
    }

    @Test fun footerOptionsBringsPhoneOptionsAndToolsIntoView() {
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Options").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Options · this device").assertIsDisplayed()
        // Privacy settings are an Option on this screen, not a pinned top row.
        rule.onNodeWithText("Privacy & authentication").performScrollTo().assertIsDisplayed()
        // Returning to the list is one tap from the same persistent footer.
        rule.onNodeWithContentDescription("Transfers").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Search transfers on this device").assertIsDisplayed()
    }

    @Test fun anEmptyPhoneSaysSoOnceWithoutAZeroOfZeroCount() {
        rule.waitForIdle()
        rule.onNodeWithText("Your transfers will appear here").assertIsDisplayed()
        rule.onNodeWithText("Add a torrent").assertIsDisplayed()
        // The shared footer may expose the same destination; match the collection safely.
        rule.onAllNodesWithText("Connections").assertAny(hasClickAction())
        rule.onRoot().captureRoboImage("build/chunks-visual/phone-true-empty-welcome-412dp.png")
        rule.onNodeWithText("No transfers on this device yet.").assertDoesNotExist()
        rule.onNodeWithText("Showing 0 of 0 local transfers").assertDoesNotExist()
    }
}
