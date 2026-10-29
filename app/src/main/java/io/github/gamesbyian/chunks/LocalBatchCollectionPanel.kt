package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.CollectionMembershipRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explicit, source-safe local Collection assignment. A transfer's Collection
 * membership is independent of native libtorrent metadata and download paths.
 *
 * Only observed displayed results are selectable; if the engine list changes,
 * the selection is discarded rather than silently targeting stale identities.
 */
@Composable
internal fun LocalBatchCollectionPanel(
    transfers: List<LocalTransferStatus>,
    shown: List<LocalTransferStatus>,
    inventoryStale: Boolean,
    store: TransferCollectionStore,
    onMembershipChanged: () -> Unit,
    /** Reviewed Start (true) / Stop (false) of exactly the selected identities. */
    onBatchLifecycle: ((List<String>, Boolean) -> Unit)? = null,
    /** Signed-in server host for batch Copy Transfer to server; null hides it. */
    copyServerLabel: String? = null,
    onBatchCopyToServer: ((List<String>) -> Unit)? = null,
    /** Batch Move step 1 (verified destination Adds + journal); null hides it. */
    onBatchMoveToServer: ((List<String>) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var draft by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var lifecycleConfirm by remember { mutableStateOf<Boolean?>(null) }
    var copyConfirm by remember { mutableStateOf(false) }
    var moveConfirm by remember { mutableStateOf(false) }
    // A fresh list can reorder or remove items behind a card. Reset every
    // outstanding batch when the exact observed identities or stale gate change.
    val keys = remember(transfers) { transfers.map { it.infoHash }.sorted() }
    LaunchedEffect(keys, inventoryStale) {
        selected = emptySet()
        confirming = false
        lifecycleConfirm = null
        copyConfirm = false
        moveConfirm = false
        if (inventoryStale) expanded = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = {
            expanded = !expanded
            selected = emptySet()
            confirming = false
            message = null
        }, enabled = !saving && !inventoryStale && transfers.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (expanded) "Close local batch selection" else "Batch organize phone transfers")
        }
        if (!expanded) return@Column
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add selected transfers to a Collection",
                    style = MaterialTheme.typography.titleMedium)
                Text("${selected.size} selected. This only changes Chunks metadata on this phone. " +
                    "No torrent, download file, folder, or seeding behavior changes.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val next = selected + shown.map { it.infoHash }
                        if (next.size > 512) message = "Limit 512 transfers per batch. Narrow the results."
                        else { selected = next; confirming = false; message = null }
                    }, enabled = !saving && !inventoryStale && shown.isNotEmpty(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("Select shown (${shown.size})")
                    }
                    OutlinedButton(onClick = { selected = emptySet(); confirming = false },
                        enabled = !saving && selected.isNotEmpty(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("Clear")
                    }
                }
                // One list of compact choices rather than duplicate heavy transfer cards.
                shown.take(20).forEach { transfer ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = transfer.infoHash in selected,
                            enabled = !saving && !inventoryStale,
                            role = Role.Checkbox,
                            onValueChange = { checked ->
                                selected = if (checked && selected.size < 512)
                                    selected + transfer.infoHash
                                else if (checked) selected
                                else selected - transfer.infoHash
                                confirming = false
                            }
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = transfer.infoHash in selected, onCheckedChange = null)
                        Text(transfer.name, modifier = Modifier.weight(1f), maxLines = 2)
                    }
                }
                if (shown.size > 20) {
                    Text("Only the first 20 results have individual checkboxes here. " +
                        "Use Select shown for all ${shown.size} matching transfers.",
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = draft,
                    onValueChange = { draft = it; confirming = false },
                    label = { Text("Collection name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                if (confirming) {
                    Text("Confirm saving ${selected.size} selected memberships to " +
                        "'${draft.trim()}' on this phone.")
                }
                Button(onClick = {
                    val name = CollectionMembershipRules.cleanName(draft)
                    val ids = selected.toList()
                    if (name == null || ids.isEmpty()) {
                        message = "Select transfers and enter a valid Collection."
                    } else if (!confirming) {
                        confirming = true
                    } else {
                        saving = true
                        scope.launch {
                            try {
                                val saved = withContext(Dispatchers.IO) {
                                    store.addBatch(TransferCollectionStore.LOCAL_ENGINE_SCOPE,
                                        ids, name) && ids.all { hash ->
                                            store.forTransfer(
                                                TransferCollectionStore.LOCAL_ENGINE_SCOPE, hash
                                            ).any { it.equals(name, ignoreCase = true) }
                                        }
                                }
                                if (saved) {
                                    message = "Saved ${ids.size} local Collection memberships."
                                    selected = emptySet()
                                    expanded = false
                                    draft = ""
                                    onMembershipChanged()
                                } else {
                                    message = "Save could not be verified. Inspect current " +
                                        "Collection memberships before another attempt."
                                }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                message = "Save outcome uncertain; inspect memberships before retry."
                            } finally {
                                confirming = false
                                saving = false
                            }
                        }
                    }
                }, enabled = !inventoryStale && !saving && selected.isNotEmpty() &&
                    CollectionMembershipRules.cleanName(draft) != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (confirming) "Confirm local batch Collection" else
                        "Review local Collection assignment")
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (onBatchCopyToServer != null && copyServerLabel != null) {
                    Text("Copy selected to the server", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { copyConfirm = !copyConfirm },
                        enabled = !saving && !inventoryStale && selected.isNotEmpty() &&
                            selected.size <= io.github.gamesbyian.chunks.model.BatchServerCopy.MAX_ITEMS,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Copy Transfer to server (${selected.size})…")
                    }
                    if (copyConfirm) {
                        Text("Adds each selected torrent's exact definition to $copyServerLabel as a new, " +
                            "stopped job at the server's default Place, one at a time. Downloaded files " +
                            "are not transferred: the server downloads on its own, and these phone " +
                            "transfers stay exactly as they are. Torrents already on the server are " +
                            "skipped. Tracker addresses (which can include private passkeys) go only to " +
                            "this server. If an Add cannot be confirmed, Chunks stops there without retrying.",
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            copyConfirm = false
                            val ids = transfers.filter { it.infoHash in selected }.map { it.infoHash }
                            selected = emptySet()
                            onBatchCopyToServer(ids)
                        }, enabled = !saving && !inventoryStale,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Confirm copy to server")
                        }
                    }
                }
                if (onBatchMoveToServer != null && copyServerLabel != null) {
                    OutlinedButton(onClick = { moveConfirm = !moveConfirm },
                        enabled = !saving && !inventoryStale && selected.isNotEmpty() &&
                            selected.size <= io.github.gamesbyian.chunks.model.BatchServerCopy.MAX_ITEMS,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Move Transfer to server (${selected.size})…")
                    }
                    if (moveConfirm) {
                        Text("Moves the torrent jobs, not their files, in two steps. First Chunks adds each " +
                            "selected torrent to $copyServerLabel as a stopped job and confirms it there. " +
                            "Then you review and separately confirm removing the original phone jobs. " +
                            "Downloaded files stay on this phone and keep using its storage; the server " +
                            "may download everything again.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            moveConfirm = false
                            val ids = transfers.filter { it.infoHash in selected }.map { it.infoHash }
                            selected = emptySet()
                            onBatchMoveToServer(ids)
                        }, enabled = !saving && !inventoryStale,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Add to server, then review removal")
                        }
                    }
                }
                if (onBatchLifecycle != null) {
                    val chosen = transfers.filter { it.infoHash in selected }
                    val toStart = chosen.count { it.stopped }
                    val toStop = chosen.size - toStart
                    Text("Start or stop selected", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { lifecycleConfirm = true },
                            enabled = !saving && !inventoryStale && toStart > 0,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Start $toStart") }
                        OutlinedButton(onClick = { lifecycleConfirm = false },
                            enabled = !saving && !inventoryStale && toStop > 0,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Stop $toStop") }
                    }
                    lifecycleConfirm?.let { start ->
                        Text("${if (start) "Start" else "Stop"} ${if (start) toStart else toStop} selected " +
                            "phone transfers one at a time. If any state cannot be confirmed, Chunks " +
                            "stops there without retrying. Files stay where they are.",
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            lifecycleConfirm = null
                            val ids = chosen.map { it.infoHash }
                            selected = emptySet()
                            onBatchLifecycle(ids, start)
                        }, enabled = !saving && !inventoryStale,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (start) "Confirm Start" else "Confirm Stop")
                        }
                    }
                }
            }
        }
    }
}
