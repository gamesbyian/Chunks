package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.model.CapabilityEvidence
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentCapabilitySnapshot
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentOperation
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.TransferDetailReadState
import io.github.gamesbyian.chunks.model.UnifiedTransferDetails
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import io.github.gamesbyian.chunks.rpc.RemoteStallEvidence
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-xhdpi")
class UnifiedTransferDetailPanelTest {
    @get:Rule val rule = createComposeRule()
    private val id = EnvironmentId("remote-instance-two")
    private val key = UnifiedTransferKey.of(id, "e".repeat(40))
    private val now = 800_000L

    private fun detail(availability: EnvironmentAvailability = EnvironmentAvailability.LIVE) =
        UnifiedTransferDetails.resolve(key, listOf(EnvironmentTransferSnapshot(
            environment = id, availability = availability, inventoryComplete = true,
            observedAtEpochMillis = now - 10,
            transfers = listOf(UnifiedTransferObservation(
                key = key, displayName = "Accessible selected transfer",
                originalName = "Example file collection", sizeBytes = 1024,
                progressPpm = 555_000, started = true,
                downloadBytesPerSecond = null, uploadBytesPerSecond = 0,
                peers = null, hasError = true, ratioThousandths = 1450,
                reportedSaveFolder = "/home/seed/long named folder"))
        )), EnvironmentCapabilitySnapshot(id, "rTorrent on server two", now - 20,
            mapOf(EnvironmentOperation.START_STOP to CapabilityEvidence.ADVERTISED,
                EnvironmentOperation.FILE_PRIORITIES to CapabilityEvidence.MISSING)), now)

    @Test fun narrowScreenAt200PercentKeepsTruthAndActionsReachable() {
        var backs = 0
        var refreshes = 0
        var opens = 0
        val state = detail()
        rule.setContent {
            val original = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(original.density, 2f)) {
                ChunksTheme {
                    Surface {
                        UnifiedTransferDetailPanel(state, "Server two / rutorrent2",
                            onBack = { backs++ },
                            onRefreshOwningList = { refreshes++ },
                            onOpenOwningList = { opens++ })
                    }
                }
            }
        }
        rule.onNodeWithText("Transfer details").assertExists()
        rule.onNodeWithText("Environment: Server two / rutorrent2").assertExists()
        rule.onNodeWithText("Accessible selected transfer").assertExists()
        rule.onNodeWithText("Artwork & media information").assertExists()
        rule.onNodeWithText("Change identification or artwork…").performScrollTo().performClick()
        rule.onNodeWithText("Close media identification").performScrollTo().performClick()
        rule.onNodeWithText("Needs attention: an error was reported.").assertExists()
        rule.onNodeWithText("Start state set; peer activity has not been verified.").assertExists()
        rule.onNodeWithText("Exact infohash:", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Progress: 55.5%").assertExists()
        rule.onNodeWithText("Download rate: not reported").assertExists()
        rule.onNodeWithText("Save folder reported by engine: /home/seed/long named folder")
            .assertExists()
        rule.onNodeWithText("A reported folder does not confirm those files exist", substring = true)
            .assertExists()
        rule.onNodeWithText("Peers: not reported").assertExists()
        rule.onNodeWithText("Share ratio: 1.45").assertExists()
        rule.onRoot().captureRoboImage("build/chunks-visual/details-320x700-200pct.png")
        rule.onNodeWithText("Refresh Server two / rutorrent2").performScrollTo().performClick()
        rule.onNodeWithText("Open Server two / rutorrent2 transfers").performScrollTo().performClick()
        rule.onNodeWithText("Show technical details").performScrollTo().performClick()
        rule.onNodeWithText("Exact infohash: " + "e".repeat(40)).performScrollTo().assertExists()
        rule.onNodeWithText("Start and stop:", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("File list and priorities:", substring = true).performScrollTo().assertExists()
        rule.onRoot().captureRoboImage("build/chunks-visual/details-capabilities-320x700-200pct.png")
        rule.onNodeWithText("Back to transfers").performScrollTo().performClick()
        assertEquals(1, backs)
        assertEquals(1, opens)
        assertEquals(1, refreshes)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-xhdpi")
    fun regularScreenShowsStaleEvidenceWithoutSuccessClaim() {
        val state = detail(EnvironmentAvailability.STALE)
        assertEquals(TransferDetailReadState.STALE, state.readState)
        rule.setContent {
            ChunksTheme {
                Surface { UnifiedTransferDetailPanel(state, "Server two", onBack = {}) }
            }
        }
        rule.onNodeWithText("Accessible selected transfer").assertExists()
        rule.onNodeWithText("Last known: Needs attention: an error was reported.").assertExists()
        rule.onNodeWithText("Observation:", substring = true).assertExists()
        rule.onNodeWithText("Last-known transfer facts only.", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("Show technical details").performScrollTo().performClick()
        rule.onNodeWithText("Start and stop:", substring = true).performScrollTo().assertExists()
        rule.onRoot().captureRoboImage("build/chunks-visual/details-stale-412x892.png")
    }

    @Test fun incompleteInventoryLabelsVisibleCachedFactsAsUnconfirmed() {
        val snapshot = EnvironmentTransferSnapshot(
            id, EnvironmentAvailability.LIVE, false, now - 10,
            listOf(UnifiedTransferObservation(
                key, "Cached title", "Cached title", 1024, 800_000, true, 12, 0, 0))
        )
        val state = UnifiedTransferDetails.resolve(key, listOf(snapshot), null, now)
        assertEquals(TransferDetailReadState.INCOMPLETE, state.readState)
        rule.setContent {
            ChunksTheme {
                Surface { UnifiedTransferDetailPanel(state, "Server two", onBack = {}) }
            }
        }
        rule.onNodeWithText("Cached title").assertExists()
        rule.onNodeWithText("Save folder reported by engine: not reported").assertExists()
        rule.onNodeWithText("Peers: 0").assertExists()
        rule.onNodeWithText("Share ratio: not reported").assertExists()
        rule.onNodeWithText("Last-known transfer facts only.", substring = true)
            .performScrollTo().assertExists()
        rule.onNodeWithText("Capability unknown.", substring = true).assertDoesNotExist()
    }

    @Test fun lockedSourceNeverLeaksItsNameAndDoesNotOfferSourceNavigation() {
        val state = detail(EnvironmentAvailability.LOCKED)
        rule.setContent {
            ChunksTheme {
                Surface {
                    UnifiedTransferDetailPanel(state, "Locked server", onBack = {},
                        onOpenOwningList = {}, onRefreshOwningList = {})
                }
            }
        }
        rule.onNodeWithText("Accessible selected transfer").assertDoesNotExist()
        rule.onNodeWithText("Save folder reported by engine:", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Exact infohash:", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Open Locked server transfers").assertDoesNotExist()
        rule.onNodeWithText("Refresh Locked server").assertDoesNotExist()
        rule.onNodeWithText("Capabilities in this environment").assertDoesNotExist()
        rule.onNodeWithText("Artwork & media information").assertDoesNotExist()
        rule.onNodeWithText("Environment: Locked server").assertExists()
        rule.onRoot().captureRoboImage("build/chunks-visual/details-locked-320x700.png")
    }

    @Test fun exactStallInspectionIsReachableAt320And200PercentWithoutLeakingServerMessages() {
        var inspected = 0
        val observation = RemoteStallEvidence(
            key.infoHash, started = true, active = false, open = false,
            hashingPhase = 0, hashCheckFailed = false, hashChecking = false,
            awaitingMetadata = true,
            messageClass = RemoteStallEvidence.MessageClass.TRACKER_OR_NETWORK
        )
        rule.setContent {
            val original = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(original.density, 2f)) {
                ChunksTheme {
                    Surface {
                        UnifiedTransferDetailPanel(detail(), "Server two / rutorrent2",
                            onBack = {}, onInspectStall = { inspected++ },
                            stallReport = observation)
                    }
                }
            }
        }
        rule.onNodeWithText("Inspect transfer state").performScrollTo().performClick()
        assertEquals(1, inspected)
        rule.onNodeWithText("Server requested Start:", substring = true).performScrollTo().assertExists()
        rule.onNodeWithText("Raw messages and tracker details are withheld.", substring = true)
            .performScrollTo().assertExists()
        rule.onNodeWithText("passkey", substring = true).assertDoesNotExist()
    }

    @Test fun lockedSourceCannotOfferReadOnlyStallProbeEvenIfCallbackExists() {
        rule.setContent {
            ChunksTheme {
                Surface {
                    UnifiedTransferDetailPanel(detail(EnvironmentAvailability.LOCKED),
                        "Locked server", onBack = {}, onInspectStall = {})
                }
            }
        }
        rule.onNodeWithText("Inspect transfer state").assertDoesNotExist()
        rule.onNodeWithText("Why is this transfer inactive?").assertDoesNotExist()
    }

    @Test fun completedStoppedDownloadRemainsIdentifiableWithoutClaimingPeerProof() {
        val completed = UnifiedTransferObservation(
            key = key, displayName = "Finished archive", originalName = "Finished archive",
            sizeBytes = 1024, progressPpm = 1_000_000, started = false,
            downloadBytesPerSecond = 0, uploadBytesPerSecond = 0, peers = null
        )
        assertEquals("Download complete; stopped in the last report.", transferAtAGlance(completed))
        assertEquals("Stopped in the last report.",
            transferAtAGlance(completed.copy(progressPpm = 900_000)))
        assertEquals("Needs attention: an error was reported.",
            transferAtAGlance(completed.copy(hasError = true)))
        assertEquals("Download complete; sharing activity not confirmed.",
            transferAtAGlance(completed.copy(started = true)))
    }

}
