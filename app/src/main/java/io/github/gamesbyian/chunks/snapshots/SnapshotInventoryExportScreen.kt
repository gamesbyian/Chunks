package io.github.gamesbyian.chunks.snapshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.exports.OwnedDiagnosticExportProvider
import io.github.gamesbyian.chunks.exports.OwnedDiagnosticShare
import java.time.Instant
import java.util.Locale
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First user-facing, non-destructive snapshot slice. Covers precisely the
 * current rTorrent view only. The default remains inventory-only; separate
 * explicit consent permits independently verified original v1 .torrent
 * definitions from a scoped read-only ruTorrent Source plugin. Never
 * modifies a server session or copies payload bytes.
 */
@Composable
internal fun SnapshotInventoryExportScreen(
    transfers: List<RemoteTransfer>,
    view: String,
    snapshotRevision: Long,
    stale: Boolean,
    loading: Boolean,
    onBack: () -> Unit,
    /** Optional exact-account, read-only Source-plugin lookup. */
    readOriginalMetainfo: (suspend (String) -> ByteArray?)? = null,
    /** Opt-in to sensitive source data must be re-affirmed after a connection change. */
    sourceSessionIdentity: Any? = null,
    /** Invalidates an in-flight export if its connected remote account changes. */
    accountStillCurrent: () -> Boolean = { true },
    /** Optional read-only comparison/restore-review panel for this server. */
    restorePanel: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeHistoricalDirectories by remember { mutableStateOf(false) }
    var includeOriginalDefinitions by remember(sourceSessionIdentity) { mutableStateOf(false) }
    var recoveredProgress by remember { mutableIntStateOf(0) }
    var recoveredTotal by remember { mutableIntStateOf(0) }
    // Snapshot revision advances on every ordinary remote poll, including
    // speed-only updates. A long Source export must be invalidated by a
    // different job/name/Place or target, not by irrelevant traffic counters.
    val observedRows = transfers.map {
        Triple(it.hash.lowercase(Locale.ROOT), it.name, it.directory)
    }.sortedBy { it.first }
    val currentRows by rememberUpdatedState(observedRows)
    val currentView by rememberUpdatedState(view)
    // A poll in flight need not revoke an otherwise fresh proven inventory.
    // A stale view, new row/Place or switched account does.
    val currentReady by rememberUpdatedState(!stale)
    val currentAccount by rememberUpdatedState(accountStillCurrent)
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultIsVerified by remember { mutableStateOf(false) }
    val captureResult = remember(transfers, view, snapshotRevision, includeHistoricalDirectories) {
        runCatching {
            SnapshotInventoryCaptureV1.remote(
                transfers = transfers,
                createdAt = Instant.now(),
                includeHistoricalDirectories = includeHistoricalDirectories
            )
        }
    }
    val captured = captureResult.getOrNull()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Back to transfers")
        }
        Text("Save transfer snapshot", style = MaterialTheme.typography.headlineMedium)
        Text(
            "This snapshot records transfer names and exact infohashes from the " +
                "currently loaded '$view' server view. You can explicitly request " +
                "verified original .torrent definitions if the server supports them. " +
                "Downloaded files, payload bytes and old server settings are never included.",
            style = MaterialTheme.typography.bodyLarge
        )
        if (stale || loading || snapshotRevision <= 0) {
            Text(
                "The server inventory is not currently fresh. Return to transfers " +
                    "and Refresh before saving.",
                color = MaterialTheme.colorScheme.error
            )
        }
        if (captured != null) {
            Text(
                "Observed: ${captured.sourceCount} transfers in '$view'. " +
                    "Recorded: ${captured.inventoriedCount}. " +
                    "Omitted: ${captured.omitted.size}. " +
                    "Recoverable before optional Source reads: 0.",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Source queue revision: $snapshotRevision. " +
                    "This snapshot reflects one loaded view, not necessarily " +
                    "every transfer on the server."
            )
            if (captured.omitted.isNotEmpty()) {
                Text(
                    "Unsupported or unsafe entries were omitted. " +
                        "Review your source queue before relying on this inventory.",
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = includeHistoricalDirectories,
                    enabled = !busy,
                    role = Role.Checkbox,
                    onValueChange = { includeHistoricalDirectories = it }
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = includeHistoricalDirectories, onCheckedChange = null)
                Text(
                    "Include old server directories (may reveal private paths)",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Text(
                "Old paths are reference hints only. They will never be " +
                    "automatically sent to another server.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = includeOriginalDefinitions,
                    enabled = !busy && readOriginalMetainfo != null,
                    role = Role.Checkbox,
                    onValueChange = { includeOriginalDefinitions = it }
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = includeOriginalDefinitions,
                    enabled = readOriginalMetainfo != null,
                    onCheckedChange = null
                )
                Text("Include verified original .torrent definitions when available",
                    modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                "Original torrent definitions can contain private tracker passkeys " +
                    "and sensitive announce URLs. Enabling this explicitly requests " +
                    "them from this server without altering its transfers. " +
                    "Unsupported or missing originals remain inventory-only. " +
                    "Keep the shared snapshot private.",
                style = MaterialTheme.typography.bodySmall
            )
            if (readOriginalMetainfo == null) {
                Text("Original-source retrieval is unavailable for this server.",
                    style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(
                "Cannot capture this inventory: " +
                    (captureResult.exceptionOrNull()?.message ?: "Unknown error"),
                color = MaterialTheme.colorScheme.error
            )
        }
        Text(
            "Safe export: Chunks prepares an independent read-only document and opens " +
                "Android's Share menu. The receiving app handles any final Save. " +
                "Chunks will not overwrite an unproven document selected through a file picker.",
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            enabled = !busy && !loading && !stale && snapshotRevision > 0 &&
                captured != null && captured.inventoriedCount > 0 &&
                (!includeOriginalDefinitions || readOriginalMetainfo != null),
            onClick = {
                val capture = captured ?: return@Button
                val reader = readOriginalMetainfo
                val originals = includeOriginalDefinitions
                val revision = snapshotRevision
                val observedView = view
                val frozenRows = observedRows.toList()
                val stillCurrent = {
                    currentReady && currentRows == frozenRows &&
                        currentView == observedView && currentAccount()
                }
                busy = true
                recoveredProgress = 0
                recoveredTotal = if (originals) capture.manifest.items.size else 0
                result = null
                resultIsVerified = false
                val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(ZoneOffset.UTC).format(Instant.now())
                scope.launch {
                    try {
                        check(stillCurrent()) { "The connected server inventory changed." }
                        val enriched = if (originals && reader != null)
                            SnapshotRemoteMetainfoCaptureV1.collect(
                                capture, reader, stillCurrent,
                                onProgress = { done, total ->
                                    recoveredProgress = done
                                    recoveredTotal = total
                                })
                            else null
                        val bytes = enriched?.archive ?: withContext(Dispatchers.Default) {
                            capture.toArchive()
                        }
                        check(stillCurrent()) { "The connected server inventory changed." }
                        val uri = withContext(Dispatchers.IO) {
                            OwnedDiagnosticExportProvider.publish(context,
                                "chunks-snapshot-${stamp}.chunksnap", bytes)
                        }
                        check(stillCurrent()) { "The connected server inventory changed." }
                        OwnedDiagnosticShare.openChooser(context, uri,
                            "application/octet-stream", "transfer snapshot")
                        result = if (enriched != null)
                            "A verified, read-only snapshot is ready to share for queue revision " +
                                revision + ". Verified original definitions: " + enriched.recovered +
                                "; inventory-only after Source lookup: " +
                                (enriched.unavailable + enriched.sizeLimited) +
                                " (unavailable: " + enriched.unavailable +
                                "; size-limited: " + enriched.sizeLimited +
                                "). The receiving app has not saved it yet. " +
                                "Original tracker data may be sensitive. No payload bytes are included."
                            else
                            "A byte-verified, read-only inventory snapshot is ready to share for " +
                                "queue revision " + revision +
                                ". Nothing has been saved by the receiving app yet. " +
                                "This inventory contains no restorable definitions or payload bytes."
                        resultIsVerified = true
                    } catch (cancel: CancellationException) {
                        result = "Snapshot handoff was interrupted; no external document was modified by Chunks."
                        throw cancel
                    } catch (_: Exception) {
                        result = "Could not verify and share this snapshot. Refresh the exact " +
                            "server inventory and review the request again. No server transfer was changed."
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) {
            Text(if (busy) "Preparing snapshot…" else
                if (includeOriginalDefinitions) "Share snapshot with verified originals"
                else "Share inventory snapshot")
        }
        if (busy && recoveredTotal > 0) {
            Text("Checking original definitions: ${recoveredProgress} of ${recoveredTotal}.")
        }

        if (result != null) {
            Text(
                result.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = if (resultIsVerified) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
            )
        }
        Text(
            "This action reads the already-loaded Chunks transfer list. " +
                "It does not add, start, stop, delete, or copy any torrent on your server.",
            style = MaterialTheme.typography.bodySmall
        )
        restorePanel?.invoke()
    }
}
