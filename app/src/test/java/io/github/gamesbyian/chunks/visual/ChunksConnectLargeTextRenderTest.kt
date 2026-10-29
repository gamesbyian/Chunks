package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
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

    @Test fun connectAt200Percent() {
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/chunks-visual/connect-200pct.png")
    }
}
