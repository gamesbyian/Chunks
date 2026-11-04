package io.github.gamesbyian.chunks

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A stale phone inventory never presents old rates as live or estimates time left. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-mdpi")
class LocalCardStaleRateTest {
    @get:Rule val rule = createComposeRule()

    private val downloading = LocalTransferStatus("d".repeat(40), "Example", LocalTransferState.DOWNLOADING,
        stopped = false, progressPpm = 500_000, wantedBytes = 600L * 1024 * 1024,
        doneBytes = 300L * 1024 * 1024, payloadDownloadedBytes = 0, payloadUploadedBytes = 0, peers = 3,
        savePath = "/storage/emulated/0/Download/Chunks", error = null,
        downloadPayloadRateBytesPerSecond = 1024 * 1024)

    @Test fun freshCardShowsLiveRateAndEstimate() {
        rule.setContent { ChunksTheme { LocalTransferCard(downloading, busy = false, onToggle = {}, onRemove = {}) } }
        rule.onNodeWithText("Live payload", substring = true).assertExists()
        rule.onNodeWithText("about 5 min left", substring = true).assertExists()
    }

    @Test fun staleCardSaysLastReadAndOffersNoEstimate() {
        rule.setContent { ChunksTheme {
            LocalTransferCard(downloading, busy = true, onToggle = {}, onRemove = {}, stale = true)
        } }
        rule.onNodeWithText("Last read payload", substring = true).assertExists()
        rule.onAllNodesWithText("left", substring = true).assertCountEquals(0)
    }
}
