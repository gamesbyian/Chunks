package io.github.gamesbyian.chunks.snapshots

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSnapshotStoppedRestoreReviewInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val torrent = ("d4:infod6:lengthi5e4:name8:test.txt12:piece lengthi16384e" +
        "6:pieces20:01234567890123456789ee").toByteArray()
    private fun draft(): LocalSnapshotStoppedRestoreDraft {
        val preview = TorrentMetainfoReader.preview(torrent)
        return LocalSnapshotStoppedRestoreDraft(
            SnapshotVerifiedTorrentDocumentV1.Prepared(preview.infoHashV1,
                preview.name, "safe.torrent", torrent.copyOf()),
            "a".repeat(64), emptyList(), 17L)
    }

    @Test fun explicitStoppedRestoreReviewNeverSubmitsUntilTap() {
        var calls = 0
        compose.setContent {
            MaterialTheme {
                LocalSnapshotStoppedRestoreReview(draft(),
                    "Downloads/Chunks Downloads", eligible = true,
                    onConfirm = { calls++ }, onCancel = { })
            }
        }
        compose.onNodeWithText("Confirm one stopped snapshot restore").assertExists()
        compose.onNodeWithText("New phone destination: Downloads/Chunks Downloads").assertExists()
        compose.onNodeWithText("No historical paths are reused", substring = true).assertExists()
        compose.onNodeWithText("Confirm restore stopped").assertExists()
        compose.runOnIdle { assertEquals(0, calls) }
        compose.onNodeWithText("Confirm restore stopped").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun staleComparisonCannotAuthorizeAddButCancelAlwaysWorks() {
        var submitted = false
        var cancelled = false
        compose.setContent {
            MaterialTheme {
                LocalSnapshotStoppedRestoreReview(draft(),
                    "Unknown phone destination", eligible = false,
                    onConfirm = { submitted = true },
                    onCancel = { cancelled = true })
            }
        }
        compose.onNodeWithText("Confirm restore stopped").assertIsNotEnabled()
        compose.onNodeWithText("Cancel restore").performClick()
        compose.runOnIdle {
            assertFalse(submitted)
            assertEquals(true, cancelled)
        }
    }
}
