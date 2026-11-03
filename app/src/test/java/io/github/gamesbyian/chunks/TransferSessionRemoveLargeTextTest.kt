package io.github.gamesbyian.chunks

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.rpc.SessionEraseOutcome
import io.github.gamesbyian.chunks.rpc.SessionEraseRecheck
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual review UI, no server access. In particular, Cancel must not dispatch d.erase. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
class TransferSessionRemoveLargeTextTest {
    @get:Rule val rule = createComposeRule()
    private val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:1/RPC2", "", ""))
    private val transfer = RemoteTransfer(
        hash = "c".repeat(40), name = "Long public example to keep readable",
        sizeBytes = 512, completedBytes = 512, started = false,
        directory = "/downloads/specific-profile"
    )
    private var mutations = 0

    private fun show() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChunksTheme {
                    Surface {
                      Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        TransferSessionRemoveControl(
                            remote, transfer, snapshotStale = false, mainView = true,
                            onMutationStarted = { mutations++; false }, onSettled = {}
                        )
                      }
                    }
                }
            }
        }
    }

    @Test fun confirmAndCancelGrowWith200PercentTextAt320DpWithoutMutation() {
        show()
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.onNodeWithText("Confirm removal from rTorrent").assertExists()
        rule.onNodeWithText("Infohash: " + "c".repeat(40)).assertExists()
        rule.onNodeWithText("Current directory: /downloads/specific-profile").assertExists()
        rule.onNodeWithText("Remove transfer and data (unavailable)").assertExists()
        val confirm = rule.onNodeWithText("Remove transfer, keep data").performScrollTo().getUnclippedBoundsInRoot()
        val cancel = rule.onNodeWithText("Cancel").performScrollTo().getUnclippedBoundsInRoot()
        assertTrue("Confirm needs a minimum 52dp touch target", confirm.bottom - confirm.top >= 52.dp)
        assertTrue("Cancel needs a minimum 48dp touch target", cancel.bottom - cancel.top >= 48.dp)
        rule.onNodeWithText("Cancel").performScrollTo().performClick()
        rule.onNodeWithText("Remove transfer, keep data").assertDoesNotExist()
        assertEquals("Cancel must not request a remote mutation", 0, mutations)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
    fun exactRemovalReviewRemainsReachableOn412Dp() {
        show()
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.onNodeWithText("Remove transfer, keep data").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, mutations)
    }


    @Test fun pendingRemovalNeedsReadOnlyMatchingIdentityBeforeFreshConfirmedRetry() {
        var eraseCalls = 0
        var checks = 0
        rule.setContent {
            ChunksTheme {
                Surface {
                    TransferSessionRemoveControl(
                        remote, transfer, snapshotStale = false, mainView = true,
                        onMutationStarted = { true }, onSettled = {},
                        performErase = { _, _, _ ->
                            eraseCalls++
                            SessionEraseOutcome.PendingRemoval(transfer.hash)
                        },
                        recheck = { _, _, _ ->
                            checks++
                            SessionEraseRecheck.PresentMatching
                        }
                    )
                }
            }
        }
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.onNodeWithText("Remove transfer, keep data").performClick()
        rule.waitForIdle()
        assertEquals(1, eraseCalls)
        rule.onNodeWithText("Remove transfer, keep data").assertDoesNotExist()
        rule.onNodeWithText("Check current session (read-only)").performClick()
        rule.waitForIdle()
        assertEquals(1, checks)
        assertEquals("Read-only check must never dispatch a second remove", 1, eraseCalls)
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        assertEquals(1, eraseCalls)
        rule.onNodeWithText("Remove transfer, keep data").performClick()
        rule.waitForIdle()
        assertEquals(2, eraseCalls)
    }

    @Test fun removalReviewShowsServerAccountAndClosesIfScopeChanges() {
        var label by mutableStateOf("Server · alice @ host.example/rutorrent/")
        rule.setContent {
            ChunksTheme { Surface {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    TransferSessionRemoveControl(
                        remote, transfer, snapshotStale = false, mainView = true,
                        onMutationStarted = { mutations++; false }, onSettled = {},
                        environmentLabel = label
                    )
                }
            } }
        }
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.onNodeWithText("Server · alice @ host.example/rutorrent/").assertExists()
        rule.runOnIdle { label = "Server · bob @ host.example/rutorrent2/" }
        rule.onNodeWithText("Remove transfer, keep data").assertDoesNotExist()
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.onNodeWithText("Server · bob @ host.example/rutorrent2/").assertExists()
        assertEquals(0, mutations)
    }

    @Test fun scopeLabelChangeDoesNotClearAnUncertainRemovalLock() {
        var label by mutableStateOf("First account")
        var eraseCalls = 0
        rule.setContent {
            ChunksTheme { Surface {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    TransferSessionRemoveControl(
                        remote, transfer, snapshotStale = false, mainView = true,
                        onMutationStarted = { true }, onSettled = {}, environmentLabel = label,
                        performErase = { _, _, _ ->
                            eraseCalls++
                            SessionEraseOutcome.Uncertain(transfer.hash)
                        }
                    )
                }
            } }
        }
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.onNodeWithText("Remove transfer, keep data").performScrollTo().performClick()
        rule.waitForIdle()
        rule.runOnIdle { label = "Changed display label" }
        rule.onNodeWithText("Remove from rTorrent…").assertDoesNotExist()
        rule.onNodeWithText("Check current session (read-only)").assertExists()
        assertEquals(1, eraseCalls)
    }

    @Test fun staleOrDifferentViewRequiresANewRemovalReview() {
        var stale by mutableStateOf(false)
        var mainView by mutableStateOf(true)
        rule.setContent {
            ChunksTheme { Surface {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    TransferSessionRemoveControl(
                        remote, transfer, snapshotStale = stale, mainView = mainView,
                        onMutationStarted = { mutations++; false }, onSettled = {}
                    )
                }
            } }
        }
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.runOnIdle { stale = true }
        rule.onNodeWithText("Remove transfer, keep data").assertDoesNotExist()
        rule.runOnIdle { stale = false }
        rule.onNodeWithText("Remove transfer, keep data").assertDoesNotExist()
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.runOnIdle { mainView = false }
        rule.onNodeWithText("Remove transfer, keep data").assertDoesNotExist()
        rule.runOnIdle { mainView = true }
        rule.onNodeWithText("Remove transfer, keep data").assertDoesNotExist()
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.onNodeWithText("Remove transfer, keep data").assertExists()
        assertEquals(0, mutations)
    }

    @Test fun unidentifiedScopeCannotStartRemoval() {
        rule.setContent {
            ChunksTheme { Surface {
                TransferSessionRemoveControl(
                    remote, transfer, snapshotStale = false, mainView = true,
                    onMutationStarted = { mutations++; true }, onSettled = {},
                    environmentLabel = null
                )
            } }
        }
        rule.onNodeWithText("Remove from rTorrent…").assertIsNotEnabled()
        rule.onNodeWithText("The server account could not be identified.", substring = true).assertExists()
        assertEquals(0, mutations)
    }

    @Test fun scopeLabelChangeDoesNotUnlockAnInFlightRemoval() {
        var label by mutableStateOf("First account")
        var stale by mutableStateOf(false)
        var eraseCalls = 0
        val result = CompletableDeferred<SessionEraseOutcome>()
        rule.setContent {
            ChunksTheme { Surface {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    TransferSessionRemoveControl(
                        remote, transfer, snapshotStale = stale, mainView = true,
                        onMutationStarted = { true }, onSettled = {}, environmentLabel = label,
                        performErase = { _, _, _ -> eraseCalls++; result.await() }
                    )
                }
            } }
        }
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.onNodeWithText("Remove transfer, keep data").performScrollTo().performClick()
        rule.runOnIdle { label = "Changed display label"; stale = true }
        rule.runOnIdle { stale = false }
        rule.onNodeWithText("Remove from rTorrent…").assertDoesNotExist()
        rule.onNodeWithText("Checking exact server session…").assertExists()
        assertEquals(1, eraseCalls)
        rule.runOnIdle { result.complete(SessionEraseOutcome.Uncertain(transfer.hash)) }
        rule.waitForIdle()
        rule.onNodeWithText("Check current session (read-only)").assertExists()
        assertEquals(1, eraseCalls)
    }

    @Test fun absentAfterUncertainRemovalNeverOffersAnotherDelete() {
        var eraseCalls = 0
        rule.setContent {
            ChunksTheme {
                Surface {
                    TransferSessionRemoveControl(
                        remote, transfer, snapshotStale = false, mainView = true,
                        onMutationStarted = { true }, onSettled = {},
                        performErase = { _, _, _ ->
                            eraseCalls++
                            SessionEraseOutcome.Uncertain(transfer.hash)
                        },
                        recheck = { _, _, _ -> SessionEraseRecheck.Absent }
                    )
                }
            }
        }
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.onNodeWithText("Remove transfer, keep data").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Check current session (read-only)").performClick()
        rule.waitForIdle()
        assertEquals(1, eraseCalls)
        rule.onNodeWithText("Remove transfer, keep data").assertDoesNotExist()
        rule.onNodeWithText("The exact transfer is now absent", substring = true).assertExists()
    }


    @Test fun readdedSameHashCanRecoverFromPreviousVerifiedRemoveWithoutBlindRetry() {
        var removeCalls = 0
        var reads = 0
        rule.setContent {
            ChunksTheme {
                Surface {
                    TransferSessionRemoveControl(
                        remote, transfer, snapshotStale = false, mainView = true,
                        onMutationStarted = { true }, onSettled = {},
                        performErase = { _, _, _ ->
                            removeCalls++
                            SessionEraseOutcome.VerifiedRemoved(transfer.hash)
                        },
                        recheck = { _, _, _ ->
                            reads++
                            // The same exact hash was re-added between refreshes.
                            SessionEraseRecheck.PresentMatching
                        }
                    )
                }
            }
        }
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.onNodeWithText("Remove transfer, keep data").performClick()
        rule.waitForIdle()
        assertEquals(1, removeCalls)
        rule.onNodeWithText("Check current session (read-only)").performClick()
        rule.waitForIdle()
        assertEquals(1, reads)
        assertEquals(1, removeCalls)
        rule.onNodeWithText("Remove from rTorrent…").assertExists()
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.onNodeWithText("Remove transfer, keep data").performClick()
        rule.waitForIdle()
        assertEquals(2, removeCalls)
    }

    @Test fun deniedMutationGateDoesNotTrapRemoveForever() {
        show()
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        // The 200%-font confirmation is below the viewport. A real tap must
        // scroll it into view first; an off-screen click is not a sent command.
        rule.onNodeWithText("Remove transfer, keep data").performScrollTo().performClick()
        assertEquals("The denied mutation gate must have been invoked exactly once", 1, mutations)
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("Remove from rTorrent…").fetchSemanticsNodes().isNotEmpty()
        }
        // A new review is allowed; no second mutation is dispatched implicitly.
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.onNodeWithText("Remove transfer, keep data").assertExists()
        assertEquals(1, mutations)
    }

    @Test fun staleInventoryNeverOffersDestructiveConfirmation() {
        rule.setContent {
            ChunksTheme {
                Surface {
                    TransferSessionRemoveControl(remote, transfer,
                        snapshotStale = true, mainView = true,
                        onMutationStarted = { mutations++; false }, onSettled = {})
                }
            }
        }
        rule.onNodeWithText("Refresh this stale server snapshot before removing a transfer.").assertExists()
        rule.onNodeWithText("Remove transfer, keep data").assertDoesNotExist()
        assertEquals(0, mutations)
    }
}
