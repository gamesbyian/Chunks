package io.github.gamesbyian.chunks.snapshots

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bounded Android document reader. Never creates files or contacts any torrent engine. */
internal object SnapshotDocumentReaderV1 {
    private const val MAX_BYTES = 64 * 1024 * 1024
    fun inspect(resolver: ContentResolver, uri: Uri): SnapshotManifestV1.Verified {
        val input = resolver.openInputStream(uri)
            ?: throw IOException("Android could not open this document")
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16_384)
            var emptyReads = 0
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (n == 0) {
                    if (++emptyReads > 16) throw IOException("Document provider returned no data")
                    continue
                }
                emptyReads = 0
                if (output.size().toLong() + n > MAX_BYTES)
                    throw IOException("Snapshot archive exceeds the 64 MiB limit")
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        // Only the schema validator proves archive version, membership, checksum
        // and v1 .torrent metainfo identity. This method never extracts any member.
        return SnapshotManifestV1.decode(bytes)
    }
}

/**
 * Offline, read-only inspection for an existing .chunksnap file, including a
 * backup exported before the user connected to any rTorrent server. Inspection
 * does NOT authorize duplicate-target decisions or any restore.
 */
@Composable
internal fun SnapshotDocumentInspectScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var inspected by remember { mutableStateOf<SnapshotManifestV1.Verified?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            inspected = null
            problem = null
            scope.launch {
                try {
                    inspected = withContext(Dispatchers.IO) {
                        SnapshotDocumentReaderV1.inspect(context.contentResolver, uri)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    problem = "Snapshot cannot be trusted or opened: " +
                        (failure.message ?: "Invalid or unavailable document")
                } finally { busy = false }
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Back")
        }
        Text("Inspect transfer snapshot", style = MaterialTheme.typography.headlineMedium)
        Text("Open a .chunksnap file saved from Chunks. Inspection is completely " +
            "offline, reads only the chosen document, and never adds, starts, " +
            "stops, deletes or alters any local or remote torrents.")
        Button(
            onClick = { choose.launch(arrayOf("*/*")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) { Text(if (busy) "Checking snapshot…" else "Choose .chunksnap file") }
        if (problem != null) Text(problem.orEmpty(),
            color = MaterialTheme.colorScheme.error)
        val checked = inspected
        if (checked != null) {
            val items = checked.manifest.items
            Text("Archive verified", style = MaterialTheme.typography.titleLarge)
            Text("Created: " + checked.manifest.createdAtUtc)
            Text("Source: " + checked.manifest.source.wire)
            Text("Total transfer records: " + items.size)
            Text("Definitions with verified complete v1 .torrent metainfo: " +
                items.count { it.recovery == SnapshotManifestV1.Recovery.METAINFO })
            Text("Magnet fallbacks requiring network metadata discovery: " +
                items.count { it.recovery == SnapshotManifestV1.Recovery.MAGNET })
            Text("Inventory-only records (cannot restore by hash alone): " +
                items.count { it.recovery == SnapshotManifestV1.Recovery.INVENTORY })
            Text("Failed source records: " +
                items.count { it.recovery == SnapshotManifestV1.Recovery.FAILED })
            if (checked.requiresSensitiveExportWarning) {
                Text("Sensitive backup warning: embedded torrent definitions or " +
                    "magnet trackers may contain private tracker passkeys. " +
                    "Keep this document private.",
                    color = MaterialTheme.colorScheme.error)
            }
            Text("No payload files are included. A historical completion percentage " +
                "does not prove bytes exist on a replacement server.")
            Text("This screen is read-only. Restoring to a target engine remains " +
                "unavailable until destination checks, exact identity deduplication " +
                "and a durable import journal are implemented.")
            items.take(30).forEach { item ->
                Text(item.originalName + " · " + item.recovery.wire,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (items.size > 30) Text("Showing the first 30 entries of " + items.size +
                ". No transfer definitions were changed.")
        }
    }
}
