package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.rpc.RemoteTransferActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A confirmed fresh RPC inventory, not a UI recompose, must revive the
 * opposite Start/Stop action. All writes are injected, no host/network.
 */
@RunWith(AndroidJUnit4::class)
class RemoteLifecycleRefreshInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:1/RPC2", "", ""))
    private val initial = RemoteTransfer("a".repeat(40), "Test legal fixture", 1024, 0,
        started = false, directory = "/fixture")

    @Test fun startThenStopThenStartAreReachableAfterDistinctFreshSnapshots() {
        val calls = mutableListOf<Boolean>()
        var latest = initial
        var revision = 3L
        var stale = false
        var toApply: Boolean? = null
        rule.setContent {
            var transfer by remember { mutableStateOf(initial) }
            var snapshotRevision by remember { mutableStateOf(3L) }
            var snapshotStale by remember { mutableStateOf(false) }
            latest = transfer
            revision = snapshotRevision
            stale = snapshotStale
            MaterialTheme {
                TransferLifecycleControl(
                    remote = remote,
                    transfer = transfer,
                    snapshotStale = snapshotStale,
                    snapshotRevision = snapshotRevision,
                    onMutationStarted = {
                        if (snapshotStale) false else {
                            snapshotStale = true
                            true
                        }
                    },
                    onSettled = {
                        transfer = transfer.copy(started = toApply ?: transfer.started)
                        snapshotRevision++
                        snapshotStale = false
                    },
                    performLifecycle = { _, desired ->
                        calls.add(desired)
                        toApply = desired
                        LifecycleOutcome.Verified(desired)
                    }
                )
            }
        }
        rule.onNodeWithText("Start transfer…").performClick()
        rule.onNodeWithText("Confirm start").performClick()
        rule.waitUntil(5000) { revision == 4L && !stale }
        rule.onNodeWithText("Stop transfer…").assertExists().performClick()
        rule.onNodeWithText("Confirm stop").performClick()
        rule.waitUntil(5000) { revision == 5L && !stale }
        rule.onNodeWithText("Start transfer…").assertExists()
        assertEquals(listOf(true, false), calls)
        assertEquals(false, latest.started)
    }

    @Test fun stateSetButInactiveCanBeExplicitlyResumedAfterFreshActivityRead() {
        val calls = mutableListOf<Boolean>()
        var revision = 1L
        var stale = false
        rule.setContent {
            var snapshotRevision by remember { mutableStateOf(1L) }
            var snapshotStale by remember { mutableStateOf(false) }
            revision = snapshotRevision
            stale = snapshotStale
            MaterialTheme {
                TransferLifecycleControl(
                    remote = remote,
                    transfer = initial.copy(started = true),
                    snapshotStale = snapshotStale,
                    snapshotRevision = snapshotRevision,
                    onMutationStarted = {
                        if (snapshotStale) false else {
                            snapshotStale = true
                            true
                        }
                    },
                    onSettled = {
                        snapshotRevision++
                        snapshotStale = false
                    },
                    performLifecycle = { _, desired ->
                        calls.add(desired)
                        LifecycleOutcome.Verified(desired)
                    },
                    observeActivity = {
                        RemoteTransferActivity(active = false, open = true,
                            connectedPeers = 0, downloadBytesPerSecond = 0)
                    }
                )
            }
        }
        rule.onNodeWithText("Resume inactive transfer…").assertDoesNotExist()
        rule.onNodeWithText("Diagnose download activity").performClick()
        rule.onNodeWithText("Resume inactive transfer…").assertExists().performClick()
        rule.onNodeWithText("Confirm resume").assertExists().performClick()
        rule.waitUntil(5000) { revision == 2L && !stale }
        assertEquals(listOf(true), calls)
        // The earlier activity reading is stale after a newly accepted queue
        // observation; no second Resume without a new explicit diagnosis.
        rule.onNodeWithText("Resume inactive transfer…").assertDoesNotExist()
        rule.onNodeWithText("Stop transfer…").assertExists()
    }

    @Test fun staleOrSameRevisionNeverRearmsASecondWrite() {
        val calls = mutableListOf<Boolean>()
        var refresh: (() -> Unit)? = null
        var stale = false
        rule.setContent {
            var revision by remember { mutableStateOf(9L) }
            var snapshotStale by remember { mutableStateOf(false) }
            stale = snapshotStale
            refresh = { snapshotStale = false; revision++ }
            MaterialTheme {
                TransferLifecycleControl(
                    remote = remote,
                    transfer = initial,
                    snapshotStale = snapshotStale,
                    snapshotRevision = revision,
                    onMutationStarted = {
                        if (snapshotStale) false else {
                            snapshotStale = true
                            true
                        }
                    },
                    onSettled = {
                        // The server read failed. A successful RPC response
                        // alone must not unlock another write.
                        snapshotStale = true
                    },
                    performLifecycle = { _, desired ->
                        calls.add(desired)
                        LifecycleOutcome.Uncertain(desired)
                    }
                )
            }
        }
        rule.onNodeWithText("Start transfer…").performClick()
        rule.onNodeWithText("Confirm start").performClick()
        rule.waitUntil(5000) { stale && calls.size == 1 }
        rule.onNodeWithText("Start transfer…").assertDoesNotExist()
        rule.runOnUiThread { refresh?.invoke() }
        rule.onNodeWithText("Start transfer…").assertExists()
        assertEquals(listOf(true), calls)
    }
}
