package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteHashRecheckObservation
import io.github.gamesbyian.chunks.rpc.RemoteHashRecheckOutcome
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real render/interactions; callbacks isolate UI authorization, never substitute for engine proof. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class RemoteHashRecheckControlTest {
    @get:Rule val rule = createComposeRule()
    private fun account(user: String = "a") = RTorrentRemote(RemoteCredentials(
        "https://fixture.invalid/rutorrent1/RPC2", user, "fixture"))
    private val row = RemoteTransfer("c".repeat(40), "Synthetic public transfer", 1000, 100, false, "/data")

    @Test fun oneExplicitRequestThenReadOnlyIdleStatusNeverClaimsCompletedFilesOrRepeatsScheduling() {
        val remote = account()
        var requests = 0
        var reads = 0
        var settled = 0
        rule.setContent { ChunksTheme { Surface { Column {
            RemoteHashRecheckControl(remote, row, remote.addAccountScope(), false, true,
                { true }, { settled++ }, request = { h, n, dir ->
                    assertEquals(row.hash, h); assertEquals(row.name, n); assertEquals(row.directory, dir)
                    requests++; RemoteHashRecheckOutcome.SubmittedUnobserved(h)
                }, observe = { _, _, _ -> reads++; RemoteHashRecheckObservation.NotChecking })
        } } } }
        rule.onRoot().captureRoboImage("build/chunks-visual/remote-recheck-ready-412.png")
        val submit = requireNotNull(rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK)
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        rule.runOnIdle { submit(); submit() } // duplicate input before a new frame still admits only once
        rule.waitUntil { settled == 1 }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).assertDoesNotExist()
        rule.onNodeWithText("Recheck acknowledged; checking was not observed.", substring = true).assertExists()
        rule.onNodeWithText("Check Recheck status (read-only)").performClick()
        rule.waitUntil { reads == 1 }
        rule.onNodeWithText("This does not prove the request completed or that files are intact.", substring = true).assertExists()
        rule.onRoot().captureRoboImage("build/chunks-visual/remote-recheck-idle-412.png")
        assertEquals(1, requests)
        assertEquals(1, settled)
        rule.onNodeWithText("Review a new Recheck").performClick()
        assertEquals("Review cannot itself schedule anything", 1, requests)
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).performClick()
        rule.waitUntil { requests == 2 }
    }

    @Test fun staleWrongPrincipalAndUnresolvedPlaceNeverAcquireAMutationLease() {
        val remote = account()
        val stale = mutableStateOf(true)
        val alias = mutableStateOf(remote.addAccountScope())
        val place = mutableStateOf(row.directory)
        var leases = 0
        rule.setContent { ChunksTheme { Surface {
            RemoteHashRecheckControl(remote, row.copy(directory = place.value), alias.value,
                stale.value, true, { leases++; false }, {})
        } } }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).assertIsNotEnabled()
        rule.runOnIdle { stale.value = false; alias.value = account("another").addAccountScope() }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).assertIsNotEnabled()
        rule.runOnIdle { alias.value = remote.addAccountScope(); place.value = "." }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).assertIsNotEnabled()
        assertEquals(0, leases)
    }

    @Test fun aRefusedParentLeaseCannotScheduleOrCreateAnUncertainAttempt() {
        val remote = account()
        var leases = 0
        rule.setContent { ChunksTheme { Surface {
            RemoteHashRecheckControl(remote, row, remote.addAccountScope(), false, true,
                { leases++; false }, { error("No admitted mutation") },
                request = { _, _, _ -> error("No scheduling without the exact parent lease") })
        } } }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).performClick()
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).assertExists()
        rule.onNodeWithTag(ChunksSemantics.REMOTE_RECHECK_STATUS).assertDoesNotExist()
        assertEquals(1, leases)
        rule.onNodeWithText("Recheck was not submitted.", substring = true).assertExists()
    }

    @Test fun theActualRpcPreflightRefusesARenamedJobWithoutACommandOrPrivateErrorText() {
        FakeRTorrent().use { gateway ->
            gateway.jobs[row.hash] = FakeRTorrent.Job(row.hash, "Private changed title", row.directory, false)
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
            rule.setContent { ChunksTheme { Surface {
                RemoteHashRecheckControl(remote, row, remote.addAccountScope(), false, true, { true }, {})
            } } }
            rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).performClick()
            rule.waitUntil { rule.onAllNodesWithText("No Recheck command was sent.", substring = true)
                .fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Private changed title", substring = true).assertDoesNotExist()
            assertTrue(gateway.methods.none { it == "d.check_hash" })
        }
    }

    @Test fun savedUncertainAttemptSurvivesSameAccountRestoreButCannotCrossAnIdenticalJobOnAnotherAccount() {
        val restore = StateRestorationTester(rule)
        var remote = account()
        var requests = 0
        restore.setContent { ChunksTheme { Surface {
            RemoteHashRecheckControl(remote, row, remote.addAccountScope(), false, true,
                { true }, {}, request = { h, _, _ -> requests++; RemoteHashRecheckOutcome.Uncertain(h) })
        } } }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).performClick()
        rule.waitUntil { requests == 1 }
        restore.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).assertDoesNotExist()
        rule.onNodeWithText("Recheck outcome uncertain.", substring = true).assertExists()
        remote = account("b")
        restore.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).assertExists()
        rule.onNodeWithTag(ChunksSemantics.REMOTE_RECHECK_STATUS).assertDoesNotExist()
        assertEquals(1, requests)
    }

    @Test fun queuedAndUnauthenticatedReadsCannotRearmTheSchedulingAction() {
        val remote = account()
        val next = mutableStateOf<RemoteHashRecheckObservation>(RemoteHashRecheckObservation.Queued)
        rule.setContent { ChunksTheme { Surface {
            RemoteHashRecheckControl(remote, row, remote.addAccountScope(), false, true,
                { true }, {}, request = { h, _, _ -> RemoteHashRecheckOutcome.AlreadyQueued(h) },
                observe = { _, _, _ -> next.value })
        } } }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Check Recheck status (read-only)").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Review a new Recheck").assertDoesNotExist()
        rule.runOnIdle { next.value = RemoteHashRecheckObservation.AuthenticationRequired }
        rule.onNodeWithText("Check Recheck status (read-only)").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("This exact account requires authentication.", substring = true).assertExists()
        rule.onNodeWithText("Review a new Recheck").assertDoesNotExist()
    }

    @Test fun restoringAnInFlightAttemptRequiresANewReadInsteadOfClaimingItIsStillRunning() {
        val restore = StateRestorationTester(rule)
        val remote = account()
        val finish = CompletableDeferred<RemoteHashRecheckOutcome>()
        var requests = 0
        restore.setContent { ChunksTheme { Surface {
            RemoteHashRecheckControl(remote, row, remote.addAccountScope(), false, true,
                { true }, {}, request = { _, _, _ -> requests++; finish.await() })
        } } }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).performClick()
        rule.waitUntil { requests == 1 }
        restore.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).assertDoesNotExist()
        rule.onNodeWithText("Recheck request in progress.").assertDoesNotExist()
        rule.onNodeWithText("Check Recheck status (read-only)").assertExists()
        assertEquals(1, requests)
    }

    @Test fun anOldAccountReadCannotOverwriteTheOwningAttemptAfterSwitchingBackAndForth() {
        val a = account("a")
        val b = account("b")
        val remote = mutableStateOf(a)
        val finishRead = CompletableDeferred<RemoteHashRecheckObservation>()
        rule.setContent { ChunksTheme { Surface {
            RemoteHashRecheckControl(remote.value, row, remote.value.addAccountScope(), false, true,
                { true }, {}, request = { h, _, _ ->
                    if (remote.value === a) RemoteHashRecheckOutcome.Uncertain(h)
                    else RemoteHashRecheckOutcome.AlreadyQueued(h)
                }, observe = { _, _, _ -> finishRead.await() })
        } } }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Check Recheck status (read-only)").performClick()
        rule.runOnIdle { remote.value = b }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).performClick()
        rule.waitForIdle()
        rule.runOnIdle { remote.value = a }
        rule.runOnIdle { finishRead.complete(RemoteHashRecheckObservation.NotChecking) }
        rule.waitForIdle()
        rule.runOnIdle { remote.value = b }
        rule.onNodeWithText("The server reported a pending hash check for this exact transfer.", substring = true).assertExists()
        rule.onNodeWithText("Last read: this exact transfer was neither checking nor queued.", substring = true).assertDoesNotExist()
    }

    @Test fun aReadTimeoutReplacesTheEarlierStatusAndCannotRearmOrRepeatScheduling() {
        val remote = account()
        var requests = 0
        rule.setContent { ChunksTheme { Surface {
            RemoteHashRecheckControl(remote, row, remote.addAccountScope(), false, true,
                { true }, {}, request = { h, _, _ -> requests++; RemoteHashRecheckOutcome.Uncertain(h) },
                observe = { _, _, _ -> withTimeout(1L) { CompletableDeferred<RemoteHashRecheckObservation>().await() } })
        } } }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Check Recheck status (read-only)").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("The status read timed out.", substring = true).assertExists()
        rule.onNodeWithText("Review a new Recheck").assertDoesNotExist()
        assertEquals(1, requests)
    }

    @Test fun anOlderRequestForTheSameAccountCannotClearANewerRequestsPendingState() {
        val a = account("a")
        val b = account("b")
        val remote = mutableStateOf(a)
        val firstA = CompletableDeferred<RemoteHashRecheckOutcome>()
        val secondA = CompletableDeferred<RemoteHashRecheckOutcome>()
        var aRequests = 0
        rule.setContent { ChunksTheme { Surface {
            RemoteHashRecheckControl(remote.value, row, remote.value.addAccountScope(), false, true,
                { true }, {}, request = { h, _, _ ->
                    if (remote.value === b) RemoteHashRecheckOutcome.Uncertain(h)
                    else if (++aRequests == 1) firstA.await() else secondA.await()
                })
        } } }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).performClick()
        rule.waitUntil { aRequests == 1 }
        rule.runOnIdle { remote.value = b }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).performClick()
        rule.waitForIdle()
        rule.runOnIdle { remote.value = a }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).performClick()
        rule.waitUntil { aRequests == 2 }
        rule.runOnIdle { firstA.complete(RemoteHashRecheckOutcome.HashingObserved(row.hash)) }
        rule.waitForIdle()
        rule.onNodeWithText("Recheck request in progress.").assertExists()
        rule.onNodeWithText("Check Recheck status (read-only)").assertIsNotEnabled()
        rule.onNodeWithText("Hash checking observed", substring = true).assertDoesNotExist()
        rule.runOnIdle { secondA.complete(RemoteHashRecheckOutcome.SubmittedUnobserved(row.hash)) }
        rule.waitUntil { rule.onAllNodesWithText("Recheck acknowledged;", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, aRequests)
    }

    @Test fun rapidStatusTapsCannotAdmitOverlappingReadsOrReuseAnOldIdleWitness() {
        val remote = account()
        val finish = CompletableDeferred<RemoteHashRecheckObservation>()
        var reads = 0
        rule.setContent { ChunksTheme { Surface {
            RemoteHashRecheckControl(remote, row, remote.addAccountScope(), false, true,
                { true }, {}, request = { h, _, _ -> RemoteHashRecheckOutcome.Uncertain(h) },
                observe = { _, _, _ -> reads++; finish.await() })
        } } }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).performClick()
        rule.waitForIdle()
        val read = requireNotNull(rule.onNodeWithText("Check Recheck status (read-only)")
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action)
        rule.runOnIdle { read(); read() }
        rule.waitUntil { reads == 1 }
        rule.onNodeWithText("Review a new Recheck").assertDoesNotExist()
        rule.runOnIdle { finish.complete(RemoteHashRecheckObservation.NotChecking) }
        rule.waitUntil { rule.onAllNodesWithText("Review a new Recheck").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, reads)
    }

    @Test @Config(sdk = [35], qualifiers = "w320dp-h700dp-mdpi")
    fun pendingRecheckAtLargeTextPreventsRepeatAndKeepsItsStatusReachable() {
        val remote = account()
        val finish = CompletableDeferred<RemoteHashRecheckOutcome>()
        var requests = 0
        rule.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
            ChunksTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Synthetic server • Transfer details")
                Text(row.name)
                RemoteHashRecheckControl(remote, row, remote.addAccountScope(), false, true,
                    { true }, {}, request = { _, _, _ -> requests++; finish.await() })
            } } }
        } }
        rule.onNodeWithTag(ChunksSemantics.DETAILS_REMOTE_RECHECK).performScrollTo().performClick()
        rule.waitUntil { requests == 1 }
        rule.onNodeWithText("Check Recheck status (read-only)").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(ChunksSemantics.REMOTE_RECHECK_STATUS).assertExists()
        rule.onRoot().captureRoboImage("build/chunks-visual/remote-recheck-pending-320-200pct.png")
        rule.runOnIdle { finish.complete(RemoteHashRecheckOutcome.HashingObserved(row.hash)) }
        rule.waitUntil { rule.onAllNodesWithText("Hash checking observed", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, requests)
        assertTrue(finish.isCompleted)
    }
}
