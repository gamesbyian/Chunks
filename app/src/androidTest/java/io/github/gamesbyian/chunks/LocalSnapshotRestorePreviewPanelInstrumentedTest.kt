package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.snapshots.LocalSnapshotRestorePreviewPanel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSnapshotRestorePreviewPanelInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val transfer = LocalTransferStatus(
        infoHash = "a".repeat(40), name = "Public example",
        state = LocalTransferState.DOWNLOADING, stopped = false,
        progressPpm = 0, wantedBytes = 1000, doneBytes = 0,
        payloadDownloadedBytes = 0, payloadUploadedBytes = 0,
        peers = 0, savePath = "/app-private", error = null)

    @Test fun startupEmptyComposeListCannotBeTreatedAsCompleteEngineInventory() {
        compose.setContent {
            MaterialTheme {
                LocalSnapshotRestorePreviewPanel(
                    emptyList(), inventoryConfirmed = false, inventoryStale = false,
                    busyEngine = false, mutationGeneration = 0)
            }
        }
        compose.onNodeWithText("Compare saved snapshot with phone").performClick()
        compose.onNodeWithText("Snapshot restore comparison (read-only)").assertExists()
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsNotEnabled()
        compose.onNodeWithText("Phone inventory is not yet confirmed, is stale, or a native " +
            "operation is in progress. Wait for a fresh full read.").assertExists()
    }

    @Test fun trulyConfirmedEmptyEngineCanReviewWithoutClaimingAnyTorrentMutation() {
        compose.setContent {
            MaterialTheme {
                LocalSnapshotRestorePreviewPanel(
                    emptyList(), inventoryConfirmed = true, inventoryStale = false,
                    busyEngine = false, mutationGeneration = 1)
            }
        }
        compose.onNodeWithText("Compare saved snapshot with phone").performClick()
        compose.onNodeWithText("Current verified phone queue: 0 transfer identities. " +
            "Comparisons are discarded if this list changes.").assertExists()
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsEnabled()
        compose.onNodeWithText("Restore selected transfers").assertDoesNotExist()
    }

    @Test fun staleFrameAndNativeCommandBlockPickerAndRevokePriorEligibility() {
        var isStale by mutableStateOf(false)
        var busy by mutableStateOf(false)
        var generation by mutableStateOf(1L)
        compose.setContent {
            MaterialTheme {
                LocalSnapshotRestorePreviewPanel(
                    listOf(transfer), inventoryConfirmed = true,
                    inventoryStale = isStale, busyEngine = busy,
                    mutationGeneration = generation)
            }
        }
        compose.onNodeWithText("Compare saved snapshot with phone").performClick()
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsEnabled()
        compose.runOnIdle { isStale = true; generation++ }
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsNotEnabled()
        compose.runOnIdle { isStale = false; busy = true; generation++ }
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsNotEnabled()
        compose.runOnIdle { busy = false; generation++ }
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsEnabled()
    }
}
