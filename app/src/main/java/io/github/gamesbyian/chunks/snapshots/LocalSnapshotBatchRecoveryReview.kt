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

/** Process-only readback request; never stores archive contents or historical paths. */
internal data class LocalSnapshotBatchRecoveryDraft(
    val hashes: List<String>,
    val archiveFingerprint: String,
    val frozenHashes: List<String>,
    val frozenGeneration: Long
)

/** One separate, explicit *read-only to the engine* batch recovery review. */
@Composable
internal fun LocalSnapshotBatchRecoveryReview(
    draft: LocalSnapshotBatchRecoveryDraft,
    selectedPlace: String,
    eligible: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Check previous stopped snapshot restores",
                style = MaterialTheme.typography.titleMedium)
            Text("Definitions to check: ${draft.hashes.size}")
            Text("Previously reviewed local destination: $selectedPlace")
            Text("Select the SAME folder originally approved for these restores. " +
                "Chunks will reopen only existing journals, then check the current " +
                "complete Local transfer inventory, stopped state and exact folder. " +
                "A matching hash alone never proves restoration.",
                style = MaterialTheme.typography.bodySmall)
            Text("No transfer will be added, started, stopped, retried, removed or " +
                "downloaded. Independently confirmed stopped jobs may have their " +
                "existing journal marked verified. Missing or unreadable journals " +
                "remain unresolved; this action never initializes replacements.",
                style = MaterialTheme.typography.bodySmall)
            if (!eligible) Text(
                "The selected folder or local inventory is not ready or changed. " +
                    "Refresh the snapshot comparison before checking again.",
                color = MaterialTheme.colorScheme.error)
            Button(onClick = onConfirm, enabled = eligible,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Check ${draft.hashes.size} previous restores (read-only)")
            }
            OutlinedButton(onClick = onCancel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Cancel restore check")
            }
        }
    }
}
