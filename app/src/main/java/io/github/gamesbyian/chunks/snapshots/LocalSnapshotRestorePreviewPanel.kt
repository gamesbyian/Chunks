package io.github.gamesbyian.chunks.snapshots

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * User-selected .chunksnap -> validated original-identity comparison against a
 * CURRENT FULL local-engine list. No engine command, network request, restored
 * file, destination choice or mutation is reachable from this screen.
 *
 * The caller is responsible for proving at least one successful whole-engine
 * observation. A fresh-looking initial empty Compose list is NOT proof.
 */
@Composable
internal fun LocalSnapshotRestorePreviewPanel(
    transfers: List<LocalTransferStatus>,
    inventoryConfirmed: Boolean,
    inventoryStale: Boolean,
    busyEngine: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SnapshotRestorePreviewV1.Preview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Local status counters and peers may update each second. Identity changes
    // are the relevant source boundary. A stale/unverified frame remains
    // unavailable even if its old hash list appears unchanged.
    val observedHashes = transfers.map { it.infoHash.lowercase(Locale.ROOT) }
    val targetReady = inventoryConfirmed && !inventoryStale && !busyEngine
    val latestHashes by rememberUpdatedState(observedHashes)
    val latestReady by rememberUpdatedState(targetReady)
    LaunchedEffect(observedHashes, targetReady) {
        result = null
        if (!targetReady) error = "A fresh, complete phone transfer list is required before comparison."
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            reading = false
        } else {
            // Freeze exactly the local inventory that was present when the
            // document picker returned. Later native updates revoke the result.
            val frozen = latestHashes.toList()
            if (!latestReady) {
                reading = false
                result = null
                error = "Phone transfer inventory became stale. Refresh before trying again."
            } else {
                reading = true
                result = null
                error = null
                scope.launch {
                    try {
                        val candidate = withContext(Dispatchers.IO) {
                            val inspected = SnapshotDocumentReaderV1.inspect(
                                context.contentResolver, uri)
                            SnapshotRestorePreviewV1.plan(
                                inspected, frozen, targetInventoryComplete = true)
                        }
                        if (latestReady && latestHashes == frozen) {
                            result = candidate
                        } else {
                            error = "Phone torrent identities changed during review. " +
                                "The comparison was discarded; reopen the snapshot."
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        error = "Cannot verify this snapshot against the phone: " +
                            (failure.message ?: "Unsupported or unsafe archive")
                    } finally {
                        reading = false
                    }
                }
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    expanded = !expanded
                    result = null
                    error = null
                },
                enabled = !reading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(if (expanded) "Close phone restore comparison"
                    else "Compare saved snapshot with phone")
            }
            if (expanded) {
                Text("Snapshot restore comparison (read-only)",
                    style = MaterialTheme.typography.titleMedium)
                Text("Compares a verified .chunksnap against every torrent in a " +
                    "fresh, complete phone-engine observation. It can identify missing " +
                    "torrent definitions, but cannot restore data or move downloaded files.")
                Text("The existing phone transfers stay untouched. A match means " +
                    "the exact torrent identity already exists here; it does not " +
                    "prove its payload is complete or correctly seeded.",
                    style = MaterialTheme.typography.bodySmall)
                if (!targetReady) {
                    Text("Phone inventory is not yet confirmed, is stale, or a native " +
                        "operation is in progress. Wait for a fresh full read.",
                        color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Current verified phone queue: ${transfers.size} transfer " +
                        "identities. Comparisons are discarded if this list changes.",
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = {
                        reading = true
                        result = null
                        error = null
                        open.launch(arrayOf("*/*"))
                    },
                    enabled = targetReady && !reading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) {
                    Text(if (reading) "Inspecting snapshot…"
                        else "Choose .chunksnap to compare")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                result?.let { preview ->
                    Text("Compared with ${preview.targetInventoryCount} current phone transfers.",
                        style = MaterialTheme.typography.titleMedium)
                    Text("Already present: ${preview.alreadyPresentCount}. " +
                        "Missing definitions needing individual review: " +
                        "${preview.candidateCount}. " +
                        "Not recoverable from this file: ${preview.unavailableCount}.")
                    Text("No destination was selected. No torrent was added, started, " +
                        "stopped, deleted, downloaded or otherwise changed.",
                        color = MaterialTheme.colorScheme.primary)
                    preview.entries.take(30).forEach { entry ->
                        val category = when (entry.decision) {
                            SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT ->
                                "Already on phone (skip)"
                            SnapshotRestorePreviewV1.Decision.METADATA_NEEDS_REVIEW ->
                                "Missing: verified torrent metadata needs Add review"
                            SnapshotRestorePreviewV1.Decision.MAGNET_NEEDS_REVIEW ->
                                "Missing: magnet needs metadata discovery and Add review"
                            SnapshotRestorePreviewV1.Decision.INVENTORY_ONLY ->
                                "Inventory only: no recovery definition"
                            SnapshotRestorePreviewV1.Decision.SOURCE_FAILED ->
                                "Source failed: no recovery definition"
                        }
                        Text(entry.originalName + " · " + category,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (preview.entries.size > 30) {
                        Text("Showing 30 of ${preview.entries.size} inspected entries. " +
                            "No restore changes were made.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Next steps are explicit Place remapping, a durable per-item " +
                        "import journal and exact-hash readback. A preview alone " +
                        "cannot authorize restoration.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
