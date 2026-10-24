package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.Units
import io.github.gamesbyian.chunks.rpc.RemoteFile

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
internal fun TransferFilePager(hash: String, files: List<RemoteFile>) {
    var requestedPage by remember(hash, files) { mutableIntStateOf(0) }
    val current = FilePages.select(files.size, requestedPage)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${files.size} files · read only", fontWeight = FontWeight.Bold)
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
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Previous 40") }
                OutlinedButton(
                    onClick = { requestedPage = current.number + 1 },
                    enabled = current.number < current.count - 1,
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Next 40") }
            }
            Text("Page ${current.number + 1} of ${current.count}",
                style = MaterialTheme.typography.labelMedium)
            for (i in current.firstIndex until current.endExclusive) {
                val file = files[i]
                val priority = when (file.priority) {
                    0 -> "Skipped"
                    1 -> "Normal"
                    2 -> "High"
                    else -> "Priority unknown"
                }
                Text(
                    "${i + 1}. ${file.path} · ${Units.bytes(file.sizeBytes)} · $priority",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
