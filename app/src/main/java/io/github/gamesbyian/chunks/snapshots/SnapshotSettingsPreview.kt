package io.github.gamesbyian.chunks.snapshots

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val FILES_PER_PAGE = 10

@Composable
internal fun SnapshotSettingsPreview(
    entry: SnapshotRestorePreviewV1.Entry,
    target: SnapshotRestoreTarget
) {
    entry.displayAlias?.let {
        Text("Saved Chunks display alias: $it", style = MaterialTheme.typography.bodySmall)
    }
    val files = entry.fileMappings
    if (files.isEmpty()) {
        Text("No verified file priorities in this record.", style = MaterialTheme.typography.bodySmall)
        return
    }
    var expanded by remember(entry) { mutableStateOf(false) }
    var page by remember(entry) { mutableIntStateOf(0) }
    val unsupported = if (target.supportsLowPriority) 0
        else files.count { it.level == SnapshotFilePriorities.Level.LOW }
    if (unsupported > 0) Text(
        "$unsupported Low priorities are unsupported by ${target.title}.",
        style = MaterialTheme.typography.bodySmall
    )
    OutlinedButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.heightIn(min = 48.dp)
    ) { Text(if (expanded) "Hide file priority mapping" else "Review ${files.size} file priorities") }
    if (expanded) {
        Text("Original file layout. Target file paths have not been checked.",
            style = MaterialTheme.typography.bodySmall)
        val offset = page * FILES_PER_PAGE
        files.drop(offset).take(FILES_PER_PAGE).forEach { mapping ->
            val file = mapping.file
            val missing = !target.supportsLowPriority && mapping.level == SnapshotFilePriorities.Level.LOW
            val suffix = if (missing) " (unsupported)" else ""
            Text("#${file.index} ${file.path} (${file.sizeBytes} bytes) · ${mapping.level.label}$suffix",
                style = MaterialTheme.typography.bodySmall)
        }
        if (files.size > FILES_PER_PAGE) Row {
            OutlinedButton(
                onClick = { page-- }, enabled = page > 0,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Previous files") }
            OutlinedButton(
                onClick = { page++ }, enabled = offset + FILES_PER_PAGE < files.size,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Next files") }
        }
    }
}
