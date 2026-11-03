package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Read-only disclosure tests. No torrent engine, RPC, storage or mutation fixture. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class PhoneIntakeDisclosureTest {
    @get:Rule val rule = createComposeRule()

    @Test fun collapsedDefaultShowsActualPlaceAndRequiresAnExplicitTap() {
        var expanded by mutableStateOf(false)
        var taps = 0
        rule.setContent {
            ChunksTheme { Surface {
                PhoneIntakeDisclosure(expanded, pendingReview = false,
                    placeLabel = "Downloads / Chunks Downloads",
                    onToggle = { taps++; expanded = !expanded })
            } }
        }
        rule.onNodeWithText("Add torrent on this phone").assertExists()
        rule.onNodeWithText("Save to: Downloads / Chunks Downloads · Change in Add").assertExists()
        assertEquals(0, taps)
        rule.onNodeWithText("Add torrent on this phone").performClick()
        rule.onNodeWithText("Hide phone Add options").assertExists()
        rule.onNodeWithText("Save to: Downloads / Chunks Downloads · Change in Add")
            .assertDoesNotExist()
        rule.onNodeWithText("Hide phone Add options").performClick()
        rule.onNodeWithText("Add torrent on this phone").assertExists()
        assertEquals(2, taps)
    }

    @Test fun pendingReviewRemainsFindableAndUnknownLocationIsNeverPresentedAsVerified() {
        rule.setContent {
            ChunksTheme { Surface {
                PhoneIntakeDisclosure(expanded = false, pendingReview = true,
                    placeLabel = null, onToggle = {})
            } }
        }
        rule.onNodeWithText("Review torrent on this phone").assertExists()
        rule.onNodeWithText("Download location unverified · Review in Add").assertExists()
        rule.onNodeWithText("Save to:", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
    fun compactEntryRetainsA48DpTargetAt200PercentFont() {
        rule.setContent {
            val normal = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(normal.density, 2f)) {
                ChunksTheme { Surface {
                    PhoneIntakeDisclosure(expanded = false, pendingReview = false,
                        placeLabel = "Phone Downloads", onToggle = {})
                } }
            }
        }
        val button = rule.onNodeWithText("Add torrent on this phone")
        val bounds = button.getUnclippedBoundsInRoot()
        assert(bounds.bottom - bounds.top >= 48.dp)
    }
}
