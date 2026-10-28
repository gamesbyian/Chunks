package io.github.gamesbyian.chunks.snapshots

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opt-in restorable-definition export. Reads Chunks' own saved native resumes,
 * not torrent payloads or any remote server. A native resume may not contain
 * metainfo; such rows remain explicitly inventory-only, never forged.
 *
 * This does not perform any restore. Each actual .torrent may include
 * sensitive private tracker passkeys. An affirmative warning acknowledgement
 * is mandatory when even one definition is recoverable.
 */
@Composable
internal fun LocalVerifiedSnapshotPanel(
    transfers: List<LocalTransferStatus>,
    stale: Boolean,
    busyEngine: Boolean,
    // Native instrumentation may inject an inert reader; normal app always
    // uses the bounded verified local-resume source on Dispatchers.IO.
    recover: (suspend (List<LocalTransferStatus>, Boolean) -> LocalResumeMetainfoSourceV1.Recovery)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var oldPaths by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LocalResumeMetainfoSourceV1.Recovery?>(null) }
    var acknowledged by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var verifiedSave by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }

    // An admitted different torrent identity/name or lost source visibility
    // invalidates the review, not the saved output already written.
    val identities = remember(transfers) {
        transfers.map { it.infoHash to it.name }.sortedBy { it.first }
    }
    LaunchedEffect(identities, stale, busyEngine) {
        if (stale || busyEngine) {
            result = null
            acknowledged = false
            pending = null
            feedback = "Phone transfer inventory is not fresh. Recover only from a confirmed queue."
        } else {
            // A changed source list cannot silently keep a previous review.
            result = null
            acknowledged = false
        }
    }
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val frozen = pending
        pending = null
        if (uri == null || frozen == null) {
            saving = false
            if (uri == null) feedback = "Save cancelled. Source torrents were not modified."
            else feedback = "No reviewed snapshot bytes were available."
        } else {
            scope.launch {
                verifiedSave = false
                try {
                    withContext(Dispatchers.IO) {
                        SnapshotDocumentWriterV1.writeAndVerify(context.contentResolver, uri, frozen)
                    }
                    verifiedSave = true
                    feedback = "Snapshot saved and SHA-256 readback verified. " +
                        "This preserves .torrent definitions only, not files, pieces or resume progress."
                } catch (cancel: CancellationException) {
                    feedback = "Save interrupted. The document may be incomplete."
                    throw cancel
                } catch (error: Exception) {
                    feedback = "Snapshot NOT verified: " +
                        (error.message ?: "The document provider refused readback") +
                        ". Inspect or discard this output."
                } finally { saving = false }
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { expanded = !expanded }, enabled = !scanning && !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (expanded) "Close verified backup review" else
                    "Recover real .torrent definitions for backup")
            }
            if (!expanded) return@Column
            Text("Verified torrent-definition backup", style = MaterialTheme.typography.titleMedium)
            Text("A saved Chunks resume might contain original v1 torrent metainfo. " +
                "A name or infohash alone cannot restore a torrent. Only definitions " +
                "whose original hash and title pass independent validation are usable. " +
                "This reads existing app-private resume records, not downloaded files, " +
                "and makes no torrent engine or remote server changes.",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                value = oldPaths, enabled = !scanning && !saving,
                role = Role.Checkbox, onValueChange = {
                    oldPaths = it
                    result = null
                    acknowledged = false
                }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = oldPaths, onCheckedChange = null)
                Text("Include old phone save paths (private; not portable)")
            }
            if (stale || busyEngine) Text(
                "Cannot recover now: phone inventory is stale or changing.",
                color = MaterialTheme.colorScheme.error)
            Button(onClick = {
                scanning = true
                result = null
                acknowledged = false
                verifiedSave = false
                feedback = null
                val frozen = transfers.toList()
                val includePaths = oldPaths
                scope.launch {
                    try {
                        val found = if (recover != null)
                            recover(frozen, includePaths)
                        else withContext(Dispatchers.IO) {
                            LocalResumeMetainfoSourceV1.capture(
                                File(context.filesDir, "local-engine"),
                                frozen, Instant.now(), includePaths
                            )
                        }
                        if (!stale && !busyEngine) {
                            // toArchive already checks all exact metainfo identity
                            // and ZIP receipts. We prove decode as a second gate.
                            withContext(Dispatchers.IO) {
                                SnapshotManifestV1.decode(found.toArchive())
                            }
                            result = found
                        } else feedback = "Phone inventory changed during recovery. Try again."
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        feedback = "Recovery unavailable or unverified: " +
                            (error.message ?: "Invalid resume or archive")
                    } finally { scanning = false }
                }
            }, enabled = !stale && !busyEngine && !scanning && !saving &&
                transfers.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (scanning) "Reading saved definitions…" else "Check recoverable definitions")
            }
            val ready = result
            if (ready != null) {
                Text("Observed: ${ready.sourceCount}; records: ${ready.manifest.items.size}; " +
                    "verified .torrent definitions: ${ready.verifiedRestorableCount}; " +
                    "inventory-only: ${ready.inventoryOnlyCount}; unsupported/omitted: " +
                    "${ready.omittedCount}.")
                if (ready.unavailableByHash.isNotEmpty())
                    Text("${ready.unavailableByHash.size} recorded transfers had no safe, " +
                        "matching, usable native .torrent definition. They remain inventory-only.",
                        style = MaterialTheme.typography.bodySmall)
                if (ready.verifiedRestorableCount > 0) {
                    Text("Sensitive export: original .torrent definitions can include " +
                        "private tracker addresses, embedded passkeys and other identifiers. " +
                        "Do not share this backup publicly, and store it somewhere private.",
                        color = MaterialTheme.colorScheme.error)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = acknowledged, enabled = !saving,
                            role = Role.Checkbox, onValueChange = { acknowledged = it }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = acknowledged, onCheckedChange = null)
                        Text("I understand the snapshot may contain private tracker credentials")
                    }
                    Button(onClick = {
                        val encoded = runCatching { ready.toArchive() }.getOrNull()
                        if (encoded == null) {
                            feedback = "Could not validate the reviewed archive. Nothing saved."
                        } else {
                            pending = encoded
                            saving = true
                            verifiedSave = false
                            feedback = null
                            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                                .withZone(ZoneOffset.UTC).format(Instant.now())
                            saver.launch("chunks-phone-verified-torrents-$stamp.chunksnap")
                        }
                    }, enabled = acknowledged && !stale && !busyEngine && !scanning && !saving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(if (saving) "Saving verified archive…" else
                            "Choose private file for verified snapshot")
                    }
                } else {
                    Text("No complete v1 .torrent definitions were recoverable from the " +
                        "saved resumes. Use inventory-only backup instead; nothing here " +
                        "can be promised as restorable.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("Even verified .torrent definitions do not include downloaded data. " +
                    "Restoring onto a new engine is not implemented by this screen.",
                    style = MaterialTheme.typography.bodySmall)
            }
            feedback?.let { Text(it, color = if (verifiedSave) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error) }
        }
    }
}
