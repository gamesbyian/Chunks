package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import io.github.gamesbyian.chunks.model.TransferDisplayNames
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * The visible Add target is a draft edit, never an execution permission. Only
 * the independently authenticated current account may be offered as a ready
 * remote target. Saved profiles require authentication before remote Add.
 */
internal data class UniversalAddSavedDestination(val id: String, val label: String)

internal fun eligibleUniversalAddRemote(
    authenticatedAccountScope: String?,
    candidateAccountScope: String?
): Boolean = !authenticatedAccountScope.isNullOrBlank() &&
    authenticatedAccountScope == candidateAccountScope

@Composable
internal fun UniversalAddDestinationPicker(
    addingRemotely: Boolean,
    authenticatedRemoteLabel: String?,
    savedOtherProfiles: List<UniversalAddSavedDestination>,
    enabled: Boolean,
    aliasDraft: String = "",
    onAliasChange: (String) -> Unit = {},
    /** The current source's own name, for filename-derived display-name suggestions. */
    sourceName: String? = null,
    incomingSourceWaiting: Boolean = false,
    importingSource: Boolean = false,
    sourceError: String? = null,
    onAcceptIncoming: () -> Unit = {},
    onKeepDraft: () -> Unit = {},
    onPhone: () -> Unit,
    onAuthenticatedRemote: () -> Unit,
    onConnectSaved: (String) -> Unit
) {
    var showSaved by rememberSaveable { mutableStateOf(false) }
    var showAlias by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (incomingSourceWaiting) {
            Text("Another torrent source was shared. Keep the current draft or explicitly replace it; neither choice adds a transfer.",
                style = MaterialTheme.typography.bodyMedium)
            FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onAcceptIncoming, enabled = enabled && !importingSource,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Use new source") }
                OutlinedButton(onClick = onKeepDraft, enabled = enabled && !importingSource,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep current draft") }
            }
        }
        if (importingSource) Text("Reading .torrent document; no transfer has been submitted.",
            style = MaterialTheme.typography.bodySmall)
        sourceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Add to", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        FlowRow(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = !addingRemotely, enabled = enabled,
                onClick = onPhone, label = { Text("This device") },
                modifier = Modifier.heightIn(min = 48.dp).testTag(ChunksSemantics.ADD_TARGET_DEVICE))
            authenticatedRemoteLabel?.let { label ->
                FilterChip(selected = addingRemotely, enabled = enabled,
                    onClick = onAuthenticatedRemote,
                    label = { Text(label) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(ChunksSemantics.ADD_TARGET_REMOTE))
            }
        }
        Text("Destination selection only changes this draft. Add transfer is the sole submission action.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { showAlias = !showAlias }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                stateDescription = if (showAlias) "Expanded" else "Collapsed"
            }) {
            Text(if (showAlias) "Hide display name" else
                if (aliasDraft.isBlank()) "Optional display name" else "Display name selected")
        }
        if (showAlias) {
            OutlinedTextField(
                value = aliasDraft,
                onValueChange = { onAliasChange(it.take(TransferDisplayNames.MAX_ALIAS_LENGTH + 1)) },
                label = { Text("Display name in Chunks") },
                placeholder = { Text("Optional; leave blank for original name") },
                singleLine = false,
                maxLines = 3,
                enabled = enabled,
                isError = !validUniversalAddAlias(aliasDraft),
                modifier = Modifier.fillMaxWidth()
            )
            val nameSuggestions = androidx.compose.runtime.remember(sourceName) {
                sourceName?.let { TransferDisplayNames.suggestions(it) }.orEmpty()
            }
            if (nameSuggestions.isNotEmpty()) FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                nameSuggestions.forEach { suggestion ->
                    androidx.compose.material3.FilterChip(selected = aliasDraft == suggestion.name,
                        onClick = { onAliasChange(suggestion.name) }, enabled = enabled,
                        label = { Text(suggestion.name) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = "Use display-name suggestion: " + suggestion.name
                        })
                }
            }
            Text("Only the Chunks list label changes after a verified new Add. " +
                "The torrent name, source, files and server remain untouched.",
                style = MaterialTheme.typography.bodySmall)
            if (!validUniversalAddAlias(aliasDraft)) Text(
                "Use a visible name of at most 160 characters without control characters.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        if (savedOtherProfiles.isNotEmpty()) {
            OutlinedButton(onClick = { showSaved = !showSaved },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    stateDescription = if (showSaved) "Expanded" else "Collapsed"
                }) {
                Text(if (showSaved) "Hide other saved connections"
                    else "Other saved connections (" + savedOtherProfiles.size + ")")
            }
            if (showSaved) {
                Text("A saved profile is not an authenticated Add destination. Connect first; the current source draft is kept.",
                    style = MaterialTheme.typography.bodySmall)
                savedOtherProfiles.forEach { profile ->
                    OutlinedButton(
                        onClick = { onConnectSaved(profile.id) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = enabled) {
                        Text("Connect to " + profile.label)
                    }
                }
            }
        }
    }
}
