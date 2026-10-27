package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import kotlinx.coroutines.launch

/**
 * No optimistic mutation. One explicitly confirmed start/stop per presentation.
 * The caller refreshes the server view after attempts, even if the response
 * is uncertain. The user must inspect current remote state before another.
 */
@Composable
fun TransferLifecycleControl(
    remote: RTorrentRemote,
    transfer: RemoteTransfer,
    snapshotStale: Boolean,
    onMutationStarted: () -> Boolean,
    onSettled: () -> Unit
) {
    val validV1Hash = remember(transfer.hash) { Regex("[0-9a-fA-F]{40}").matches(transfer.hash) }
    var confirming by rememberSaveable(transfer.hash, transfer.name, transfer.directory) { mutableStateOf(false) }
    var attempted by rememberSaveable(transfer.hash, transfer.name, transfer.directory) { mutableStateOf(false) }
    var pending by remember(transfer.hash, transfer.name, transfer.directory) { mutableStateOf(false) }
    var feedback by remember(transfer.hash) { mutableStateOf<String?>(null) }
    var verified by rememberSaveable(transfer.hash, transfer.name, transfer.directory) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val requestedStarted = !transfer.started
    val verb = if (requestedStarted) "Start" else "Stop"

    if (!validV1Hash) {
        Text("This transfer hash format is not yet supported for lifecycle controls.",
            style = MaterialTheme.typography.bodySmall)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!confirming && !attempted) {
            OutlinedButton(
                onClick = { confirming = true },
                enabled = !snapshotStale,
                modifier = Modifier.height(48.dp)
            ) { Text(verb + " transfer…") }
            if (snapshotStale) {
                Text("Refresh this stale server snapshot before changing a transfer.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (confirming) {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Confirm " + verb.lowercase() + " for this torrent",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                    Text(transfer.name, style = MaterialTheme.typography.bodyMedium)
                    Text("Exact infohash: " + transfer.hash,
                        style = MaterialTheme.typography.bodySmall)
                    Text("The server will change this transfer's running state. Chunks will read it back and will not retry automatically.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (onMutationStarted()) {
                                confirming = false
                                attempted = true
                                pending = true
                                scope.launch {
                                    try {
                                        val outcome = remote.setStarted(transfer.hash, requestedStarted)
                                        verified = outcome is LifecycleOutcome.Verified
                                        feedback = when (outcome) {
                                            is LifecycleOutcome.Verified ->
                                                "Verified on server: " + (if (outcome.started) "started" else "stopped") + "."
                                            is LifecycleOutcome.Pending ->
                                                "Server reports " + (if (outcome.observedStarted) "started" else "stopped") +
                                                    ", not the requested state. Refresh before considering another action."
                                            is LifecycleOutcome.Uncertain ->
                                                "RPC outcome uncertain. The operation may already have happened. Refresh before considering another action."
                                        }
                                    } catch (_: Exception) {
                                        verified = false
                                        feedback = "Could not verify the operation. Inspect the latest server state before retrying."
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
                            enabled = !pending && !snapshotStale,
                            modifier = Modifier.height(48.dp)
                        ) { Text("Confirm " + verb.lowercase()) }
                        OutlinedButton(
                            onClick = { confirming = false },
                            enabled = !pending,
                            modifier = Modifier.height(48.dp)
                        ) { Text("Cancel") }
                    }
                }
            }
        }
        if (attempted) {
            Card(colors = CardDefaults.cardColors(
                containerColor = if (verified) MaterialTheme.colorScheme.secondaryContainer
                                 else MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Lifecycle result", fontWeight = FontWeight.Bold)
                    Text(if (pending) "Checking the server's state…" else
                        feedback ?: "Result unavailable; inspect server state.")
                }
            }
        }
    }
}
