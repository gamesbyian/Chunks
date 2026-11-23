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

/**
 * One explicit approval for a bounded series of individually verified
 * non-idempotent stopped rTorrent Adds. A review or folder pick is never
 * permission to submit this batch. The draft remains in process memory.
 */
@Composable
internal fun RemoteSnapshotBatchStoppedConfirmation(
    draft: SnapshotRemoteBatchStoppedRestore.Draft,
    accountLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Confirm ${draft.count} stopped server restores") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("rTorrent account: $accountLabel")
                Text((if (draft.serverDefault) "Current server default Place: "
                    else "Reviewed server folder: ") + draft.place)
                Text("These original torrent definitions may contain private tracker " +
                    "addresses and passkeys. Confirmation sends them to this exact " +
                    "rTorrent account one at a time and leaves each new job STOPPED.")
                for ((hash, name) in draft.items.take(8)) {
                    Text(name + " · " + hash.take(12))
                }
                if (draft.count > 8) Text("And ${draft.count - 8} more verified originals.")
                Text("Chunks checks EVERY previous restore journal before the first " +
                    "server Add, then independently checks each stopped job and " +
                    "folder before proceeding. The first uncertain result stops " +
                    "all remaining work. No retries, rollback, Start or downloaded " +
                    "payload copies are attempted.")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 52.dp)) {
                Text("Confirm ${draft.count} stopped restores")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Cancel")
            }
        }
    )
}
