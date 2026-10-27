package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies a native, user-cancellable diagnostic is discoverable without RPC mutation. */
@RunWith(AndroidJUnit4::class)
class RemoteTrafficControlsInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:1/RPC2", "", ""))
    private val fixture = RemoteTransfer(
        hash = "ab".repeat(20), name = "Licensed public test", sizeBytes = 100_000,
        completedBytes = 10_000, started = true, directory = "/srv/test", downBytesPerSecond = 0
    )

    @Test fun runningTorrentOffersUserInitiatedReadOnlyProgressSamplesEvenWhenListIsStale() {
        rule.setContent {
            MaterialTheme {
                TransferLifecycleControl(
                    remote = remote, transfer = fixture, snapshotStale = true,
                    onMutationStarted = { throw AssertionError("Lifecycle writes must remain disabled") },
                    onSettled = { throw AssertionError("No lifecycle write should settle") }
                )
            }
        }
        rule.onNodeWithText("Measure download progress (3 samples)").assertExists()
        rule.onNodeWithText("Diagnose download activity").assertExists()
        rule.onNodeWithText("Stop transfer…").assertIsNotEnabled()
    }

    @Test fun stoppedTorrentDoesNotImplyItsBytesAreDownloading() {
        rule.setContent {
            MaterialTheme {
                TransferLifecycleControl(
                    remote = remote, transfer = fixture.copy(started = false),
                    snapshotStale = false,
                    onMutationStarted = { throw AssertionError("No lifecycle writes expected") },
                    onSettled = { throw AssertionError("No lifecycle write should settle") }
                )
            }
        }
        rule.onNodeWithText("Start transfer…").assertExists()
        rule.onNodeWithText("Measure download progress (3 samples)").assertDoesNotExist()
    }
}
