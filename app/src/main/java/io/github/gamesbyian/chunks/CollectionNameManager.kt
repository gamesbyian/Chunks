package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.CollectionNameRegistryRules

/**
 * Collection names exist even with no loaded member transfers. Creating or
 * forgetting a name changes only local metadata, never torrent membership or
 * a physical Place. No classification suggestion is ever auto-accepted.
 */
@Composable
internal fun CollectionNameManager(
    scope: String,
    registry: CollectionNameRegistry,
    registeredNames: List<String>,
    onNamesChanged: (List<String>) -> Unit
) {
    var draft by remember(scope) { mutableStateOf("") }
    var note by remember(scope) { mutableStateOf<String?>(null) }
    var managing by remember(scope) { mutableStateOf(false) }
    var pendingForget by remember(scope) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Create a Collection even before it has any transfers. Saved names stay on this phone and are scoped to this engine/account.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it; note = null },
            label = { Text("New collection name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("new-collection-name")
        )
        OutlinedButton(
            onClick = {
                if (registry.create(scope, draft)) {
                    onNamesChanged(registry.names(scope))
                    draft = ""
                    note = "Collection saved. No transfers were assigned or moved."
                } else note = "Collection name could not be saved. Nothing changed."
            },
            enabled = scope.isNotBlank() &&
                CollectionNameRegistryRules.add(registeredNames, draft) != null &&
                draft.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text("Create empty collection") }
        if (note != null) Text(requireNotNull(note), style = MaterialTheme.typography.bodySmall)
        if (registeredNames.isNotEmpty()) {
            OutlinedButton(onClick = {
                managing = !managing
                pendingForget = null
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (managing) "Close name management" else "Manage saved names")
            }
            if (managing) {
                Text("Forgetting a saved name never removes existing transfer memberships or their files.",
                    style = MaterialTheme.typography.bodySmall)
                registeredNames.forEach { name ->
                    if (pendingForget == name) {
                        Text("Forget the reusable name \"$name\"? Transfers already assigned remain unchanged.")
                        OutlinedButton(onClick = {
                            if (registry.forget(scope, name)) {
                                onNamesChanged(registry.names(scope))
                                note = "Saved name forgotten. Existing transfer memberships are unchanged."
                                pendingForget = null
                            } else note = "Could not forget that name."
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Confirm forget $name")
                        }
                        OutlinedButton(onClick = { pendingForget = null },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Cancel forget")
                        }
                    } else {
                        OutlinedButton(onClick = { pendingForget = name },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Forget saved name $name")
                        }
                    }
                }
            }
        }
    }
}
