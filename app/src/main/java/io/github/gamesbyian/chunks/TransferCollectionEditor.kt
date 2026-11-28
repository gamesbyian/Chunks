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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.CollectionMembershipRules
import io.github.gamesbyian.chunks.model.CollectionChoicePlanner
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An explicitly editable membership for a single scoped torrent.
 * No operation in this card calls rTorrent, edits paths or changes sharing.
 */
@Composable
internal fun TransferCollectionEditor(
    transfer: RemoteTransfer,
    scope: String,
    store: TransferCollectionStore,
    availableCollections: List<String> = emptyList(),
    onMembershipChanged: () -> Unit
) {
    TransferCollectionEditor(transfer.hash, transfer.name, scope, store,
        availableCollections, onMembershipChanged = onMembershipChanged)
}

/**
 * Shared remote/local Collection editor. Local transfers use a distinct
 * app-owned identity namespace, never the rTorrent endpoint scope.
 */
@Composable
internal fun TransferCollectionEditor(
    infoHash: String,
    displayName: String,
    scope: String,
    store: TransferCollectionStore,
    availableCollections: List<String> = emptyList(),
    /** Card title; screens that already name the transfer (canonical Details) pass "Collections". */
    title: String = displayName,
    onMembershipChanged: () -> Unit
) {
    // Both editor state and its coroutine lifetime belong to this exact transfer.
    // Changing profiles with the same hash must not display the previous result.
    key(scope, infoHash) {
        ScopedCollectionEditor(infoHash, scope, store, availableCollections, title, onMembershipChanged)
    }
}

@Composable
private fun ScopedCollectionEditor(
    infoHash: String,
    scope: String,
    store: TransferCollectionStore,
    availableCollections: List<String>,
    title: String,
    onMembershipChanged: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var showExisting by rememberSaveable { mutableStateOf(false) }
    var memberships by remember { mutableStateOf<List<String>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var readFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val coroutines = rememberCoroutineScope()

    LaunchedEffect(reload, store) {
        readFailed = false
        memberships = null
        try {
            memberships = withContext(Dispatchers.IO) { store.forTransfer(scope, infoHash) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: RuntimeException) {
            readFailed = true
        }
    }

    fun changeMembership(name: String, adding: Boolean, clearDraft: Boolean = false) {
        if (saving || memberships == null) return
        val cleaned = CollectionMembershipRules.cleanName(name) ?: return
        saving = true
        message = "Saving collection membership…"
        coroutines.launch {
            try {
                val (committed, observed) = withContext(Dispatchers.IO) {
                    // A failed commit does not establish that nothing changed.
                    val saved = try {
                        if (adding) store.add(scope, infoHash, cleaned)
                        else store.remove(scope, infoHash, cleaned)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: RuntimeException) {
                        false
                    }
                    saved to store.forTransfer(scope, infoHash)
                }
                memberships = observed
                val present = observed.any { it.equals(cleaned, ignoreCase = true) }
                if (committed && present == adding) {
                    if (clearDraft) draft = ""
                    message = if (adding) "Membership saved on this device. Sharing is unchanged."
                        else "Collection membership removed. Torrent files are unchanged."
                    onMembershipChanged()
                } else {
                    message = "Could not verify the save. Current memberships are shown; check them before trying again."
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: RuntimeException) {
                memberships = null
                readFailed = true
                message = "Save outcome uncertain. Reload memberships before trying again."
            } finally {
                saving = false
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge)
            val current = memberships
            Text(
                when {
                    readFailed -> "Collection memberships unavailable"
                    current == null -> "Loading collection memberships…"
                    current.isEmpty() -> "No manual collections"
                    else -> current.joinToString(" · ")
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            if (readFailed) {
                OutlinedButton(onClick = { reload++ },
                    enabled = !saving,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Reload memberships")
                }
            }
            OutlinedButton(
                onClick = { expanded = !expanded },
                enabled = !saving,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(if (expanded) "Close collection editor" else "Edit collections")
            }
            if (expanded) {
                Text(
                    "A transfer can belong to several collections. These names stay on this device; no folders or torrent data change.",
                    style = MaterialTheme.typography.bodySmall
                )
                val choices = if (current == null) emptyList()
                    else CollectionChoicePlanner.unassigned(availableCollections, current)
                val canAdd = !saving && current != null &&
                    current.size < CollectionMembershipRules.MAX_PER_TRANSFER
                if (choices.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { showExisting = !showExisting },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .semantics {
                                stateDescription = if (showExisting) "Expanded" else "Collapsed"
                            }
                    ) {
                        Text(if (showExisting) "Hide existing collections"
                            else "Choose existing collection (${choices.size})")
                    }
                    if (showExisting) {
                        Text("Add an existing collection", style = MaterialTheme.typography.bodyMedium)
                        for (name in choices) {
                            OutlinedButton(
                                onClick = { changeMembership(name, adding = true) },
                                enabled = canAdd,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) { Text("Add to $name") }
                        }
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it; message = null },
                    label = { Text("Collection name") },
                    enabled = !saving,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("collection-name-input")
                )
                OutlinedButton(
                    enabled = canAdd && CollectionMembershipRules.cleanName(draft) != null,
                    onClick = { changeMembership(draft, adding = true, clearDraft = true) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Add to collection") }
                for (name in current.orEmpty()) {
                    OutlinedButton(
                        onClick = { changeMembership(name, adding = false) },
                        enabled = !saving,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Remove from $name") }
                }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}
