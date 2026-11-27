package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.RarVolumeCandidates
import io.github.gamesbyian.chunks.model.Units
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Read-only inventory on demand. Neither this surface nor its model has
 * extraction, download priority, deletion or settings authority. */
@Composable
internal fun LocalArchiveInventoryPanel(infoHash: String, engine: () -> LocalDetailsPort?) {
    var expanded by remember(infoHash) { mutableStateOf(false) }
    var busy by remember(infoHash) { mutableStateOf(false) }
    var review by remember(infoHash) { mutableStateOf<LocalArchiveInventory.Review?>(null) }
    var failed by remember(infoHash) { mutableStateOf(false) }
    var group by remember(infoHash) { mutableStateOf(0) }
    var page by remember(infoHash) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    fun read() {
        if (busy) return
        expanded = true
        busy = true
        failed = false
        review = null
        group = 0
        page = 0
        scope.launch {
            try {
                review = withContext(Dispatchers.IO) { engine()?.archiveReview(infoHash) }
                failed = review == null
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            if (expanded) { expanded = false; review = null; failed = false }
            else read()
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (expanded) "Hide RAR volume review" else "Review RAR archive volumes…")
        }
        if (!expanded) return@Column
        Text("Filename candidates only. Archive headers, verified pieces, final volume and integrity are not confirmed. This review does not extract or change files.",
            style = MaterialTheme.typography.bodySmall)
        if (busy) Text("Reading the transfer inventory…")
        if (failed) Text("A complete current file inventory could not be confirmed.",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        review?.let { shown ->
            val candidates = shown.candidates
            Text("At last review: " + candidates.size + " RAR filename groups" +
                if (shown.torrentProgressPpm < 1_000_000) "; torrent not complete." else "; torrent reports complete.",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (candidates.isEmpty()) Text("No supported RAR volume names found.")
            candidates.getOrNull(group)?.let { candidate ->
                Text("Group " + (group + 1) + " of " + candidates.size,
                    style = MaterialTheme.typography.titleSmall)
                Text(listOf(candidate.folder, candidate.stem).filter { it.isNotEmpty() }.joinToString("/"))
                Text(when (candidate.form) {
                    RarVolumeCandidates.Form.MODERN -> "Modern numbered RAR names"
                    RarVolumeCandidates.Form.LEGACY_OR_SINGLE -> "Single or legacy-numbered RAR names"
                    RarVolumeCandidates.Form.MIXED -> "Mixed RAR naming conventions"
                })
                if (candidate.ambiguous) Text("Ambiguous names or duplicate volume numbers. No set confirmed.",
                    color = MaterialTheme.colorScheme.error)
                if (candidate.missingCount > 0) Text(
                    "Missing volume number " + candidate.firstMissing + " (" +
                        candidate.missingCount + " gaps in listed numbering).",
                    color = MaterialTheme.colorScheme.error)
                Text(candidate.listedCompleteCount.toString() + " of " + candidate.volumes.size +
                    " listed files report complete bytes.")
                if (candidate.allListedComplete && !candidate.ambiguous && candidate.missingCount == 0)
                    Text("All listed volumes report complete bytes; the final volume and archive integrity are unknown.")
                val lastPage = (candidate.volumes.size - 1) / 8
                Text("Volumes " + (page * 8 + 1) + "–" +
                    minOf((page + 1) * 8, candidate.volumes.size) + " of " + candidate.volumes.size)
                candidate.volumes.drop(page * 8).take(8).forEach { volume ->
                    val file = volume.file
                    Text(file.path.substringAfterLast('/') + " · " + Units.bytes(file.doneBytes) +
                        " of " + Units.bytes(file.sizeBytes) + if (!file.wanted) " · Skipped" else "",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (lastPage > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { page-- }, enabled = page > 0,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Previous volumes") }
                    OutlinedButton(onClick = { page++ }, enabled = page < lastPage,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Next volumes") }
                }
                if (candidates.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { group--; page = 0 }, enabled = group > 0,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Previous group") }
                    OutlinedButton(onClick = { group++; page = 0 }, enabled = group < candidates.lastIndex,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Next group") }
                }
            }
        }
        OutlinedButton(onClick = ::read, enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Refresh RAR volume review") }
    }
}
