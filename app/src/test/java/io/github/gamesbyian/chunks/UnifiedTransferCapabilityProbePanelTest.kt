package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.UnifiedTransferDetails
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class UnifiedTransferCapabilityProbePanelTest {
    @get:Rule val rule = createComposeRule()
    private val server = EnvironmentId("saved-server-exact")
    private val key = UnifiedTransferKey.of(server, "1".repeat(40))
    private val now = 10_000L

    private fun detail(locked: Boolean = false) = UnifiedTransferDetails.resolve(
        key,
        listOf(EnvironmentTransferSnapshot(server,
            if (locked) EnvironmentAvailability.LOCKED else EnvironmentAvailability.LIVE,
            inventoryComplete = true, observedAtEpochMillis = now,
            transfers = listOf(UnifiedTransferObservation(key, "One exact transfer",
                "One exact transfer", null, null, null, null, null, null)))),
        null, now
    )

    @Test fun explicitProbeIsReadOnlyOptionalAndAnnouncesFailureAt200PercentText() {
        val busy = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        var userTaps = 0
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChunksTheme { Surface {
                    UnifiedTransferDetailPanel(detail(), "Server · exact / rutorrent2",
                        onBack = {}, onCheckCapabilities = {
                            userTaps++
                            busy.value = true
                        }, capabilityCheckInProgress = busy.value,
                        capabilityCheckError = error.value)
                } }
            }
        }
        assertEquals("No implicit probe during composition", 0, userTaps)
        val check = rule.onNodeWithContentDescription(
            "Check capabilities for Server · exact / rutorrent2")
        check.performScrollTo().performClick()
        assertEquals(1, userTaps)
        rule.onNodeWithText("Checking capabilities…").assertExists()
        rule.runOnIdle {
            busy.value = false
            error.value = "the signed-in server did not answer"
        }
        val region = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        rule.onNode(region).assertExists()
        rule.onNodeWithText(
            "Capability check could not be completed: the signed-in server did not answer")
            .performScrollTo().assertExists()
        // The panel never creates capability successes. Owning adapter must
        // provide a new observed, exact-profile snapshot after its own probe.
        rule.onNodeWithText(
            "Start and stop: Capability has not been checked recently for this exact environment.")
            .performScrollTo().assertExists()
        assertEquals(1, userTaps)
    }

    @Test fun savedServerWithoutAnAdapterNeverOffersAProbe() {
        rule.setContent {
            ChunksTheme { Surface {
                UnifiedTransferDetailPanel(detail(), "Saved, not signed in", onBack = {})
            } }
        }
        rule.onNodeWithText("Check server capabilities").assertDoesNotExist()
        rule.onNodeWithText("Capabilities in this environment").assertExists()
    }

    @Test fun lockedEnvironmentHidesProbeAndPrivateTransferFacts() {
        rule.setContent {
            ChunksTheme { Surface {
                UnifiedTransferDetailPanel(detail(locked = true), "Locked profile", onBack = {},
                    onCheckCapabilities = { error("Must never run") })
            } }
        }
        rule.onNodeWithText("One exact transfer").assertDoesNotExist()
        rule.onNodeWithText("Check server capabilities").assertDoesNotExist()
        rule.onNodeWithText("Exact infohash:", substring = true).assertDoesNotExist()
    }
}
