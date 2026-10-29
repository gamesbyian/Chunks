package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.Units
import io.github.gamesbyian.chunks.rpc.RemoteFile

/**
 * Presentation of one exact server file and its priority control.
 * On a narrow or large-text screen the action occupies its own row so the
 * full file path, size and priority remain legible. This component never
 * initiates a remote mutation; the owning pager keeps all capability gates.
 */
@Composable
internal fun RemoteFilePageRow(
    index: Int,
    file: RemoteFile,
    priorityLabel: String,
    expanded: Boolean,
    actionEnabled: Boolean,
    onToggle: () -> Unit
) {
    val fileFacts = "${index + 1}. ${file.path} · ${Units.bytes(file.sizeBytes)} · $priorityLabel"
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 380.dp || fontScale >= 1.5f
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(fileFacts, style = MaterialTheme.typography.bodySmall)
                RemoteFilePriorityToggle(index, file, expanded, actionEnabled, onToggle,
                    modifier = Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fileFacts, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                RemoteFilePriorityToggle(index, file, expanded, actionEnabled, onToggle)
            }
        }
    }
}

@Composable
private fun RemoteFilePriorityToggle(
    index: Int,
    file: RemoteFile,
    expanded: Boolean,
    actionEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val actionName = if (expanded) "Close priority options" else "Change priority"
    OutlinedButton(onClick = onToggle, enabled = actionEnabled,
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "$actionName for file ${index + 1}: ${file.path}"
        }) {
        Text(if (expanded) "Close" else "Priority")
    }
}
