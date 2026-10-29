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
import androidx.compose.material3.FilterChip
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
import io.github.gamesbyian.chunks.rpc.FilePriorityOutcome
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteFile
import kotlinx.coroutines.launch

/**
 * Explicitly selected file only. A stale listing or non-main server view
 * cannot authorize any RPC write; the adapter also preflights path, size,
 * indexed identity and old priority against the live server.
 */
@Composable
internal fun TransferFilePriorityControl(
    remote: RTorrentRemote,
    hash: String,
    index: Int,
    file: RemoteFile,
    snapshotStale: Boolean,
    mainView: Boolean,
    onMutationStarted: () -> Boolean,
    onSettled: () -> Unit
) {
    val valid = remember(hash, index) {
        Regex("[0-9a-fA-F]{40}").matches(hash) && index in 0..11999
    }
    var desired by rememberSaveable(hash, index, file.path, file.sizeBytes, file.priority) {
        mutableStateOf(file.priority)
    }
    var confirming by rememberSaveable(hash, index, file.path, file.sizeBytes, file.priority) { mutableStateOf(false) }
    var attempted by rememberSaveable(hash, index, file.path, file.sizeBytes, file.priority) { mutableStateOf(false) }
    var pending by remember(hash, index, file.path, file.sizeBytes, file.priority) { mutableStateOf(false) }
    var feedback by remember(hash, index, file.path, file.priority) { mutableStateOf<String?>(null) }
    var verified by rememberSaveable(hash, index, file.path, file.sizeBytes, file.priority) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val allowed = valid && file.priority in 0..2 && mainView && !snapshotStale
    val choices = listOf("Skip", "Normal", "High")

    AccentOutlineCard(ChunksColors.Green) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Edit one file priority", fontWeight = FontWeight.Bold)
            Text("File ${index + 1}: " + file.path,
                style = MaterialTheme.typography.bodyMedium)
            Text("Current: " + choices.getOrElse(file.priority) { "Unknown" },
                style = MaterialTheme.typography.bodySmall)
            if (!valid || file.priority !in 0..2) {
                Text("This file identity or priority is not supported for editing.",
                    color = MaterialTheme.colorScheme.error)
            } else if (!mainView || snapshotStale) {
                Text("Switch to the main server view and refresh the current snapshot before changing file priorities.",
                    color = MaterialTheme.colorScheme.error)
            }
            if (!attempted && !confirming && allowed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (value in 0..2) {
                        FilterChip(
                            selected = desired == value,
                            onClick = { desired = value },
                            label = { Text(choices[value]) },
                            modifier = Modifier.height(48.dp)
                        )
                    }
                }
                OutlinedButton(
                    onClick = { confirming = true },
                    enabled = desired != file.priority,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Review priority change") }
            }
            if (confirming) {
                Text("Confirm file priority change",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)
                Text("Exact infohash: " + hash, style = MaterialTheme.typography.bodySmall)
                Text("File index: " + index + " · " + file.path,
                    style = MaterialTheme.typography.bodySmall)
                Text("From " + choices[file.priority] + " to " + choices[desired] + ". " +
                    "Skipping a file does not delete existing bytes, and overlapping pieces may still be downloaded.",
                    style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        if (onMutationStarted()) {
                        confirming = false
                        attempted = true
                        pending = true
                        scope.launch {
                            try {
                                val result = remote.setFilePriority(
                                    hash, index, file.path, file.sizeBytes,
                                    file.priority, desired)
                                verified = result is FilePriorityOutcome.Verified
                                feedback = when (result) {
                                    is FilePriorityOutcome.Verified ->
                                        "Verified by rTorrent: file priority is " + choices[result.priority] + "."
                                    is FilePriorityOutcome.Unchanged ->
                                        "The server already reports this priority; no change was sent."
                                    is FilePriorityOutcome.PendingVerification ->
                                        "The server did not yet verify the requested file priority. Refresh the file list before doing anything else."
                                    is FilePriorityOutcome.Uncertain ->
                                        "The priority request may have applied. Inspect the file list; never blindly repeat the change."
                                }
                            } catch (_: Exception) {
                                feedback = "Unable to verify file priority. A server change may have applied; refresh before attempting anything else."
                                verified = false
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
                    enabled = allowed && !pending,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Confirm priority") }
                OutlinedButton(
                    onClick = { confirming = false },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Cancel") }
            }
            if (attempted) {
                Text("Priority result", fontWeight = FontWeight.Bold)
                Text(if (pending) "Checking file priority on the server…" else
                    feedback ?: "Result unavailable. Inspect the remote file list.",
                    color = if (verified) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
