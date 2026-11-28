package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only compose inside the exact client's already-authorized Details route. */
@Composable
internal fun TransferNotesSection(scope: String, infoHash: String) {
    val context = LocalContext.current
    val store = remember(context) { TransferNotesStore(context) }
    key(scope, infoHash) {
        ScopedTransferNotes(scope, infoHash, store)
    }
}

@Composable
private fun ScopedTransferNotes(scope: String, infoHash: String, store: TransferNotesStore) {
    // Private user text must never enter saved instance state. Leaving or locking Details
    // disposes the editor; a new authorized entry reads the exact persisted value.
    var observed by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var showFull by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    val coroutine = rememberCoroutineScope()

    LaunchedEffect(reload, store) {
        unavailable = false
        observed = null
        try {
            val note = withContext(Dispatchers.IO) { store.read(scope, infoHash) }
            observed = note
            draft = note
            if (reload != 0) status = "Current saved note loaded."
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: RuntimeException) {
            unavailable = true
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Private note", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() })
            Text("Stored in Chunks on this device for this client and transfer. Never sent to the torrent client or trackers.",
                style = MaterialTheme.typography.bodySmall)
            val current = observed
            when {
                unavailable -> {
                    Text("Saved note unavailable.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    OutlinedButton(onClick = { reload++ }, enabled = !saving,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Reload saved note") }
                }
                current == null -> Text("Loading saved note…",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                editing -> {
                    Text("Unsaved changes are discarded when leaving or locking Details.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = draft, onValueChange = {
                            if (it.length <= TransferNotesStore.MAX_LENGTH && '\u0000' !in it) {
                                draft = it
                                status = null
                            }
                        }, label = { Text("Transfer note") }, enabled = !saving,
                        minLines = 3, maxLines = 8,
                        modifier = Modifier.fillMaxWidth().testTag("private-transfer-note-input")
                    )
                    Text("${draft.length} / ${TransferNotesStore.MAX_LENGTH}", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        enabled = !saving && draft != current,
                        onClick = {
                            if (saving) return@OutlinedButton
                            val proposed = draft
                            saving = true
                            status = "Saving private note…"
                            coroutine.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        store.save(scope, infoHash, current, proposed)
                                    }
                                    observed = result.observed
                                    when (result.status) {
                                        TransferNotesStore.SaveStatus.VERIFIED -> {
                                            draft = result.observed
                                            editing = false
                                            status = if (proposed.isEmpty()) "Private note cleared." else "Private note saved."
                                        }
                                        TransferNotesStore.SaveStatus.CONFLICT -> {
                                            // Preserve the user's draft, but never rebase it onto a
                                            // newer value without a deliberate reload and edit.
                                            unavailable = true
                                            status = "The saved note changed. Reload it before editing again."
                                        }
                                        TransferNotesStore.SaveStatus.UNVERIFIED -> {
                                            unavailable = true
                                            status = "Save could not be verified. Reload the saved note."
                                        }
                                    }
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: RuntimeException) {
                                    unavailable = true
                                    observed = null
                                    status = "Save outcome uncertain. Reload the saved note."
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text(if (saving) "Saving…" else if (draft.isEmpty()) "Save empty note (clear)" else "Save private note") }
                    OutlinedButton(onClick = { draft = current; editing = false; status = null },
                        enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel note edit") }
                }
                else -> {
                    if (current.isEmpty()) Text("No private note")
                    else {
                        Text(current, maxLines = if (showFull) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis)
                        OutlinedButton(onClick = { showFull = !showFull },
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                stateDescription = if (showFull) "Expanded" else "Collapsed"
                            }) { Text(if (showFull) "Show less note" else "Show full note") }
                    }
                    OutlinedButton(onClick = { draft = current; editing = true; status = null },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit private note") }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        }
    }
}
