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

/** Process-only original definitions and complete-inventory review, never SavedState. */
internal data class LocalSnapshotBatchStoppedRestoreDraft(
    val originals: List<SnapshotVerifiedTorrentDocumentV1.Prepared>,
    val archiveFingerprint: String,
    val frozenHashes: List<String>,
    val frozenGeneration: Long
)

/**
 * This is the ONE explicit authorization for a bounded group. Files are
 * independently prepared before entering it, and no source/historical path is
 * persisted or displayed as a new destination. Each item must be confirmed
 * stopped at this same current Local Place before the next Add can begin.
 */
@Composable
internal fun LocalSnapshotBatchStoppedRestoreReview(
    draft: LocalSnapshotBatchStoppedRestoreDraft,
    selectedPlace: String,
    eligible: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Confirm ${draft.originals.size} stopped snapshot restores",
                style = MaterialTheme.typography.titleMedium)
            Text("New local destination: $selectedPlace")
            draft.originals.take(5).forEach {
                Text(it.originalName + " · " + it.infoHashV1.take(12),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (draft.originals.size > 5)
                Text("And ${draft.originals.size - 5} more verified definitions.",
                    style = MaterialTheme.typography.bodySmall)
            Text("This one confirmation authorizes up to ${draft.originals.size} separate " +
                "stopped Local Adds, in order. Chunks independently verifies each one " +
                "at the selected folder before proceeding. The first uncertain Add, " +
                "stale inventory or journal error stops the rest. It never retries " +
                "an uncertain write, starts a transfer or copies downloaded files.",
                style = MaterialTheme.typography.bodySmall)
            Text("These originals may contain private tracker passkeys. The snapshot's " +
                "old server paths and settings are not reused. You must Start any " +
                "successfully restored transfer separately.",
                style = MaterialTheme.typography.bodySmall)
            if (!eligible) Text(
                "The local inventory, current destination or reviewed source changed. " +
                    "Refresh and compare the snapshot before another confirmation.",
                color = MaterialTheme.colorScheme.error
            )
            Button(onClick = onConfirm, enabled = eligible,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Confirm ${draft.originals.size} stopped restores")
            }
            OutlinedButton(onClick = onCancel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Cancel batch restore")
            }
        }
    }
}
