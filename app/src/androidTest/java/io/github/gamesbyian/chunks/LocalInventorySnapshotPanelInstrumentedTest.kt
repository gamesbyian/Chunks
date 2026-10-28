package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.snapshots.LocalInventorySnapshotPanel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalInventorySnapshotPanelInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val inventory = listOf(LocalTransferStatus(
        infoHash = "a".repeat(40), name = "Public sample",
        state = LocalTransferState.DOWNLOADING, stopped = true,
        progressPpm = 0, wantedBytes = 1024, doneBytes = 0,
        payloadDownloadedBytes = 0, payloadUploadedBytes = 0,
        peers = 0, savePath = "/app-owned/private", error = null))

    @Test fun currentLocalFrameMayExportInventoryWithoutClaimingRecovery() {
        compose.setContent {
            MaterialTheme {
                LocalInventorySnapshotPanel(inventory, stale = false, busyEngine = false)
            }
        }
        compose.onNodeWithText("Save phone transfer inventory").performClick()
        compose.onNodeWithText("Local transfer inventory only").assertExists()
        compose.onNodeWithText("Choose file for phone inventory").assertIsEnabled()
        compose.onNodeWithText(
            "Observed: 1; recorded: 1; omitted: 0; verified restorable: 0."
        ).assertExists()
    }

    @Test fun staleLocalFrameCannotExportAsFresh() {
        compose.setContent {
            MaterialTheme {
                LocalInventorySnapshotPanel(inventory, stale = true, busyEngine = false)
            }
        }
        compose.onNodeWithText("Save phone transfer inventory").performClick()
        compose.onNodeWithText("Choose file for phone inventory").assertIsNotEnabled()
    }

    @Test fun inFlightNativeMutationCannotExport() {
        compose.setContent {
            MaterialTheme {
                LocalInventorySnapshotPanel(inventory, stale = false, busyEngine = true)
            }
        }
        compose.onNodeWithText("Save phone transfer inventory").performClick()
        compose.onNodeWithText("Choose file for phone inventory").assertIsNotEnabled()
    }
}
