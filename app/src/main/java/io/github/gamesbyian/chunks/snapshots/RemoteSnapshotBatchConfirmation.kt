package io.github.gamesbyian.chunks.snapshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Only this second, explicit confirmation permits a bounded remote batch. */
@Composable
internal fun RemoteSnapshotBatchConfirmation(
    review: SnapshotRemoteDefinitionRestore.BatchReview,
    accountLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore ${review.count} stopped server definitions?") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(accountLabel)
                Text((if (review.serverDefault) "Current server default Place: "
                    else "Explicitly reviewed server folder: ") + review.place)
                review.originals.take(6).forEach { original ->
                    Text(original.name + " · " + original.hash.take(12))
                }
                if (review.count > 6) Text("${review.count - 6} more original definitions")
                Text("Each original .torrent, including its trackers, is submitted to this " +
                    "account ONLY after the preceding stopped registration is independently " +
                    "verified at this Place. Any uncertain result stops the remainder. " +
                    "No transfer starts, payload is copied or priorities are applied.")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Confirm ${review.count} stopped restores")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancel")
            }
        }
    )
}
