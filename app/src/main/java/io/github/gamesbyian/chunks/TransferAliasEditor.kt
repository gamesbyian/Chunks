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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.TransferDisplayNames

internal class TransferAliasEditSession(initialAlias: String?) {
    var editing by mutableStateOf(false)
    var draft by mutableStateOf(initialAlias.orEmpty())
    var problem by mutableStateOf<String?>(null)
}

private val TransferAliasEditSessionSaver = Saver<TransferAliasEditSession, List<String?>>(
    save = { listOf(if (it.editing) "1" else "0", it.draft, it.problem) },
    restore = { fields ->
        TransferAliasEditSession(fields.getOrNull(1)).apply {
            editing = fields.getOrNull(0) == "1"
            problem = fields.getOrNull(2)
        }
    }
)

/**
 * Changes only Chunks' transfer-list alias. No RPC, data/file rename, or metadata edits.
 * Save is explicit; tapping an automatic suggestion just fills the editable draft.
 */
@Composable
internal fun TransferAliasEditor(
    originalName: String,
    hash: String,
    alias: String?,
    onSave: (String?) -> Boolean,
    session: TransferAliasEditSession? = null
) {
    // The installed Transfers screen owns an editor session ABOVE its LazyColumn.
    // A keyed LazyColumn alone proved insufficient on API 35: the selected
    // suggestion vanished before Save in an actual MainActivity screenshot.
    // Standalone editors still use saveable state for recycling/process restore.
    val editor = session ?: rememberSaveable(hash, alias, saver = TransferAliasEditSessionSaver) {
        TransferAliasEditSession(alias)
    }
    val suggestions = remember(originalName) { TransferDisplayNames.suggestions(originalName) }

    if (!editor.editing) {
        OutlinedButton(onClick = {
            editor.problem = null
            editor.draft = alias.orEmpty()
            editor.editing = true
        }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (alias == null) "Change list name" else "Edit list name")
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Only the name shown in Chunks changes. The torrent, files and seeding remain untouched.")
        Text("Original: " + originalName)
        OutlinedTextField(
            value = editor.draft,
            onValueChange = { editor.draft = it; editor.problem = null },
            label = { Text("Display name in Chunks") },
            placeholder = { Text(originalName) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            isError = editor.problem != null
        )
        if (suggestions.isNotEmpty()) {
            Text("Clean-name suggestions · from this filename only")
            for (suggestion in suggestions) {
                OutlinedButton(
                    onClick = { editor.draft = suggestion.name; editor.problem = null },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(suggestion.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    suggestion.explanation,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
            }
            Text("Suggestions can be edited before saving. They don't identify guests or episode titles absent from the filename.")
        }
        if (editor.problem != null) Text(editor.problem.orEmpty())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    try {
                        val proposed = TransferDisplayNames.validateAlias(editor.draft)
                        if (onSave(proposed.takeUnless { it == originalName })) {
                            editor.editing = false
                            editor.problem = null
                        } else editor.problem = "Could not save the local display name."
                    } catch (failure: IllegalArgumentException) {
                        editor.problem = failure.message ?: "Invalid display name."
                    }
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) { Text("Save name") }
            OutlinedButton(
                onClick = { editor.editing = false; editor.problem = null },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) { Text("Cancel") }
        }
        if (alias != null) {
            OutlinedButton(onClick = {
                if (onSave(null)) {
                    editor.editing = false
                    editor.problem = null
                } else editor.problem = "Could not reset the local display name."
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Use original torrent name")
            }
        }
    }
}
