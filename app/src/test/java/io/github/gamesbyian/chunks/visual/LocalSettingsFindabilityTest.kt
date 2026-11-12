package io.github.gamesbyian.chunks.visual

import androidx.compose.ui.test.onNodeWithTag
import io.github.gamesbyian.chunks.ChunksSemantics
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
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
 * Local settings show their current value and provenance, and sit before
 * occasional tools in Local options. Nothing here saves a setting.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class LocalSettingsFindabilityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun summaryNamesValueAndWhetherItIsTheDefault() {
        assertEquals("Now: unlimited download and upload (default).",
            speedLimitSummary(LocalRateLimits()))
        assertEquals("Now: download 2048 KiB/s, upload unlimited (your saved limit).",
            speedLimitSummary(LocalRateLimits(2048 * 1024, 0)))
    }

    @Test fun speedLimitsAndOccasionalToolsRemainDiscoverableInTheirCategories() {
        rule.waitForIdle()
        rule.onNodeWithTag(ChunksSemantics.FOOTER_OPTIONS).performClick()
        rule.onNodeWithTag(io.github.gamesbyian.chunks.OptionsCategory.SEEDING.tag).performScrollTo().performClick()
        rule.onNodeWithText("Now: unlimited download and upload (default).").performScrollTo().assertExists()
        rule.onNodeWithText("All options").performScrollTo().performClick()
        rule.onNodeWithTag(io.github.gamesbyian.chunks.OptionsCategory.ABOUT.tag).performScrollTo().performClick()
        rule.onNodeWithText("More local tools").performScrollTo().assertExists()
    }
}
