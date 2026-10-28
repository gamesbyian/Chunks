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
import io.github.gamesbyian.chunks.snapshots.LocalResumeMetainfoSourceV1
import io.github.gamesbyian.chunks.snapshots.LocalVerifiedSnapshotPanel
import io.github.gamesbyian.chunks.snapshots.SnapshotManifestV1
import java.security.MessageDigest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalVerifiedSnapshotPanelInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val info = ("d6:lengthi5e4:name8:test.txt12:piece lengthi16384e" +
        "6:pieces20:01234567890123456789e").toByteArray()
    private val torrent = ("d4:info" + String(info, Charsets.US_ASCII) + "e")
        .toByteArray(Charsets.US_ASCII)
    private val hash = MessageDigest.getInstance("SHA-1").digest(info)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val transfer = LocalTransferStatus(
        infoHash = hash, name = "test.txt", state = LocalTransferState.DOWNLOADING,
        stopped = true, progressPpm = 0, wantedBytes = 5, doneBytes = 0,
        payloadDownloadedBytes = 0, payloadUploadedBytes = 0,
        peers = 0, savePath = "/not-read", error = null
    )

    private val recovery = LocalResumeMetainfoSourceV1.Recovery(
        manifest = SnapshotManifestV1.Manifest(
            producer = "chunks-android",
            createdAtUtc = "2026-10-27T21:00:00Z",
            source = SnapshotManifestV1.Source.LOCAL,
            items = listOf(SnapshotManifestV1.Item(
                infoHashV1 = hash, originalName = "test.txt",
                recovery = SnapshotManifestV1.Recovery.METAINFO))
        ),
        sourceCount = 1,
        omittedCount = 0,
        metainfoByHash = mapOf(hash to torrent),
        unavailableByHash = emptyMap()
    )

    @Test fun realDefinitionNeedsExplicitSensitiveTrackerAcknowledgement() {
        compose.setContent {
            MaterialTheme {
                LocalVerifiedSnapshotPanel(listOf(transfer), stale = false,
                    busyEngine = false, recover = { _, _ -> recovery })
            }
        }
        compose.onNodeWithText("Recover real .torrent definitions for backup").performClick()
        compose.onNodeWithText("Check recoverable definitions").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(
                androidx.compose.ui.test.hasText("verified .torrent definitions: 1", substring = true)
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Choose private file for verified snapshot").assertIsNotEnabled()
        compose.onNodeWithText(
            "I understand the snapshot may contain private tracker credentials"
        ).performClick()
        compose.onNodeWithText("Choose private file for verified snapshot").assertIsEnabled()
    }

    @Test fun stalePhoneInventoryNeverOpensRecoveryAction() {
        compose.setContent {
            MaterialTheme {
                LocalVerifiedSnapshotPanel(listOf(transfer), stale = true,
                    busyEngine = false, recover = { _, _ -> recovery })
            }
        }
        compose.onNodeWithText("Recover real .torrent definitions for backup").performClick()
        compose.onNodeWithText("Check recoverable definitions").assertIsNotEnabled()
    }
}
