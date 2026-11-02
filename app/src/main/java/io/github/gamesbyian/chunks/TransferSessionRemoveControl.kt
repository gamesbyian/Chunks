package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import io.github.gamesbyian.chunks.rpc.SessionEraseRecheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Postconditions are exact-session hash reads, not membership of the named
 * `main` view. A torrent can remain in rTorrent's session while hidden from
 * `main`, so never misdescribe a hidden-view result as session removal.
 */
internal fun sessionEraseFeedback(response: SessionEraseOutcome): String = when (response) {
    is SessionEraseOutcome.VerifiedRemoved ->
        "Verified: this exact infohash is absent from rTorrent's session. Physical files were not inspected."
    is SessionEraseOutcome.AlreadyAbsent ->
        "Already absent from rTorrent's session by exact infohash check. No erase command was sent."
    is SessionEraseOutcome.PendingRemoval ->
        "rTorrent still reports this exact infohash in its session after the removal request. Refresh and inspect before another action."
    is SessionEraseOutcome.Uncertain ->
        "Removal outcome uncertain. The server may already have removed this exact infohash. Refresh by hash; never blindly repeat."
}

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
    onSettled: () -> Unit,
    performErase: suspend (String, String, String) -> SessionEraseOutcome = remote::eraseFromSession,
    recheck: suspend (String, String, String) -> SessionEraseRecheck = remote::recheckSessionErase
) {
    val validHash = remember(transfer.hash) { Regex("[0-9a-fA-F]{40}").matches(transfer.hash) }
    var confirming by rememberSaveable(transfer.hash, transfer.name, transfer.directory) {
        mutableStateOf(false)
    }
    var attempted by rememberSaveable(transfer.hash, transfer.name, transfer.directory) {
        mutableStateOf(false)
    }
    var pending by remember(remote, transfer.hash, transfer.name, transfer.directory) {
        mutableStateOf(false)
    }
    var checking by remember(remote, transfer.hash, transfer.name, transfer.directory) {
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
                enabled = mainView && !snapshotStale && !pending && !checking,
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
                    Text("Choose what to remove. Removing the transfer keeps downloaded files on the server. Custom server hooks could still have additional effects.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = {}, enabled = false,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Remove transfer and data (unavailable)") }
                    Text("Deleting server files is unavailable: rTorrent session removal cannot safely identify and delete only this transfer's data. Use your server file manager if you choose to remove those files.",
                        style = MaterialTheme.typography.bodySmall)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (mainView && !snapshotStale && !pending && !checking &&
                                    onMutationStarted()) {
                                    confirming = false
                                    attempted = true
                                    pending = true
                                    scope.launch {
                                        try {
                                            val response = performErase(
                                                transfer.hash, transfer.name, transfer.directory)
                                            verified = response is SessionEraseOutcome.VerifiedRemoved ||
                                                response is SessionEraseOutcome.AlreadyAbsent
                                            feedback = sessionEraseFeedback(response)
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) {
                                            verified = false
                                            feedback = "Removal could not be verified. Nothing will be retried automatically. Check the exact server session before another attempt."
                                        } finally {
                                            pending = false
                                            onSettled()
                                        }
                                    }
                                } else {
                                    confirming = false
                                    // A denied gate sent NO command. Never permanently lock
                                    // Remove because another mutation was already pending.
                                    feedback = "No Remove command was sent. Refresh and try again after the current command settles."
                                }
                            },
                            enabled = !pending && mainView && !snapshotStale,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        ) { Text("Remove transfer, keep data") }
                        OutlinedButton(
                            onClick = { confirming = false },
                            enabled = !pending,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
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
                Column(Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Removal result", fontWeight = FontWeight.Bold)
                    Text(if (pending) "Checking exact server session…" else
                        feedback ?: "Result unavailable. Inspect the server.")
                    if (!pending && !verified) {
                        OutlinedButton(
                            onClick = {
                                checking = true
                                scope.launch {
                                    try {
                                        when (recheck(transfer.hash, transfer.name, transfer.directory)) {
                                            SessionEraseRecheck.PresentMatching -> {
                                                // Fresh exact hash/name/directory. A NEW tap,
                                                // confirmation and guarded preflight is required.
                                                attempted = false
                                                feedback = "The same transfer is still in this server's session. You can review a new Remove attempt."
                                            }
                                            SessionEraseRecheck.Absent -> {
                                                verified = true
                                                feedback = "The exact transfer is now absent from rTorrent's session. No new Remove was sent; downloaded files were not inspected."
                                            }
                                            SessionEraseRecheck.Changed ->
                                                feedback = "That hash now has a different name or directory. No retry is allowed from this old review. Refresh the transfer list."
                                            SessionEraseRecheck.Unavailable ->
                                                feedback = "Could not verify the exact server identity. No retry is allowed until a new successful check."
                                        }
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (_: Exception) {
                                        feedback = "Server check failed. Nothing was removed or retried by this check."
                                    } finally { checking = false }
                                }
                            },
                            enabled = !checking && !snapshotStale && mainView,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text(if (checking) "Checking server…" else "Check current session (read-only)") }
                    }
                }
            }
        }
    }
}
