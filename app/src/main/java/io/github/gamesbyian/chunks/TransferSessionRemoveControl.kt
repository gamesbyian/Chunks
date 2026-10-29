package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.rpc.SessionEraseOutcome
import kotlinx.coroutines.launch

/**
 * A single confirmed rTorrent session removal. Not a payload-file deletion.
 * Only the main server view can authorize this action; no blind repeat after
 * an uncertain response. The remote adapter separately guards name and path.
 */
@Composable
fun TransferSessionRemoveControl(
    remote: RTorrentRemote,
    transfer: RemoteTransfer,
    snapshotStale: Boolean,
    mainView: Boolean,
    onMutationStarted: () -> Boolean,
    onSettled: () -> Unit
) {
    val validHash = remember(transfer.hash) { Regex("[0-9a-fA-F]{40}").matches(transfer.hash) }
    var confirming by rememberSaveable(transfer.hash, transfer.name, transfer.directory) {
        mutableStateOf(false)
    }
    var attempted by rememberSaveable(transfer.hash, transfer.name, transfer.directory) {
        mutableStateOf(false)
    }
    var pending by remember(transfer.hash, transfer.name, transfer.directory) {
        mutableStateOf(false)
    }
    var feedback by rememberSaveable(transfer.hash, transfer.name, transfer.directory) {
        mutableStateOf<String?>(null)
    }
    var verified by rememberSaveable(transfer.hash, transfer.name, transfer.directory) {
        mutableStateOf(false)
    }
    val scope = rememberCoroutineScope()

    if (!validHash) {
        Text("Remove is available only for torrents with an exact v1 infohash.",
            style = MaterialTheme.typography.bodySmall)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!attempted && !confirming) {
            ActionTile("Remove from rTorrent…", ChunksColors.Orange,
                onClick = { confirming = true },
                enabled = mainView && !snapshotStale,
                modifier = Modifier.fillMaxWidth(),
                icon = R.drawable.chunks_ic_remove, stacked = false)
            if (!mainView) {
                Text("Switch to the main server view before removing a transfer.",
                    style = MaterialTheme.typography.bodySmall)
            } else if (snapshotStale) {
                Text("Refresh this stale server snapshot before removing a transfer.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (confirming) {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )) {
                Column(Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Confirm removal from rTorrent",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                    Text(transfer.name, style = MaterialTheme.typography.bodyMedium)
                    Text("Infohash: " + transfer.hash, style = MaterialTheme.typography.bodySmall)
                    Text("Current directory: " + transfer.directory,
                        style = MaterialTheme.typography.bodySmall)
                    Text("This removes the torrent from rTorrent's session. Chunks does not request payload-file deletion. Custom server hooks may have additional effects. Refresh and verify before any further action.",
                        style = MaterialTheme.typography.bodySmall)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (onMutationStarted()) {
                                confirming = false
                                attempted = true
                                pending = true
                                scope.launch {
                                    try {
                                        val response = remote.eraseFromSession(
                                            transfer.hash, transfer.name, transfer.directory)
                                        verified = response is SessionEraseOutcome.VerifiedRemoved
                                        feedback = when (response) {
                                            is SessionEraseOutcome.VerifiedRemoved ->
                                                "Verified: this infohash is absent from the main server view. Physical files were not inspected."
                                            is SessionEraseOutcome.AlreadyAbsent ->
                                                "Already absent from the main server view. No erase command was sent."
                                            is SessionEraseOutcome.PendingRemoval ->
                                                "The server acknowledged removal, but this torrent remains in the main view. Inspect and refresh before another action."
                                            is SessionEraseOutcome.Uncertain ->
                                                "Removal outcome uncertain. The server may already have removed it. Refresh by hash; never blindly repeat."
                                        }
                                    } catch (_: Exception) {
                                        verified = false
                                        feedback = "Could not verify removal. The server state may have changed. Refresh before trying anything else."
                                    } finally {
                                        pending = false
                                        onSettled()
                                    }
                                }
                                } else {
                                    confirming = false
                                    attempted = true
                                    verified = false
                                    feedback = "Another remote command needs fresh readback before any further action."
                                }
                            },
                            enabled = !pending && mainView && !snapshotStale,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) { Text("Confirm remove") }
                        OutlinedButton(
                            onClick = { confirming = false },
                            enabled = !pending,
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("Cancel") }
                    }
                }
            }
        }
        if (attempted) {
            Card(colors = CardDefaults.cardColors(
                containerColor = if (verified) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.errorContainer
            )) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Removal result", fontWeight = FontWeight.Bold)
                    Text(if (pending) "Checking the server's main view…" else
                        feedback ?: "Result unavailable. Inspect the server.")
                }
            }
        }
    }
}
