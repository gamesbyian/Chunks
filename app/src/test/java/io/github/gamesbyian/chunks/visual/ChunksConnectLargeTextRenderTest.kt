package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import io.github.gamesbyian.chunks.ChunksSemantics
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The installed Connect screen at Android's 200% font scale (JVM render, review only). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 2.0f)
class ChunksConnectLargeTextRenderTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForWelcome() = rule.waitUntil(20_000) {
        rule.onAllNodesWithTag(ChunksSemantics.WELCOME_HEADING).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun connectAt200Percent() {
        waitForWelcome()
        rule.onNodeWithContentDescription("Connections").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/connect-200pct.png")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi", fontScale = 1.0f)
    fun newConnectionAtNormalText() {
        waitForWelcome()
        rule.onNodeWithContentDescription("Connections").performClick()
        rule.onNodeWithTag(ChunksSemantics.CONNECTION_ADD).performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/connections-new-profile.png")
    }

    @Test fun localAt200Percent() {
        waitForWelcome()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/local-200pct.png")
    }

    @Test fun createAt200Percent() {
        waitForWelcome()
        rule.onNodeWithContentDescription("Options").performClick()
        rule.onNodeWithTag(io.github.gamesbyian.chunks.OptionsCategory.ABOUT.tag).performScrollTo().performClick()
        rule.onNodeWithText("Create a torrent…").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/create-200pct.png")
    }
}
