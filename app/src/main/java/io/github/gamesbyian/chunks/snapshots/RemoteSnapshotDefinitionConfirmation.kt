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

@Composable
internal fun RemoteSnapshotDefinitionConfirmation(
    review: SnapshotRemoteDefinitionRestore.Review,
    accountLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (review.recoveryOnly) "Check previous server restore" else "Restore stopped") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(accountLabel)
                Text(review.name)
                Text("Infohash: " + review.hash)
                Text((if (review.serverDefault) "Server default Place: "
                    else "Explicitly reviewed server folder: ") + review.place)
                if (review.recoveryOnly) {
                    Text("Checks the journal and the transfer at the reviewed Place. Read-only.")
                } else {
                    Text("Sends the original torrent definition, including its tracker addresses, " +
                        "to this account and leaves the transfer stopped. Saved priorities require " +
                        "a separate confirmation.")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (review.recoveryOnly) "Check restore" else "Confirm stopped restore")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        }
    )
}
