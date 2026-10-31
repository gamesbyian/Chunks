package io.github.gamesbyian.chunks.snapshots

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.gamesbyian.chunks.exports.OwnedDiagnosticExportProvider
import io.github.gamesbyian.chunks.exports.OwnedDiagnosticShare

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
    var search by remember { mutableStateOf("") }
    var recoveryFilter by remember { mutableStateOf<SnapshotManifestV1.Recovery?>(null) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var savingTorrent by remember { mutableStateOf(false) }
    var saveNotice by remember { mutableStateOf<String?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            inspected = null
            problem = null
            search = ""
            recoveryFilter = null
            pageIndex = 0
            saveNotice = null
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
        Text("Open a .chunksnap file saved from Chunks. Inspect it offline, or " +
            "separately share a verified original .torrent definition for another app to save. " +
            "No transfer is added, started, stopped, deleted or altered.")
        Button(
            onClick = { choose.launch(arrayOf("*/*")) },
            enabled = !busy && !savingTorrent,
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
            Text("Inspection never changes torrent sessions. The optional export saves " +
                "one raw original .torrent to a separately selected Android document. " +
                "It can contain private tracker passkeys, so keep exported files private. " +
                "Automatic restoring to a target engine is not yet available.")
            saveNotice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it; pageIndex = 0 },
                singleLine = true,
                label = { Text("Find original name or v1 infohash") },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = recoveryFilter == null,
                    onClick = { recoveryFilter = null; pageIndex = 0 },
                    label = { Text("All") },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                SnapshotManifestV1.Recovery.entries.forEach { option ->
                    FilterChip(
                        selected = recoveryFilter == option,
                        onClick = { recoveryFilter = option; pageIndex = 0 },
                        label = { Text(option.wire) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
            val result = SnapshotInspectCatalogV1.select(
                items, search, recoveryFilter, pageIndex
            )
            Text("Showing " + result.firstIndex + "–" + result.lastIndex +
                " of " + result.matchingCount + " matching records (" +
                result.totalCount + " in archive).",
                style = MaterialTheme.typography.bodySmall)
            result.items.forEach { item ->
                Text(item.originalName + "\n" + item.infoHashV1 + " · " +
                    item.recovery.wire, style = MaterialTheme.typography.bodySmall)
                if (item.recovery == SnapshotManifestV1.Recovery.METAINFO) {
                    OutlinedButton(
                        onClick = {
                            try {
                                val selected = SnapshotVerifiedTorrentDocumentV1.prepare(
                                    checked, item.infoHashV1)
                                savingTorrent = true
                                saveNotice = null
                                scope.launch {
                                    try {
                                        if (inspected?.metainfoByHash?.get(selected.infoHashV1)
                                            ?.contentEquals(selected.bytes) != true) {
                                            saveNotice = "Torrent selection expired or the source changed."
                                        } else {
                                            val uri = withContext(Dispatchers.IO) {
                                                OwnedDiagnosticExportProvider.publish(context,
                                                    "chunks-original-${selected.infoHashV1}.torrent",
                                                    selected.bytes)
                                            }
                                            OwnedDiagnosticShare.openChooser(context, uri,
                                                "application/x-bittorrent", "original .torrent")
                                            saveNotice = "The verified original .torrent was prepared as a " +
                                                "read-only share. The receiving app has not saved it yet; " +
                                                "no torrent was added or changed."
                                        }
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (failure: Exception) {
                                        saveNotice = "Could not prepare original .torrent sharing: " +
                                            (failure.message ?: "Private export unavailable") +
                                            ". Existing documents remain unchanged."
                                    } finally { savingTorrent = false }
                                }
                            } catch (failure: Exception) {
                                saveNotice = "Cannot prepare the verified original: " +
                                    (failure.message ?: "Unsupported torrent definition")
                            }
                        },
                        enabled = !busy && !savingTorrent,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Share or save original .torrent") }
                }
            }
            if (result.items.isEmpty()) {
                Text("No records match these filters. This does not alter the archive.")
            }
            if (result.hasPrevious || result.hasNext) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { pageIndex = (result.pageIndex - 1).coerceAtLeast(0) },
                        enabled = result.hasPrevious,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Previous") }
                    OutlinedButton(
                        onClick = { pageIndex = result.pageIndex + 1 },
                        enabled = result.hasNext,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Next") }
                }
            }
            Text("Inspection is read-only. No torrent definitions were changed.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
