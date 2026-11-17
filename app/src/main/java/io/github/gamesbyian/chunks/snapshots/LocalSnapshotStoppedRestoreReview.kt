package io.github.gamesbyian.chunks.snapshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** UI-only pending review. Never persists a raw file, historical Place or passkey. */
internal class LocalSnapshotStoppedRestoreDraft(
    val original: SnapshotVerifiedTorrentDocumentV1.Prepared,
    val archiveFingerprint: String,
    val frozenHashes: List<String>,
    val frozenGeneration: Long,
    /** Existing target hash: check a prior journal, never submit another Add. */
    val recoveryOnly: Boolean = false
)

/** Hash only. No raw historical or new Place paths in journal persistence. */
internal object LocalSnapshotRestoreFingerprint {
    fun of(value: String): String = of(value.toByteArray(StandardCharsets.UTF_8))
    fun of(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value)
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }
}

@Composable
internal fun LocalSnapshotStoppedRestoreReview(
    draft: LocalSnapshotStoppedRestoreDraft,
    selectedPlace: String,
    eligible: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val preview = TorrentMetainfoReader.preview(draft.original.bytes)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (draft.recoveryOnly) "Check previous stopped restore"
                else "Confirm one stopped snapshot restore",
                style = MaterialTheme.typography.titleMedium)
            Text("Original: " + preview.name)
            Text("Exact infohash: " + preview.infoHashV1,
                style = MaterialTheme.typography.bodySmall)
            Text("New local destination: " + selectedPlace)
            if (draft.recoveryOnly) {
                Text("Checks the previous restore journal against this local device's " +
                    "current transfer list, stopped state and selected folder. " +
                    "This action never adds, starts, stops, removes or retries a transfer.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Choose the same local destination that was reviewed for the " +
                    "original attempt. A matching torrent hash alone cannot " +
                    "confirm the restore. If the journal or folder differs, " +
                    "the attempt remains unresolved.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Text("This adds this original torrent definition to the local device engine " +
                    "stopped, without transferring any downloaded files from the old " +
                    "device or server. No historical paths are reused. Existing " +
                    "transfers are never overwritten or removed. The original tracker " +
                    "addresses may contain private passkeys; Chunks will not start " +
                    "contacting peers or trackers until you press Start separately.",
                    style = MaterialTheme.typography.bodySmall)
                Text("A durable journal records an attempted Add before submission. " +
                    "If confirmation or the app is interrupted, Chunks will require " +
                    "a fresh inventory and explicit recovery review, never an " +
                    "automatic second Add.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (!eligible) {
                Text("The local inventory or selected destination is unavailable " +
                    "or has changed. Refresh and recompare before proceeding.",
                    color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = onConfirm, enabled = eligible,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (draft.recoveryOnly) "Check previous restore (read-only)"
                    else "Confirm restore stopped")
            }
            OutlinedButton(onClick = onCancel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Cancel restore")
            }
        }
    }
}
