package io.github.gamesbyian.chunks.snapshots

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First user-facing, non-destructive snapshot slice. Covers precisely the
 * currently loaded rTorrent view, never unseen views, and explicitly exports
 * *inventory-only* hashes/names, not recoverable torrent definitions.
 *
 * Future versions must acquire proven raw metainfo before advertising actual
 * restore capability. No RPC, Add, torrent data or server filesystem mutations.
 */
@Composable
internal fun SnapshotInventoryExportScreen(
    transfers: List<RemoteTransfer>,
    view: String,
    snapshotRevision: Long,
    stale: Boolean,
    loading: Boolean,
    onBack: () -> Unit,
    /** Optional read-only comparison/restore-review panel for this server. */
    restorePanel: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeHistoricalDirectories by remember { mutableStateOf(false) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingRevision by remember { mutableLongStateOf(-1) }
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
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val bytes = pendingBytes
        val revision = pendingRevision
        pendingBytes = null
        pendingRevision = -1
        if (uri == null || bytes == null) {
            busy = false
            if (uri != null) result = "No validated archive was available to write."
        } else {
            scope.launch {
                resultIsVerified = false
                try {
                    withContext(Dispatchers.IO) {
                        SnapshotDocumentWriterV1.writeAndVerify(
                            context.contentResolver, uri, bytes
                        )
                    }
                    result = "Saved and SHA-256 readback verified for queue revision " +
                        revision + ". This is an INVENTORY-ONLY snapshot: " +
                        "torrent definitions and payload bytes were not backed up."
                    resultIsVerified = true
                } catch (cancel: CancellationException) {
                    result = "Save interrupted. The chosen file might be incomplete. " +
                        "Do not trust it without independent verification."
                    throw cancel
                } catch (failure: Exception) {
                    result = "Backup NOT verified: " +
                        (failure.message ?: "Document provider write/readback failed") +
                        ". The chosen file may be incomplete; discard or inspect it."
                } finally {
                    busy = false
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Back to transfers")
        }
        Text("Save transfer inventory", style = MaterialTheme.typography.headlineMedium)
        Text(
            "This first backup format captures transfer names and exact infohashes " +
                "from the currently loaded '$view' server view. It does NOT include " +
                "downloaded files, .torrent definitions or working magnets, so " +
                "it cannot yet restore these transfers on a new server.",
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
                    "Verified restorable: 0.",
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
        } else {
            Text(
                "Cannot capture this inventory: " +
                    (captureResult.exceptionOrNull()?.message ?: "Unknown error"),
                color = MaterialTheme.colorScheme.error
            )
        }
        Button(
            enabled = !busy && !loading && !stale && snapshotRevision > 0 &&
                captured != null && captured.inventoriedCount > 0,
            onClick = {
                val archive = runCatching { captured?.toArchive() }
                val bytes = archive.getOrNull()
                if (bytes == null) {
                    resultIsVerified = false
                    result = "Could not encode this inventory: " +
                        (archive.exceptionOrNull()?.message ?: "Unknown error")
                } else {
                    pendingBytes = bytes
                    pendingRevision = snapshotRevision
                    busy = true
                    result = null
                    resultIsVerified = false
                    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .withZone(ZoneOffset.UTC).format(Instant.now())
                    picker.launch("chunks-inventory-$stamp.chunksnap")
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) {
            Text(if (busy) "Saving inventory…" else "Choose where to save inventory snapshot")
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
