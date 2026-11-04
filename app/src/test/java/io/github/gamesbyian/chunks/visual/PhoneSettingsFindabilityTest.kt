package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.github.gamesbyian.chunks.MainActivity
import io.github.gamesbyian.chunks.engine.LocalRateLimits
import io.github.gamesbyian.chunks.speedLimitSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phone settings show their current value and provenance, and sit before
 * occasional tools in Phone options. Nothing here saves a setting.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class PhoneSettingsFindabilityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun summaryNamesValueAndWhetherItIsTheDefault() {
        assertEquals("Now: unlimited download and upload (default).",
            speedLimitSummary(LocalRateLimits()))
        assertEquals("Now: download 2048 KiB/s, upload unlimited (your saved limit).",
            speedLimitSummary(LocalRateLimits(2048 * 1024, 0)))
    }

    @Test fun speedLimitsPrecedeOccasionalPhoneTools() {
        rule.waitForIdle()
        val value = rule.onNodeWithText("Now: unlimited download and upload (default).")
            .performScrollTo()
        val speedTop = value.getUnclippedBoundsInRoot().top
        val toolsTop = rule.onNodeWithText("More phone tools").performScrollTo()
            .getUnclippedBoundsInRoot().top
        // performScrollTo moved the viewport down; the summary is now above it.
        assertTrue("speed limits ($speedTop) should come before tools ($toolsTop)",
            rule.onNodeWithText("Now: unlimited download and upload (default).")
                .getUnclippedBoundsInRoot().top < toolsTop)
        rule.onNodeWithText("Phone options").assertExists()
    }
}
