package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.snapshots.SnapshotFilePriorities
import io.github.gamesbyian.chunks.snapshots.SnapshotRestorePreviewV1
import io.github.gamesbyian.chunks.snapshots.SnapshotSettingsPreview
import io.github.gamesbyian.chunks.snapshots.SnapshotRestorePreviewPanel
import io.github.gamesbyian.chunks.snapshots.SnapshotRestoreTarget
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The same read-only comparison, against one complete rTorrent inventory. */
@RunWith(AndroidJUnit4::class)
class ServerSnapshotRestorePreviewPanelInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readOnlyPreviewExposesExactMappingAndUnsupportedLowPriority() {
        val entry = SnapshotRestorePreviewV1.Entry("a".repeat(40), "Example",
            SnapshotRestorePreviewV1.Decision.METADATA_NEEDS_REVIEW, true, null,
            "Example label", listOf(
                SnapshotFilePriorities.Mapping(
                    SnapshotFilePriorities.File(2, "Example/document.txt", 42),
                    SnapshotFilePriorities.Level.LOW)
            ))
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    SnapshotSettingsPreview(entry, SnapshotRestoreTarget.SERVER)
                }
            }
        }
        compose.onNodeWithText("Saved Chunks display alias: Example label").assertExists()
        compose.onNodeWithText("1 Low priorities are unsupported", substring = true).assertExists()
        compose.onNodeWithText("Review 1 file priorities").performClick()
        compose.onNodeWithText("#2 Example/document.txt (42 bytes) · Low (unsupported)").assertExists()
        compose.onNodeWithText("Original file layout. Target file paths have not been checked.").assertExists()
    }

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
        compose.onNodeWithText("Compare saved snapshot with rTorrent client").performClick()
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsNotEnabled()
        compose.onNodeWithText("rTorrent client inventory is not yet confirmed",
            substring = true).assertExists()
        compose.runOnIdle { complete = true }
        compose.onNodeWithText("Current verified rTorrent client queue: 1 transfer identities. " +
            "Comparisons are discarded if this list changes.").assertExists()
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsEnabled()
        compose.runOnIdle { loading = true }
        compose.onNodeWithText("Choose .chunksnap to compare").assertIsNotEnabled()
    }
}
