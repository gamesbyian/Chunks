package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    session: TransferAliasEditSession? = null,
    /** Confidently identified media (title, year) offered ahead of filename-only suggestions. */
    identifiedSuggestions: List<io.github.gamesbyian.chunks.model.DisplayNameSuggestion> = emptyList()
) {
    // The installed Transfers screen owns an editor session ABOVE its LazyColumn.
    // A keyed LazyColumn alone proved insufficient on API 35: the selected
    // suggestion vanished before Save in an actual MainActivity screenshot.
    // Standalone editors still use saveable state for recycling/process restore.
    val editor = session ?: rememberSaveable(hash, alias, saver = TransferAliasEditSessionSaver) {
        TransferAliasEditSession(alias)
    }
    val suggestions = remember(originalName, identifiedSuggestions) {
        (identifiedSuggestions + TransferDisplayNames.suggestions(originalName))
            .distinctBy { it.name }.filter { it.name != originalName }.take(3)
    }
    var showSuggestions by rememberSaveable(hash, alias) { mutableStateOf(false) }

    if (!editor.editing) {
        OutlinedButton(onClick = {
            editor.problem = null
            editor.draft = alias.orEmpty()
            editor.editing = true
            showSuggestions = false
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
            OutlinedButton(
                onClick = { showSuggestions = !showSuggestions },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics {
                        stateDescription = if (showSuggestions) "Expanded" else "Collapsed"
                    }
            ) {
                Text(if (showSuggestions) "Hide name suggestions" else "Show name suggestions (${suggestions.size})")
            }
            if (showSuggestions) {
                Text(if (identifiedSuggestions.isEmpty()) "Clean-name suggestions · from this filename only"
                    else "Name suggestions · identified media first, then this filename")
                for (suggestion in suggestions) {
                OutlinedButton(
                    onClick = { editor.draft = suggestion.name; editor.problem = null },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "Use list-name suggestion: " + suggestion.name
                        }
                ) {
                    // These candidates are explicitly revealed by the user.
                    // Never conceal the significant suffix of a long episode
                    // or movie title after two lines, especially at large text.
                    Text(suggestion.name, maxLines = Int.MAX_VALUE)
                }
                Text(
                    suggestion.explanation,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
            }
                Text("Suggestions can be edited before saving. They don't identify guests or episode titles absent from the filename.")
            }
        }
        if (editor.problem != null) Text(editor.problem.orEmpty())
        val saveName: () -> Unit = {
            try {
                val proposed = TransferDisplayNames.validateAlias(editor.draft)
                if (onSave(proposed.takeUnless { it == originalName })) {
                    editor.editing = false
                    editor.problem = null
                } else editor.problem = "Could not save the local display name."
            } catch (failure: IllegalArgumentException) {
                editor.problem = failure.message ?: "Invalid display name."
            }
        }
        val cancelName: () -> Unit = {
            editor.editing = false
            editor.problem = null
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 380.dp || LocalDensity.current.fontScale >= 1.5f
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = saveName,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Save name")
                    }
                    OutlinedButton(onClick = cancelName,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Cancel")
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = saveName,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("Save name")
                    }
                    OutlinedButton(onClick = cancelName,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("Cancel")
                    }
                }
            }
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
