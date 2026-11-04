package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class CompactRemoteInventoryHeaderTest {
    @get:Rule val rule = createComposeRule()

    @Test fun ordinaryPixelClassShowsNamedScopeCountAndReachableCombinedAction() {
        var opens = 0
        rule.setContent {
            ChunksTheme {
                Box(Modifier.width(412.dp)) {
                    CompactRemoteInventoryHeader(
                        environmentLabel = "Wood", environment = null,
                        loadedCount = 32, view = "main",
                        stale = false, loading = false,
                        onAllEnvironments = { opens++ }
                    )
                }
            }
        }
        rule.onNodeWithText("Wood · 32 transfers").assertIsDisplayed()
        rule.onNodeWithText("All environments…").performClick()
        assertEquals(1, opens)
    }

    @Test
    @Config(qualifiers = "w412dp-h892dp-mdpi")
    fun actual412dpPhoneListGuttersKeepAllEnvironmentsInlineAtNormalType() {
        var opens = 0
        rule.setContent {
            ChunksTheme {
                Box(Modifier.width(376.dp)) {
                    CompactRemoteInventoryHeader(
                        environmentLabel = "Wood", environment = null,
                        loadedCount = 32, view = "main",
                        stale = false, loading = false,
                        onAllEnvironments = { opens++ }
                    )
                }
            }
        }
        val badge = rule.onNodeWithText("Wood · 32 transfers").getUnclippedBoundsInRoot()
        val all = rule.onNodeWithText("All environments…").getUnclippedBoundsInRoot()
        assertTrue("Keep identity and All environments on a single row", badge.top < all.bottom && all.top < badge.bottom)
        assertTrue("All environments must fit the content area", all.right <= 376.dp)
        assertTrue("All environments remains a 48dp target", all.bottom - all.top >= 48.dp)
        rule.onNodeWithText("All environments…").performClick()
        assertEquals(1, opens)
    }

    @Test
    @Config(qualifiers = "w320dp-h700dp-mdpi")
    fun actual320dpPhoneGuttersKeepHeaderAndScopeActionStacked() {
        rule.setContent {
            ChunksTheme {
                Box(Modifier.width(284.dp)) {
                    CompactRemoteInventoryHeader(
                        environmentLabel = "Wood", environment = null,
                        loadedCount = 32, view = "main",
                        stale = false, loading = false, onAllEnvironments = {}
                    )
                }
            }
        }
        val badge = rule.onNodeWithText("Wood · 32 transfers").getUnclippedBoundsInRoot()
        val all = rule.onNodeWithText("All environments…").getUnclippedBoundsInRoot()
        assertTrue("On a genuinely narrow phone, stack rather than squeeze", all.top >= badge.bottom)
    }

    @Test
    @Config(qualifiers = "w320dp-h700dp-mdpi")
    fun largeTextShowsStaleAndNonmainScopeWithoutRawEndpoint() {
        var opens = 0
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                ChunksTheme {
                    Box(Modifier.width(320.dp)) {
                        CompactRemoteInventoryHeader(
                            environmentLabel = "Wood", environment = null,
                            loadedCount = 1, view = "stopped",
                            stale = true, loading = false,
                            onAllEnvironments = { opens++ }
                        )
                    }
                }
            }
        }
        val label = rule.onNodeWithText("Wood · 1 transfer · stopped view · last known")
        label.assertIsDisplayed()
        val action = rule.onNodeWithText("All environments…")
        val badgeRect = label.getUnclippedBoundsInRoot()
        val actionRect = action.getUnclippedBoundsInRoot()
        assertTrue("Large-text navigation must stack beneath environment count",
            actionRect.top >= badgeRect.bottom)
        action.performClick()
        assertEquals(1, opens)
    }
}
