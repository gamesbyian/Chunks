package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.TransferDisplayNames

/**
 * Changes only Chunks' transfer-list alias. No RPC, data/file rename, or metadata edits.
 * Save is explicit; tapping an automatic suggestion just fills the editable draft.
 */
@Composable
internal fun TransferAliasEditor(
    originalName: String,
    hash: String,
    alias: String?,
    onSave: (String?) -> Boolean
) {
    var editing by remember(hash) { mutableStateOf(false) }
    var draft by remember(hash, alias) { mutableStateOf(alias.orEmpty()) }
    var problem by remember(hash) { mutableStateOf<String?>(null) }
    val suggestions = remember(originalName) { TransferDisplayNames.suggestions(originalName) }

    if (!editing) {
        OutlinedButton(onClick = {
            problem = null
            draft = alias.orEmpty()
            editing = true
        }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (alias == null) "Change list name" else "Edit list name")
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Only the name shown in Chunks changes. The torrent, files and seeding remain untouched.")
        Text("Original: " + originalName)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it; problem = null },
            label = { Text("Display name in Chunks") },
            placeholder = { Text(originalName) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            isError = problem != null
        )
        if (suggestions.isNotEmpty()) {
            Text("Clean-name suggestions · from this filename only")
            for (suggestion in suggestions) {
                OutlinedButton(
                    onClick = { draft = suggestion.name; problem = null },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(suggestion.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("Suggestions can be edited before saving. They don't identify guests or episode titles absent from the filename.")
        }
        if (problem != null) Text(problem.orEmpty())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    try {
                        val proposed = TransferDisplayNames.validateAlias(draft)
                        if (onSave(proposed.takeUnless { it == originalName })) {
                            editing = false
                            problem = null
                        } else problem = "Could not save the local display name."
                    } catch (failure: IllegalArgumentException) {
                        problem = failure.message ?: "Invalid display name."
                    }
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) { Text("Save name") }
            OutlinedButton(
                onClick = { editing = false; problem = null },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) { Text("Cancel") }
        }
        if (alias != null) {
            OutlinedButton(onClick = {
                if (onSave(null)) {
                    editing = false
                    problem = null
                } else problem = "Could not reset the local display name."
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Use original torrent name")
            }
        }
    }
}
