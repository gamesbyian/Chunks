package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.rpc.SessionEraseOutcome
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Invariant control `remove-review-survives-stale-snapshot`: an open Remove confirmation is
 * revoked when the list it was reviewed against goes stale or the view is no longer the full
 * main view. No server access.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TransferSessionRemoveRevocationTest {
    @get:Rule val rule = createComposeRule()
    private val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:1/RPC2", "", ""))
    private val transfer = RemoteTransfer(hash = "d".repeat(40), name = "Example", sizeBytes = 1,
        completedBytes = 1, started = false, directory = "/downloads/x")

    /**
     * rememberSaveable restores `confirming` without comparing its keys, so the explicit
     * `!snapshotStale && mainView` clause is the only fence for a restored review.
     */
    private fun check(revoke: (set: (stale: Boolean, main: Boolean) -> Unit) -> Unit) {
        val restoration = StateRestorationTester(rule)
        var stale by mutableStateOf(false)
        var main by mutableStateOf(true)
        var erases = 0
        restoration.setContent { ChunksTheme { Surface { Column {
            TransferSessionRemoveControl(remote, transfer, stale, main, { true }, {},
                performErase = { _, _, _ -> erases++; SessionEraseOutcome.VerifiedRemoved(transfer.hash) })
        } } } }
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.onNodeWithText("Remove transfer, keep data").assertExists()
        rule.runOnIdle { revoke { s, m -> stale = s; main = m } }
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithText("Remove transfer, keep data").assertDoesNotExist()
        assertEquals("a revoked review sends nothing", 0, erases)
    }

    @Test fun aStaleSnapshotRevokesAnOpenRemoveConfirmation() = check { set -> set(true, true) }

    @Test fun leavingTheFullMainViewRevokesAnOpenRemoveConfirmation() = check { set -> set(false, false) }
}
