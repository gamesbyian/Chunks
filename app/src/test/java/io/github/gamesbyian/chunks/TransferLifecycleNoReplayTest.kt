package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Invariant controls `lifecycle-offers-action-while-pending` and
 * `lifecycle-replays-after-attempt-without-fresh-read`: a Start/Stop is offered again neither
 * while its command is in flight nor before a NEW confirmed server snapshot arrives. No server access.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferLifecycleNoReplayTest {
    @get:Rule val rule = createComposeRule()
    private val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:1/RPC2", "", ""))
    private val transfer = RemoteTransfer("1".repeat(40), "Example", 10, 0, false, "/d")

    private fun show(revision: () -> Long, perform: suspend (String, Boolean) -> LifecycleOutcome) {
        rule.setContent { ChunksTheme { Surface { Column {
            TransferLifecycleControl(remote, transfer, snapshotStale = false, onMutationStarted = { true },
                onSettled = {}, snapshotRevision = revision(), performLifecycle = perform,
                observeActivity = { error("not read in this test") })
        } } } }
    }

    @Test fun noSecondCommandIsOfferedWhileTheFirstIsInFlightOrBeforeAFreshSnapshot() {
        var revision by mutableLongStateOf(1L)
        val release = CompletableDeferred<Unit>()
        var commands = 0
        show({ revision }) { _, _ -> commands++; release.await(); LifecycleOutcome.Verified(true) }
        rule.onNodeWithText("Start transfer…").performClick()
        rule.onNodeWithText("Confirm start").performClick()
        rule.waitUntil(5_000) { commands == 1 }
        rule.waitForIdle()
        rule.onNodeWithText("Start transfer…").assertDoesNotExist() // in flight
        rule.runOnIdle { release.complete(Unit) }
        rule.waitForIdle()
        rule.onNodeWithText("Start transfer…").assertDoesNotExist() // settled, but no new snapshot yet
        rule.onNodeWithText("This operation needs a new confirmed server snapshot", substring = true).assertExists()
        assertEquals("exactly one command", 1, commands)
        rule.runOnIdle { revision = 2L } // a NEW authoritative read
        rule.waitForIdle()
        rule.onNodeWithText("Start transfer…").assertExists()
        assertEquals(1, commands)
    }
}
