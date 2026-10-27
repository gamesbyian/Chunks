package io.github.gamesbyian.chunks

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.snapshots.SnapshotInventoryExportScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnapshotInventoryExportScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val inventory = listOf(
        RemoteTransfer(
            hash = "a".repeat(40), name = "Public fixture",
            sizeBytes = 100L, completedBytes = 0L,
            started = false, directory = "/private/seedbox"
        )
    )

    @Test fun freshQueueCanOfferExplicitlyInventoryOnlyBackup() {
        compose.setContent {
            MaterialTheme {
                SnapshotInventoryExportScreen(
                    transfers = inventory, view = "main",
                    snapshotRevision = 3, stale = false, loading = false,
                    onBack = {}
                )
            }
        }
        compose.onNodeWithText("Save transfer inventory").assertIsDisplayed()
        compose.onNodeWithText(
            "Choose where to save inventory snapshot"
        ).assertIsEnabled()
        compose.onNodeWithText(
            "Include old server directories (may reveal private paths)"
        ).assertExists()
        compose.onNodeWithText(
            "Observed: 1 transfers in 'main'. Recorded: 1. Omitted: 0. Verified restorable: 0."
        ).assertExists()
    }

    @Test fun staleQueueCannotBeSavedAsFreshInventory() {
        compose.setContent {
            MaterialTheme {
                SnapshotInventoryExportScreen(
                    transfers = inventory, view = "incomplete",
                    snapshotRevision = 4, stale = true, loading = false,
                    onBack = {}
                )
            }
        }
        compose.onNodeWithText(
            "Choose where to save inventory snapshot"
        ).assertIsNotEnabled()
    }
}
