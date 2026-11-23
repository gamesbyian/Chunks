package io.github.gamesbyian.chunks.snapshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The read-only snapshot comparison never submits a priority change. The user
 * must explicitly inspect and approve this separate confirmation dialog.
 */
@Composable
internal fun RemoteSnapshotPriorityConfirmation(
    entry: SnapshotRestorePreviewV1.Entry,
    review: SnapshotRemotePriorityComparison,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var page by remember(entry) { mutableIntStateOf(0) }
    val changes = review.rows.filter { it.saved != it.current }
    val pageSize = 8
    val offset = page * pageSize
    val end = minOf(offset + pageSize, changes.size)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply saved rTorrent file priorities?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.originalName)
                Text("Exact connected rTorrent account · Infohash ${entry.infoHashV1}")
                Text("${changes.size} priority changes will be submitted one by one. " +
                    "Every write is independently verified. Any uncertain reply stops " +
                    "the remaining changes; there is no automatic retry. " +
                    "This does not add, start, stop or move the transfer.")
                for (i in offset until end) {
                    val row = changes[i]
                    Text("#${row.file.index} ${row.file.path} (${row.file.sizeBytes} bytes)\n" +
                        "${row.current.label} → ${row.saved.label}",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (changes.size > pageSize) {
                    Text("Changed files ${offset + 1}–$end of ${changes.size}")
                    Row {
                        TextButton(onClick = { page-- }, enabled = page > 0,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Previous") }
                        TextButton(onClick = { page++ }, enabled = end < changes.size,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Next") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm,
                enabled = changes.isNotEmpty() && review.unsupportedCount == 0,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Apply ${changes.size} priorities")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancel")
            }
        }
    )
}
