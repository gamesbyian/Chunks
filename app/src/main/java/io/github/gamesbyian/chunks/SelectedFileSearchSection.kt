package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** User-initiated search of observed file metadata only. Never persists queries or file paths. */
@Composable
internal fun SelectedFileSearchSection(
    targets: List<SelectedFileSearch.Target>,
    viewWitness: Any,
    viewCurrent: () -> Boolean,
    onOpen: (UnifiedTransferKey) -> Unit
) {
    // A selection/session/visibility change disposes the coroutine and all private results.
    key(viewWitness, targets.map { it.key }) {
        ScopedSelectedFileSearch(targets, viewCurrent, onOpen)
    }
}

@Composable
private fun ScopedSelectedFileSearch(
    targets: List<SelectedFileSearch.Target>,
    viewCurrent: () -> Boolean,
    onOpen: (UnifiedTransferKey) -> Unit
) {
    if (targets.isEmpty()) return
    val latestView = rememberUpdatedState(viewCurrent)
    val latestTargets = rememberUpdatedState(targets)
    val coroutine = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<SelectedFileSearch.Result?>(null) }
    var page by remember { mutableIntStateOf(0) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                expanded = !expanded
                if (!expanded) {
                    job?.cancel()
                    result = null
                    status = null
                    query = ""
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (expanded) "Close file search" else "Find files across selected transfers")
            }
            if (!expanded) return@Column
            Text("Search file names and relative paths", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Text("${targets.size} selected transfers. Reads torrent metadata only; matching a file does not prove its bytes exist or can be opened.",
                style = MaterialTheme.typography.bodySmall)
            if (targets.size > SelectedFileSearch.MAX_TRANSFERS) {
                Text("Select at most ${SelectedFileSearch.MAX_TRANSFERS} transfers for one search.")
                return@Column
            }
            OutlinedTextField(value = query, onValueChange = {
                if (it.length <= SelectedFileSearch.MAX_QUERY_LENGTH && it.none(Char::isISOControl)) {
                    query = it
                    result = null
                    status = null
                }
            }, enabled = !running, label = { Text("File name or path") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("selected-file-search-query"))
            OutlinedButton(enabled = !running && query.isNotBlank(),
                onClick = {
                    if (running) return@OutlinedButton
                    val chosen = latestTargets.value.toList()
                    val submitted = query
                    result = null
                    page = 0
                    status = "Reading selected transfers…"
                    running = true
                    job = coroutine.launch {
                        try {
                            val outcome = SelectedFileSearch.scan(submitted, chosen,
                                viewCurrent = { latestView.value() },
                                progress = { done, total -> status = "Read $done of $total selected transfers." })
                            when (outcome) {
                                is SelectedFileSearch.Outcome.Observed -> {
                                    if (latestView.value()) {
                                        result = outcome.result
                                        status = null
                                    }
                                }
                                SelectedFileSearch.Outcome.Revoked ->
                                    status = "Search stopped because the selected client or view changed."
                                SelectedFileSearch.Outcome.InvalidRequest -> status = "Check the search and selection."
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } finally {
                            running = false
                        }
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (running) "Searching…" else "Search selected files")
            }
            if (running) OutlinedButton(onClick = {
                job?.cancel()
                result = null
                status = "Search cancelled."
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel file search") }
            status?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }

            // Keep even completed results scoped to the current admitted owner, not merely the
            // previously successful transport response. No navigation on a revoked key.
            val current = result?.takeIf { latestView.value() }
            if (current != null) {
                Text("${current.matches.size} matches in observed file metadata.",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (current.hasCoverageGap) Text("Some selected transfers were not fully searched.",
                    color = MaterialTheme.colorScheme.error)
                Text("Coverage is limited to reported metadata; completeness is not independently verified.",
                    style = MaterialTheme.typography.bodySmall)
                val labels = targets.associateBy { it.key }
                current.sources.filter { it.coverage != SelectedFileSearch.Coverage.OBSERVED }.forEach { source ->
                    val label = labels[source.key] ?: return@forEach
                    Text("${label.clientLabel}: ${label.transferName}: ${coverageLabel(source.coverage)}",
                        style = MaterialTheme.typography.bodySmall)
                }
                current.page(page).forEach { match ->
                    FileSearchMatchRow(match) {
                        if (latestView.value() && latestTargets.value.any {
                                it.key == match.key && it.authorized()
                            }) onOpen(match.key)
                    }
                }
                if (current.pageCount > 1) {
                    Text("Page ${page + 1} of ${current.pageCount}")
                    ResponsiveActionGroup(maxColumns = 2) { cell ->
                        OutlinedButton(onClick = { page-- }, enabled = page > 0, modifier = cell) {
                            Text("Previous files")
                        }
                        OutlinedButton(onClick = { page++ }, enabled = page + 1 < current.pageCount, modifier = cell) {
                            Text("Next files")
                        }
                    }
                }
            }
        }
    }
}

private fun coverageLabel(coverage: SelectedFileSearch.Coverage): String = when (coverage) {
    SelectedFileSearch.Coverage.OBSERVED -> "Reported files searched"
    SelectedFileSearch.Coverage.METADATA_PENDING -> "Torrent metadata pending"
    SelectedFileSearch.Coverage.EMPTY_OR_UNKNOWN -> "No file list confirmed"
    SelectedFileSearch.Coverage.UNAVAILABLE -> "File list unavailable"
    SelectedFileSearch.Coverage.LIMITED -> "Search limit reached"
    SelectedFileSearch.Coverage.INVALID -> "Ambiguous or invalid file metadata"
}

@Composable
private fun FileSearchMatchRow(match: SelectedFileSearch.Match, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(match.path, style = MaterialTheme.typography.bodyMedium)
        Text("${match.clientLabel} · ${match.transferName}", style = MaterialTheme.typography.bodySmall)
        Text("File index ${match.index} · ${match.sizeBytes} bytes · completed: " +
            (match.completedBytes?.let { "$it bytes" } ?: "unknown"), style = MaterialTheme.typography.bodySmall)
        Text("Observed " + DateFormat.getDateTimeInstance().format(Date(match.observedAtMillis)),
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open transfer details") }
    }
}
