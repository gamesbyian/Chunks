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
import androidx.compose.ui.text.style.TextOverflow

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

/**
 * Compact labels may be duplicated because names are editable aliases, not keys.
 * Suffix only collisions in the visible Add selector; callbacks still receive
 * immutable saved profile IDs, and the active remote uses its authenticated scope.
 */
internal fun compactUniversalAddTargetLabels(
    authenticatedName: String?,
    saved: List<UniversalAddSavedDestination>
): Pair<String?, List<UniversalAddSavedDestination>> {
    val raw = listOfNotNull(authenticatedName) + saved.map { it.label }
    val normalized = raw.map { it.trim().ifBlank { "rTorrent" } }
    val totals = normalized.groupingBy { it.lowercase(java.util.Locale.ROOT) }.eachCount()
    val seen = mutableMapOf<String, Int>()
    val distinct = normalized.map { label ->
        val key = label.lowercase(java.util.Locale.ROOT)
        val ordinal = (seen[key] ?: 0) + 1
        seen[key] = ordinal
        if (totals.getValue(key) > 1) "$label ($ordinal)" else label
    }
    val active = if (authenticatedName == null) null else distinct.first()
    val offset = if (authenticatedName == null) 0 else 1
    return active to saved.mapIndexed { index, profile ->
        profile.copy(label = distinct[index + offset])
    }
}

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
    canReplaceIncoming: Boolean = true,
    importingSource: Boolean = false,
    sourceError: String? = null,
    onAcceptIncoming: () -> Unit = {},
    onKeepDraft: () -> Unit = {},
    onLocal: () -> Unit,
    onAuthenticatedRemote: () -> Unit,
    onConnectSaved: (String) -> Unit,
    /** Connected Transmission / qBittorrent / Deluge clients (exact typed profiles). */
    protocolDestinations: List<UniversalAddSavedDestination> = emptyList(),
    selectedProtocolId: String? = null,
    localSelected: Boolean = !addingRemotely && selectedProtocolId == null,
    /** From the selected client's UniversalAddClientCapabilities; false explains instead of offering a name. */
    displayNameSupported: Boolean = selectedProtocolId == null,
    onProtocol: (String) -> Unit = {},
    /** Smart Routing's proposal (label + reason); never applied without a tap. */
    routingSuggestion: Pair<String, String>? = null,
    routingSuggestionSelected: Boolean = false,
    onUseRoutingSuggestion: () -> Unit = {}
) {
    var showAlias by rememberSaveable { mutableStateOf(false) }
    val (activeRemoteName, otherDestinations) = compactUniversalAddTargetLabels(
        authenticatedRemoteLabel, savedOtherProfiles)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (incomingSourceWaiting) {
            Text("Another torrent source was shared. Keep the current draft or explicitly replace it; neither choice adds a transfer.",
                style = MaterialTheme.typography.bodyMedium)
            FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onAcceptIncoming, enabled = enabled && canReplaceIncoming && !importingSource,
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
        routingSuggestion?.let { (label, reason) ->
            Text("Smart Routing suggests $label · $reason", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("chunks.add.routing.suggestion"))
            if (!routingSuggestionSelected) OutlinedButton(onClick = onUseRoutingSuggestion, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("chunks.add.routing.use")) { Text("Use $label") }
        }
        FlowRow(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = localSelected, enabled = enabled,
                onClick = onLocal, label = { Text("Local") },
                modifier = Modifier.heightIn(min = 48.dp).testTag(ChunksSemantics.ADD_TARGET_DEVICE))
            activeRemoteName?.let { name ->
                FilterChip(selected = addingRemotely && selectedProtocolId == null, enabled = enabled,
                    onClick = onAuthenticatedRemote,
                    label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(ChunksSemantics.ADD_TARGET_REMOTE))
            }
            protocolDestinations.forEach { client ->
                FilterChip(selected = selectedProtocolId == client.id, enabled = enabled,
                    onClick = { onProtocol(client.id) },
                    label = { Text(client.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("chunks.add.target.protocol." + client.id))
            }
            otherDestinations.forEach { profile ->
                // A saved profile requires authentication first. This gesture only
                // navigates to that exact account's connection form with the draft kept.
                FilterChip(selected = false, enabled = enabled,
                    onClick = { onConnectSaved(profile.id) },
                    label = { Text(profile.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = "Connect to " + profile.label + " before adding"
                    })
            }
        }
        if (otherDestinations.isNotEmpty()) Text(
            "Saved connections need authentication before Add; selecting one keeps this draft.",
            style = MaterialTheme.typography.bodySmall)

        // Chunks display names exist for Local and rTorrent transfers only. A name
        // typed for a Transmission/qBittorrent/Deluge Add would be silently dropped,
        // so the capability is explained instead of offered.
        if (!displayNameSupported) {
            Text("Display names are not available for this client; Chunks shows the torrent's own name.",
                style = MaterialTheme.typography.bodySmall)
            return@Column
        }
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
                sourceName?.let { name -> TransferDisplayNames.suggestions(name)
                    .filter { it.name != name && validUniversalAddAlias(it.name) } }.orEmpty()
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

    }
}
