package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    /**
     * Keep expanded details attached to the exact reported file, not merely
     * an index that might refer to another path after a server refresh.
     * Priority changes are observations, so they must not close the panel.
     */
    fun selectedIndex(files: List<RemoteFile>, index: Int, path: String?): Int =
        index.takeIf { it in files.indices && files[it].path == path } ?: -1

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
    // Server readings change as progress and priorities change. A refresh is
    // not a new torrent, so it must not jump back to page one or close details.
    // Changing the authenticated remote instance does reset the UI scope.
    var requestedPage by rememberSaveable(remote, hash) { mutableStateOf(0) }
    var fileSearch by rememberSaveable(remote, hash) { mutableStateOf("") }
    var selectedIndex by rememberSaveable(remote, hash) { mutableStateOf(-1) }
    var selectedPath by rememberSaveable(remote, hash) { mutableStateOf<String?>(null) }
    val activeSelectedIndex = FilePages.selectedIndex(files, selectedIndex, selectedPath)
    val matchedIndices = matchingFileIndices(files.map { it.path }, fileSearch)
    val current = FilePages.select(matchedIndices.size, requestedPage)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${files.size} server-reported files", fontWeight = FontWeight.Bold)
        if (files.isNotEmpty()) {
            ClearableTransferSearchField(
                value = fileSearch,
                onValueChange = { fileSearch = it; requestedPage = 0 },
                label = "Search this transfer's files"
            )
            Text("Showing ${matchedIndices.size} of ${files.size} file paths" +
                if (fileSearch.isNotBlank()) " · filtered" else "",
                style = MaterialTheme.typography.bodySmall)
        }
        if (files.isEmpty()) {
            Text("No files were reported for this transfer.",
                style = MaterialTheme.typography.bodySmall)
        } else {
            RemoteFilePagerControls(
                page = current,
                totalFiles = matchedIndices.size,
                onPrevious = { requestedPage = current.number - 1 },
                onNext = { requestedPage = current.number + 1 }
            )
            if (!mainView || snapshotStale) {
                Text("Refresh the main server view before changing file priorities.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (matchedIndices.isEmpty()) Text(
                "No files match this search. Clear it to see the full list.",
                style = MaterialTheme.typography.bodySmall)
            for (pageIndex in current.firstIndex until current.endExclusive) {
                // 'i' remains the original server file index for all read-only
                // details and guarded priority actions, even after searching.
                val i = matchedIndices[pageIndex]
                val file = files[i]
                val priority = when (file.priority) {
                    0 -> "Skipped"
                    1 -> "Normal"
                    2 -> "High"
                    else -> "Priority unknown"
                }
                RemoteFilePageRow(
                    index = i, file = file, priorityLabel = priority,
                    expanded = activeSelectedIndex == i,
                    actionEnabled = mainView && !snapshotStale && file.priority in 0..2,
                    onToggle = {
                        if (activeSelectedIndex == i) {
                            selectedIndex = -1
                            selectedPath = null
                        } else {
                            selectedIndex = i
                            selectedPath = file.path
                        }
                    }
                )
                if (activeSelectedIndex == i) {
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
