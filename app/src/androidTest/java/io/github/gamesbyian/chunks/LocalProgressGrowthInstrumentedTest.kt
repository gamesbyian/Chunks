package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.LocalProgressHistory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalProgressGrowthInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private fun transfer() = LocalTransferStatus(
        infoHash = "e".repeat(40), name = "Public growth fixture",
        state = LocalTransferState.DOWNLOADING, stopped = false,
        progressPpm = 20_000, wantedBytes = 2000,
        doneBytes = 20, payloadDownloadedBytes = 20,
        payloadUploadedBytes = 0, peers = 0,
        savePath = "/local", error = null
    )

    @Test fun measuredGrowthAndPeerZeroAppearWithoutClaimingPermanentFailure() {
        compose.setContent {
            MaterialTheme {
                LocalTransferCard(
                    transfer = transfer(), busy = false, onToggle = {}, onRemove = {},
                    observedGrowth = LocalProgressHistory.ObservedDelta(
                        seconds = 12, verifiedBytes = 0,
                        peerPayloadBytes = 0, uploadedBytes = 0,
                        peersNow = 0, metadataKnown = true)
                )
            }
        }
        compose.onNodeWithText("Observed over 12s:", substring = true).assertExists()
        compose.onNodeWithText("No verified or peer-network byte growth during this sample.",
            substring = true).assertExists()
        compose.onNodeWithText("That is not proof of a permanent stall;",
            substring = true).assertExists()
    }

    @Test fun noMeasuredSampleNeverInventsProgressData() {
        compose.setContent {
            MaterialTheme {
                LocalTransferCard(transfer(), busy = false, onToggle = {}, onRemove = {})
            }
        }
        compose.onNodeWithText("Observed over", substring = true).assertDoesNotExist()
    }
}
