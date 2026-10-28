package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.snapshots.SnapshotRestorePreviewPanel
import io.github.gamesbyian.chunks.snapshots.SnapshotRestoreTarget
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The same read-only comparison, against one complete rTorrent inventory. */
@RunWith(AndroidJUnit4::class)
class ServerSnapshotRestorePreviewPanelInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun serverComparisonNeedsAFreshCompleteViewAndNamesTheServer() {
        var complete by mutableStateOf(false)
        var loading by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                SnapshotRestorePreviewPanel(
                    targetHashes = listOf("AB".repeat(20)),
                    target = SnapshotRestoreTarget.SERVER,
                    inventoryConfirmed = complete, inventoryStale = false,
                    busyEngine = loading, mutationGeneration = 1,
                    onPrepareAdd = {}, onPrepareMagnet = {})
            }
        }
        compose.onNodeWithText("Compare saved snapshot with rTorrent server").performClick()
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsNotEnabled()
        compose.onNodeWithText("rTorrent server inventory is not yet confirmed",
            substring = true).assertExists()
        compose.runOnIdle { complete = true }
        compose.onNodeWithText("Current verified rTorrent server queue: 1 transfer identities. " +
            "Comparisons are discarded if this list changes.").assertExists()
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsEnabled()
        compose.runOnIdle { loading = true }
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsNotEnabled()
    }
}
