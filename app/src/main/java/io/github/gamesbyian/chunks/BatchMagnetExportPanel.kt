package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.BatchMagnetHandoff
import io.github.gamesbyian.chunks.exports.OwnedDiagnosticExportProvider
import io.github.gamesbyian.chunks.exports.OwnedDiagnosticShare
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explicitly selected and reviewed identity-only handoff for both engines.
 * A destination client MAY discover metadata later; no payload copy or
 * source erasure is performed or offered here.
 */
@Composable
internal fun BatchMagnetExportPanel(
    rows: List<BatchMagnetHandoff.Row>,
    stale: Boolean,
    sourceLabel: String
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var includeTitles by remember { mutableStateOf(false) }
    var reviewed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var verified by remember { mutableStateOf(false) }

    val ids = remember(rows) { rows.map { it.infoHash.lowercase(Locale.ROOT) }.sorted() }
    LaunchedEffect(ids, stale) {
        selected = emptySet()
        reviewed = false
        if (stale) expanded = false
    }


    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {
                expanded = !expanded
                selected = emptySet()
                reviewed = false
                feedback = null
            }, enabled = !saving && !stale && rows.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (expanded) "Close batch magnet export" else "Export multiple magnet identities")
            }
            if (expanded) {
                Text("Batch identity-only handoff", style = MaterialTheme.typography.titleMedium)
                Text("Selected: ${selected.size}. Sources will not be stopped or deleted. " +
                    "This file contains no torrent payload, verified pieces, directories, " +
                    "trackers, passkeys or resume state. An importing client may have to " +
                    "re-download every byte. Private/tracker-only torrents may not work.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val matches = rows.map { it.infoHash.lowercase(Locale.ROOT) }.toSet()
                        if (matches.size > BatchMagnetHandoff.MAX_ITEMS) {
                            feedback = "More than 512 visible transfers. Narrow the search."
                        } else {
                            selected = matches
                            reviewed = false
                            feedback = null
                        }
                    }, enabled = !saving && rows.isNotEmpty(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("Select shown (${rows.size})")
                    }
                    OutlinedButton(onClick = {
                        selected = emptySet()
                        reviewed = false
                    }, enabled = !saving && selected.isNotEmpty(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("Clear")
                    }
                }
                rows.take(20).forEach { row ->
                    val key = row.infoHash.lowercase(Locale.ROOT)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = key in selected, enabled = !saving,
                            role = Role.Checkbox,
                            onValueChange = { checked ->
                                selected = if (checked) {
                                    if (selected.size >= BatchMagnetHandoff.MAX_ITEMS) selected
                                    else selected + key
                                } else selected - key
                                reviewed = false
                            }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = key in selected, onCheckedChange = null)
                        Text(row.originalName, modifier = Modifier.weight(1f), maxLines = 2)
                    }
                }
                if (rows.size > 20)
                    Text("Individual controls show the first 20 matches. Use Select shown " +
                        "for all currently filtered records.", style = MaterialTheme.typography.bodySmall)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                        value = includeTitles, enabled = !saving,
                        role = Role.Checkbox, onValueChange = {
                            includeTitles = it
                            reviewed = false
                        }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = includeTitles, onCheckedChange = null)
                    Text("Include original titles (may expose private information)")
                }
                if (reviewed) Text(
                    "Confirm: export only v1 magnets for selected, currently shown transfers. " +
                        "No tracker URLs or account credentials are copied. " +
                        "The receiving torrent client must independently discover metadata.")
                Button(onClick = {
                    if (!reviewed) {
                        reviewed = true
                        feedback = null
                    } else {
                        val report = BatchMagnetHandoff.prepare(rows, selected, includeTitles)
                        if (report == null) {
                            feedback = "No supported selected v1 magnet identities, or " +
                                "source selection changed. Nothing was saved."
                            reviewed = false
                        } else {
                            saving = true
                            verified = false
                            feedback = null
                            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                                .withZone(ZoneOffset.UTC).format(Instant.now())
                            scope.launch {
                                try {
                                    val uri = withContext(Dispatchers.IO) {
                                        OwnedDiagnosticExportProvider.publish(context,
                                            "chunks-magnet-handoff-${stamp}.txt", report.bytes)
                                    }
                                    OwnedDiagnosticShare.openChooser(context, uri,
                                        "text/plain", "magnet identity handoff")
                                    verified = true
                                    feedback = "Prepared ${report.exported} trackerless v1 magnets from " +
                                        sourceLabel + " for sharing. ${report.unsupported} unsupported " +
                                        "entries omitted. No receiving app has saved them yet; " +
                                        "source torrents and payloads are unchanged."
                                } catch (cancel: CancellationException) {
                                    feedback = "Export interrupted; no external file was written by Chunks."
                                    throw cancel
                                } catch (error: Exception) {
                                    feedback = "Cannot prepare safe magnet handoff: " +
                                        (error.message ?: "Private export unavailable") +
                                        ". No existing document was changed."
                                } finally { saving = false }
                            }
                        }
                    }
                }, enabled = !saving && !stale && selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (reviewed) "Share reviewed magnet identities"
                        else "Review magnet identity export")
                }
                feedback?.let {
                    Text(it, color = if (verified) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
