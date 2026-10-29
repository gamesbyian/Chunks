package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalFileDiskCheck
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-demand, read-only check of a phone transfer's files against disk. The
 * result is a snapshot of when it ran; it offers no repair or deletion.
 */
@Composable
internal fun LocalFilesOnDiskCheck(
    transferKey: String,
    savePath: String,
    files: List<LocalTransferFile>,
    check: (File, List<LocalTransferFile>) -> LocalFileDiskCheck.Report = LocalFileDiskCheck::check
) {
    // A result describes one folder and one file mapping; progress refreshes keep it,
    // but a different save folder or file layout clears it rather than showing a stale check.
    val layout = remember(files) { files.map { Triple(it.index, it.path, it.sizeBytes) } }
    var report by remember(transferKey, savePath, layout) { mutableStateOf<LocalFileDiskCheck.Report?>(null) }
    var running by remember(transferKey, savePath, layout) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val folder = File(savePath)
    Column {
        if (!folder.isAbsolute) {
            Text("Files on disk can't be checked here: this transfer's folder isn't a direct file path.",
                style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        OutlinedButton(
            onClick = {
                running = true
                val snapshot = files
                scope.launch {
                    report = try {
                        withContext(Dispatchers.IO) { check(folder, snapshot) }
                    } finally {
                        running = false
                    }
                }
            },
            enabled = !running && files.isNotEmpty(),
            modifier = Modifier.heightIn(min = 48.dp)
        ) { Text(if (running) "Checking files on disk…" else "Check files on disk (read-only)") }
        report?.let { result ->
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(result.summary(), style = MaterialTheme.typography.bodySmall)
                result.problems.take(MAX_LISTED).forEach {
                    Text("• ${it.file.path}: ${it.status.label}", style = MaterialTheme.typography.bodySmall)
                }
                if (result.problems.size > MAX_LISTED) {
                    Text("…and ${result.problems.size - MAX_LISTED} more.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private const val MAX_LISTED = 20
