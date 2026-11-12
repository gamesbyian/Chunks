package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import io.github.gamesbyian.chunks.ChunksSemantics
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
 * The installed local-first route on a Pixel-class viewport: orientation
 * (environment identity and the All environments switch) and the actual
 * transfer inventory share the first screen, while optional tools are
 * reached from the persistent Options action instead of preceding the list.
 * Nothing here adds, starts or removes a torrent.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class LocalInventoryFirstViewportTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    // A fresh canonical catalog can legitimately wait on durable resume
    // discovery; an unobserved empty native list is not first-use evidence.
    private fun waitForWelcome() = rule.waitUntil(20_000) {
        rule.onAllNodesWithTag(ChunksSemantics.WELCOME_HEADING).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun genuineFirstRunShowsWelcomeBeforeAnyManagementToolbar() {
        waitForWelcome()
        rule.onNodeWithTag(ChunksSemantics.WELCOME_HEADING).assertIsDisplayed()
        rule.onNodeWithText("This device").assertDoesNotExist()
        rule.onNodeWithText("Search transfers on this device").assertDoesNotExist()
        rule.onNodeWithText("Search names or hashes").assertDoesNotExist()
        rule.onNodeWithText("Batch tools").assertDoesNotExist()
        rule.onNodeWithText("Options · this device").assertDoesNotExist()
        rule.onNodeWithText("Privacy & authentication").assertDoesNotExist()
    }

    @Test fun footerOptionsBringsLocalOptionsAndToolsIntoView() {
        waitForWelcome()
        rule.onNodeWithContentDescription("Options").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Options · this device").assertIsDisplayed()
        // Privacy settings are an Option on this screen, not a pinned top row.
        rule.onNodeWithText("Privacy & authentication").performScrollTo().assertIsDisplayed()
        // The footer now enters the single canonical catalog in every mode.
        rule.onNodeWithTag(ChunksSemantics.FOOTER_TRANSFERS).performClick()
        waitForWelcome()
        rule.onNodeWithTag(ChunksSemantics.WELCOME_HEADING).assertExists()
        rule.onNodeWithText("Search names or hashes").assertDoesNotExist()
    }

    @Test fun aGenuinelyEmptyFirstRunShowsWelcomeNotAnEmptyTransferCount() {
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.WELCOME_HEADING).assertIsDisplayed()
        rule.onNodeWithText("Your transfers will appear here").assertDoesNotExist()
        rule.onNodeWithTag(ChunksSemantics.WELCOME_ADD).assertIsDisplayed()
        // The shared footer may expose the same destination; match the collection safely.
        rule.onAllNodesWithText("Connections").assertAny(hasClickAction())
        rule.onRoot().captureRoboImage("build/chunks-visual/local-true-empty-welcome-412dp.png")
        rule.onNodeWithText("No transfers on this device yet.").assertDoesNotExist()
        rule.onNodeWithText("Showing 0 of 0 local transfers").assertDoesNotExist()
    }
}
