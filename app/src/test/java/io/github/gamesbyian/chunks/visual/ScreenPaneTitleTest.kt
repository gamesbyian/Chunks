package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.gamesbyian.chunks.MainActivity
import io.github.gamesbyian.chunks.ScreenPaneTitles
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Route changes carry a spoken pane title naming the screen, not its contents. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class ScreenPaneTitleTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun pane(title: String) =
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title))

    @Test fun titlesNameTheScreenAndEnvironmentOnly() {
        assertEquals("Transfers on this device", ScreenPaneTitles.of("LOCAL", "Transfers on this device", null))
        assertEquals("Transfers on Server · alice @ seed.example",
            ScreenPaneTitles.of("TRANSFERS", "Transfers", "Server · alice @ seed.example"))
        assertEquals("Connect to a server", ScreenPaneTitles.of("TRANSFERS", "Transfers", null))
        assertEquals("Seeding rules", ScreenPaneTitles.of("SEEDING", "Seeding rules", null))
    }

    @Test fun movingBetweenPhoneAndAllEnvironmentsChangesThePaneTitle() {
        rule.waitForIdle()
        pane("Transfers on this device").assertExists()
        rule.onNodeWithText("Options").performClick()
        rule.waitForIdle()
        pane("Options").assertExists()
        rule.onNodeWithText("Transfers").performClick()
        rule.waitForIdle()
        pane("All environments").assertExists()
        rule.onNodeWithText("Open transfers on this device").performScrollTo().performClick()
        rule.waitForIdle()
        pane("Transfers on this device").assertExists()
    }
}
