package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferHealthExportPanelInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val remote = listOf(RemoteTransfer(
        hash = "a".repeat(40), name = "Secret torrent",
        sizeBytes = 1_024, completedBytes = 0, started = true,
        directory = "/secret/device", downBytesPerSecond = 0))
    private val local = listOf(LocalTransferStatus(
        infoHash = "a".repeat(40), name = "Secret torrent",
        state = LocalTransferState.DOWNLOADING, stopped = false,
        progressPpm = 0, wantedBytes = 1_024, doneBytes = 0,
        payloadDownloadedBytes = 0, payloadUploadedBytes = 0,
        peers = 0, savePath = "/secret/device", error = null))

    @Test fun remoteReportExplainsSingleObservationAndRedaction() {
        compose.setContent {
            MaterialTheme { RemoteTransferHealthExportPanel(remote, "main", stale = true) }
        }
        compose.onNodeWithText("Save download-health report").performClick()
        compose.onNodeWithText("Transfer health, one observation").assertExists()
        compose.onNodeWithText("The queue is stale; the report will mark its evidence as stale.")
            .assertExists()
        compose.onNodeWithText("Include original torrent names (may be sensitive)").assertExists()
        compose.onNodeWithText("Share download-health report").assertExists()
    }

    @Test fun phoneReportIsAvailableWithoutAnyNetworkOrLibtorrentAction() {
        compose.setContent {
            MaterialTheme { LocalTransferHealthExportPanel(local, stale = false) }
        }
        compose.onNodeWithText("Save download-health report").performClick()
        compose.onNodeWithText("Transfer health, one observation").assertExists()
        compose.onNodeWithText("Share download-health report").assertExists()
    }
}
