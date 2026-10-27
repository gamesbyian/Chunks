package io.github.gamesbyian.chunks

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteFile
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deliberately dispose an expanded mutation control without ever sending an RPC.
 * Reconstructed cards must preserve confirmation state and must not silently
 * reset to an apparently fresh operation.
 */
@RunWith(AndroidJUnit4::class)
class TransferMutationRecyclingInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:1/RPC2", "", ""))
    private val transfer = RemoteTransfer("A".repeat(40),
        "Public.Example.S01E01.mkv", 1000, 500, false, "/seed")

    private fun awayAndBack() {
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(25)
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
    }

    @Test fun startStopConfirmationCannotDisappearOnQueueRecycling() {
        rule.setContent {
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "operation") {
                        TransferLifecycleControl(
                            remote, transfer, snapshotStale = false,
                            onMutationStarted = { true }, onSettled = {}
                        )
                    }
                    items(40) { i -> Text("Row $i", Modifier.height(120.dp)) }
                }
            }
        }
        rule.onNodeWithText("Start transfer…").performClick()
        rule.onNodeWithText("Confirm start for this torrent").assertExists()
        awayAndBack()
        rule.onNodeWithText("Confirm start for this torrent").assertExists()
    }

    @Test fun sessionRemovalConfirmationCannotDisappearOnQueueRecycling() {
        rule.setContent {
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "operation") {
                        TransferSessionRemoveControl(
                            remote, transfer, snapshotStale = false,
                            mainView = true, onMutationStarted = { true }, onSettled = {}
                        )
                    }
                    items(40) { i -> Text("Row $i", Modifier.height(120.dp)) }
                }
            }
        }
        rule.onNodeWithText("Remove from rTorrent…").performClick()
        rule.onNodeWithText("Confirm removal from rTorrent").assertExists()
        awayAndBack()
        rule.onNodeWithText("Confirm removal from rTorrent").assertExists()
    }

    @Test fun filePrioritySelectionRetainsExactRequestedValueAfterRecycle() {
        rule.setContent {
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "file") {
                        TransferFilePriorityControl(
                            remote = remote, hash = transfer.hash,
                            index = 0, file = RemoteFile("episode.mkv", 1000, 1),
                            snapshotStale = false, mainView = true, onMutationStarted = { true }, onSettled = {}
                        )
                    }
                    items(40) { i -> Text("Row $i", Modifier.height(120.dp)) }
                }
            }
        }
        rule.onNodeWithText("High").performClick()
        rule.onNodeWithText("Review priority change").performClick()
        rule.onNodeWithText("Confirm file priority change").assertExists()
        awayAndBack()
        rule.onNodeWithText("Confirm file priority change").assertExists()
        rule.onNodeWithText("From Normal to High.", substring = true).assertExists()
    }
}
