package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.MainActivity
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Local options at 200% type on the primary 412dp local: settings and the
 * newly reachable tools keep full-width, readable 48dp targets. Render only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi", fontScale = 2.0f)
class LocalOptionsLargeTextRenderTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun localOptionsStayReadableAt200Percent() {
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Options").performClick()
        rule.waitForIdle()
        for (label in listOf("Seeding rules…", "Privacy & authentication", "Create a torrent…",
                "Review ZIP archives…")) {
            val node = rule.onNodeWithText(label).performScrollTo()
            val box = node.getUnclippedBoundsInRoot()
            assertTrue("$label is a 48dp-class target", box.bottom - box.top >= 48.dp)
        }
        rule.onNodeWithText("Speed limits").performScrollTo()
        rule.onRoot().captureRoboImage("build/chunks-visual/local-options-200pct.png")
    }
}
