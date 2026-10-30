package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.RemoteFile
import io.github.gamesbyian.chunks.rpc.RTorrentRemote

/** Bounded display slice; the file index remains the original server index. */
internal data class FilePage(
    val number: Int,
    val count: Int,
    val firstIndex: Int,
    val endExclusive: Int
)

internal object FilePages {
    const val SIZE = 40
    fun select(totalFiles: Int, requestedPage: Int): FilePage {
        require(totalFiles in 0..12000) { "Unexpected file inventory size" }
        val count = ((totalFiles + SIZE - 1) / SIZE).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, count - 1)
        val first = page * SIZE
        return FilePage(page, count, first, minOf(first + SIZE, totalFiles))
    }
}

/**
 * All reported torrent files can be inspected with bounded native pages.
 * File identities, sizes and priority labels are server observations, not
 * filesystem validation or a claimed local file operation.
 */
@Composable
internal fun TransferFilePager(
    remote: RTorrentRemote,
    hash: String,
    files: List<RemoteFile>,
    snapshotStale: Boolean,
    mainView: Boolean,
    onMutationStarted: () -> Boolean,
    onSettled: () -> Unit
) {
    var requestedPage by rememberSaveable(hash, files) { mutableStateOf(0) }
    var selectedIndex by rememberSaveable(hash, files) { mutableStateOf(-1) }
    val current = FilePages.select(files.size, requestedPage)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${files.size} server-reported files", fontWeight = FontWeight.Bold)
        if (files.isEmpty()) {
            Text("No files were reported for this transfer.",
                style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Showing ${current.firstIndex + 1}–${current.endExclusive} of ${files.size} files",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { requestedPage = current.number - 1 },
                    enabled = current.number > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("Previous 40") }
                OutlinedButton(
                    onClick = { requestedPage = current.number + 1 },
                    enabled = current.number < current.count - 1,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("Next 40") }
            }
            Text("Page ${current.number + 1} of ${current.count}",
                style = MaterialTheme.typography.labelMedium)
            if (!mainView || snapshotStale) {
                Text("Refresh the main server view before changing file priorities.",
                    style = MaterialTheme.typography.bodySmall)
            }
            for (i in current.firstIndex until current.endExclusive) {
                val file = files[i]
                val priority = when (file.priority) {
                    0 -> "Skipped"
                    1 -> "Normal"
                    2 -> "High"
                    else -> "Priority unknown"
                }
                RemoteFilePageRow(
                    index = i, file = file, priorityLabel = priority,
                    expanded = selectedIndex == i,
                    actionEnabled = mainView && !snapshotStale && file.priority in 0..2,
                    onToggle = { selectedIndex = if (selectedIndex == i) -1 else i }
                )
                if (selectedIndex == i) {
                    TransferFilePriorityControl(
                        remote = remote,
                        hash = hash,
                        index = i,
                        file = file,
                        snapshotStale = snapshotStale,
                        mainView = mainView,
                        onMutationStarted = onMutationStarted,
                        onSettled = onSettled
                    )
                }
            }
        }
    }
}
