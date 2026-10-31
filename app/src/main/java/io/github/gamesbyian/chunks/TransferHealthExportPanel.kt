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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.TransferHealthReport
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.exports.OwnedDiagnosticExportProvider
import io.github.gamesbyian.chunks.exports.OwnedDiagnosticShare
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A single offline support artifact for diagnosing stalled downloads.
 * Never queries/mutates an engine, even when its last view is stale.
 * No torrent names, hashes, paths, trackers or credentials by default.
 */
@Composable
internal fun RemoteTransferHealthExportPanel(
    transfers: List<RemoteTransfer>,
    serverView: String,
    stale: Boolean
) {
    TransferHealthDocumentPanel(
        scope = "rtorrent",
        stale = stale,
        prepare = { names -> TransferHealthReport.remote(transfers, serverView, stale, names) }
    )
}

@Composable
internal fun LocalTransferHealthExportPanel(
    transfers: List<LocalTransferStatus>,
    stale: Boolean
) {
    TransferHealthDocumentPanel(
        scope = "phone",
        stale = stale,
        prepare = { names -> TransferHealthReport.local(transfers, stale, names) }
    )
}

@Composable
private fun TransferHealthDocumentPanel(
    scope: String,
    stale: Boolean,
    prepare: (Boolean) -> TransferHealthReport.Prepared
) {
    val context = LocalContext.current
    val coroutine = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var includeNames by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var verified by remember { mutableStateOf(false) }


    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { expanded = !expanded },
                enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (expanded) "Close download-health report" else "Save download-health report")
            }
            if (expanded) {
                Text("Transfer health, one observation", style = MaterialTheme.typography.titleMedium)
                Text("This offline report records only the queue snapshot already displayed by Chunks. " +
                    "It cannot prove peer reachability, newly verified bytes or that future downloads will start.",
                    style = MaterialTheme.typography.bodySmall)
                if (stale) Text("The queue is stale; the report will mark its evidence as stale.",
                    color = MaterialTheme.colorScheme.error)
                Text("Private by default: never includes torrent hashes, server endpoints, " +
                    "usernames, credentials, file paths, trackers, private passkeys or raw errors.",
                    style = MaterialTheme.typography.bodySmall)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                        value = includeNames, enabled = !saving, role = Role.Checkbox,
                        onValueChange = { includeNames = it }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = includeNames, onCheckedChange = null)
                    Text("Include original torrent names (may be sensitive)")
                }
                Text(
                    "Safe export: Chunks makes a new read-only document in its private cache. " +
                        "Android's Share menu lets you choose an app to save or send it. " +
                        "No existing provider document is opened for writing.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = {
                    val captured = try {
                        prepare(includeNames)
                    } catch (failure: Exception) {
                        result = "Could not prepare report: " +
                            (failure.message ?: "Invalid transfer snapshot")
                        null
                    }
                    if (captured != null) {
                        saving = true
                        verified = false
                        result = null
                        val utc = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                            .withZone(ZoneOffset.UTC).format(Instant.now())
                        coroutine.launch {
                            try {
                                val uri = withContext(Dispatchers.IO) {
                                    OwnedDiagnosticExportProvider.publish(context,
                                        "chunks-${scope}-health-${utc}.json", captured.bytes)
                                }
                                OwnedDiagnosticShare.openChooser(context, uri,
                                    "application/json", "download-health report")
                                verified = true
                                result = "Prepared a byte-verified read-only report with ${captured.reported} " +
                                    "of ${captured.observed} observed transfer records. " +
                                    "The receiving app has not saved it yet."
                            } catch (cancel: CancellationException) {
                                result = "Report handoff interrupted; Chunks did not modify an external file."
                                throw cancel
                            } catch (failure: Exception) {
                                result = "Report NOT prepared for sharing: " +
                                    (failure.message ?: "Private export unavailable") +
                                    ". Chunks did not write a picker-returned document."
                            } finally {
                                saving = false
                            }
                        }
                    }
                }, enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (saving) "Preparing report…" else "Share download-health report")
                }
                result?.let {
                    Text(it, color = if (verified) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
