package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.ServerToPhoneCopy
import io.github.gamesbyian.chunks.model.Units
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Copy files to this phone" for a completed server transfer. Checking only
 * reads from the server; confirming opens the phone's own magnet Add review,
 * where the Place and Start are chosen and confirmed as usual. The server
 * transfer is never changed (Copy, not Move).
 */
@Composable
internal fun ServerToPhoneCopyControl(
    remote: RTorrentRemote,
    transfer: RemoteTransfer,
    stale: Boolean,
    onReviewPhoneAdd: (String) -> Unit
) {
    if (transfer.sizeBytes <= 0 || transfer.completedBytes != transfer.sizeBytes) return
    val scope = rememberCoroutineScope()
    var checking by remember(transfer.hash) { mutableStateOf(false) }
    var plan by remember(transfer.hash) { mutableStateOf<ServerToPhoneCopy.Plan?>(null) }
    var addressDraft by remember(transfer.hash) { mutableStateOf("") }
    var editingAddress by remember(transfer.hash) { mutableStateOf(false) }

    fun check(addressOverride: String?) {
        checking = true
        plan = null
        scope.launch {
            plan = try {
                val source = remote.peerSource(transfer.hash)
                withContext(Dispatchers.IO) {
                    ServerToPhoneCopy.plan(transfer, source, { InetAddress.getAllByName(it).toList() },
                        addressOverride)
                }
            } catch (_: Exception) {
                ServerToPhoneCopy.Refused("Chunks could not read the server's peer details. Nothing changed.")
            } finally {
                checking = false
            }
        }
    }

    ActionTile(if (checking) "Checking the server…" else "Copy files to this phone", ChunksColors.Yellow,
        onClick = { editingAddress = false; check(null) },
        enabled = !stale && !checking,
        modifier = Modifier.fillMaxWidth(),
        icon = R.drawable.chunks_ic_folder, stacked = false)

    when (val current = plan) {
        is ServerToPhoneCopy.Ready -> {
            Text("Chunks will download all " + Units.bytes(current.bytes) + " onto this phone, " +
                "directly from your server at " + current.peer + " (" + current.addressSource.label +
                "), and verify every piece against " +
                "the torrent's own hashes. The server transfer stays exactly as it is and keeps " +
                "seeding; it uploads this data to the phone. This uses the phone's storage and " +
                "network data, and other public peers may also contribute pieces.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { plan = null; onReviewPhoneAdd(current.magnet) }, enabled = !stale,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Review phone Add") }
                OutlinedButton(onClick = { plan = null },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
            }
        }
        is ServerToPhoneCopy.Refused -> {
            Text(current.reason, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            LaunchedEffect(current) { if (current.addressProblem) editingAddress = true }
            OutlinedButton(onClick = { plan = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Close")
            }
        }
        null -> Unit
    }
    if (plan is ServerToPhoneCopy.Ready && !editingAddress) {
        TextButton(onClick = { editingAddress = true }, enabled = !checking,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("Use a different server address…") }
    }
    if (editingAddress) {
        Text("Enter the IP address or hostname your server's BitTorrent client listens on " +
            "(your provider's dashboard may list it). Only this peer hint changes; nothing is sent " +
            "to the server.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = addressDraft, onValueChange = { addressDraft = it.take(253) },
            label = { Text("Server peer address") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { check(addressDraft) },
                enabled = !stale && !checking && addressDraft.isNotBlank(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Check with this address") }
            OutlinedButton(onClick = { editingAddress = false; addressDraft = "" },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
        }
    }
}
