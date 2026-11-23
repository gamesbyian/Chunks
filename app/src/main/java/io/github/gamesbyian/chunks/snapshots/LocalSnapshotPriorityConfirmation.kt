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

@Composable
internal fun LocalSnapshotPriorityConfirmation(
    review: LocalSnapshotPriorityRestore,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var page by remember(review) { mutableIntStateOf(0) }
    val pageSize = 8
    val offset = page * pageSize
    val end = minOf(offset + pageSize, review.files.size)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply saved file priorities?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(review.target.name)
                Text("Local Device · Stopped")
                Text("Infohash ${review.target.infoHash}")
                Text("Current folder ${review.target.place}")
                Text("${review.changeCount} of ${review.files.size} file priorities will change. " +
                    "The transfer stays stopped.")
                for (index in offset until end) {
                    val saved = review.files[index]
                    val current = review.target.files[index].priority
                    val label = SnapshotFilePriorities.Level.entries
                        .firstOrNull { it.local == current }?.label ?: "Native priority $current"
                    Text("#${saved.file.index} ${saved.file.path} (${saved.file.sizeBytes} bytes)\n" +
                        "$label → ${saved.level.label}", style = MaterialTheme.typography.bodySmall)
                }
                if (review.files.size > pageSize) {
                    Text("Files ${offset + 1}–$end of ${review.files.size}")
                    Row {
                        TextButton(onClick = { page-- }, enabled = page > 0,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Previous") }
                        TextButton(onClick = { page++ }, enabled = end < review.files.size,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Next") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Apply priorities")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancel")
            }
        }
    )
}
