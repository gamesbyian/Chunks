package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

/**
 * JVM renders of the real installed screens for visual review against
 * docs/design/reference-images. Written to app/build/chunks-visual; they are
 * Robolectric pixels, not device acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ChunksScreenRenderTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun connectScreen() {
        rule.onNodeWithText("This device").assertExists()
        rule.onNodeWithContentDescription("Connections").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/connect.png")
    }

    @Test fun phoneScreen() {
        rule.onNodeWithText("This device").assertExists()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/phone.png")
    }

    @Test fun offlineScreens() {
        rule.onNodeWithText("This device").assertExists()
        rule.onNodeWithContentDescription("Connections").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("More offline tools"))
        rule.onNodeWithText("More offline tools").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Inspect a local ZIP without connecting"))
        rule.onNodeWithText("Inspect a local ZIP without connecting").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/zip-review.png")
    }

    @Test fun privacyScreen() {
        rule.onNodeWithText("This device").assertExists()
        rule.waitForIdle()
        // Privacy & authentication lives in Phone options, reached from the footer.
        rule.onNodeWithContentDescription("Options").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Privacy & authentication").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Privacy for transfers on this device is off", substring = true).assertExists()
        rule.onRoot().captureRoboImage("build/chunks-visual/privacy.png")
    }

    @Test fun createScreen() {
        rule.onNodeWithText("This device").assertExists()
        rule.onNodeWithContentDescription("Connections").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("More offline tools"))
        rule.onNodeWithText("More offline tools").performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Create a torrent from phone files"))
        rule.onNodeWithText("Create a torrent from phone files").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/create.png")
    }
}
