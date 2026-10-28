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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import java.time.Instant
import java.io.File
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Read-only local-engine inventory snapshot. The observed queue has no raw
 * torrent metainfo, so all records are INVENTORY ONLY: never promise restore.
 * No libtorrent call is made during capture or SAF publication.
 */
private data class VerifiedResumeReview(
    val identity: List<Triple<String, String, String>>,
    val recovery: LocalResumeMetainfoSourceV1.Recovery
)

@Composable
internal fun LocalInventorySnapshotPanel(
    transfers: List<LocalTransferStatus>,
    stale: Boolean,
    busyEngine: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var historicalPaths by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultVerified by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var pendingVerifiedDefinitions by remember { mutableStateOf(0) }
    var includeDefinitions by remember { mutableStateOf(false) }
    var inspectingResumes by remember { mutableStateOf(false) }
    var verifiedResumes by remember {
        mutableStateOf<VerifiedResumeReview?>(null)
    }
    var resumeProblem by remember { mutableStateOf<String?>(null) }
    // A successful live transfer frame may change counters each second.
    // Only original source identities/names and optionally historical Places
    // change what a reviewed snapshot means.
    val reviewIdentity = transfers.map {
        Triple(it.infoHash, it.name, if (historicalPaths) it.savePath else "")
    }
    // Long-running IO must check the latest Compose state rather than a closure
    // frozen before Android's document picker / native resume decode.
    val currentReviewIdentity by rememberUpdatedState(reviewIdentity)
    val currentStale by rememberUpdatedState(stale)
    val currentBusyEngine by rememberUpdatedState(busyEngine)
    val currentOptIn by rememberUpdatedState(includeDefinitions)
    LaunchedEffect(reviewIdentity, historicalPaths, stale, busyEngine) {
        verifiedResumes = null
        resumeProblem = null
    }
    val captureResult = remember(transfers, historicalPaths) {
        runCatching {
            SnapshotInventoryCaptureV1.local(transfers, Instant.now(),
                includeHistoricalDirectories = historicalPaths)
        }
    }
    val capture = captureResult.getOrNull()
    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val bytes = pending
        val definitions = pendingVerifiedDefinitions
        pending = null
        pendingVerifiedDefinitions = 0
        if (uri == null || bytes == null) {
            saving = false
            if (uri != null) result = "No validated local snapshot was available for saving."
        } else {
            scope.launch {
                resultVerified = false
                try {
                    withContext(Dispatchers.IO) {
                        SnapshotDocumentWriterV1.writeAndVerify(context.contentResolver, uri, bytes)
                    }
                    result = if (definitions > 0) {
                        "Saved and SHA-256 verified $definitions real v1 torrent definitions. " +
                            "This file may contain private-tracker passkeys; keep it secret. " +
                            "No downloaded payload bytes or engine resume state are included."
                    } else {
                        "Saved and SHA-256 readback verified. Inventory only: " +
                            "no .torrent definitions, magnets, file payloads or resume data backed up."
                    }
                    resultVerified = true
                } catch (cancel: CancellationException) {
                    result = "Save interrupted: the document may be incomplete. Verify or discard it."
                    throw cancel
                } catch (failure: Exception) {
                    result = "Backup NOT verified: " +
                        (failure.message ?: "Document provider readback failed") +
                        ". Discard or independently inspect the file."
                } finally {
                    saving = false
                }
            }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { expanded = !expanded }, enabled = !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (expanded) "Close phone inventory export" else "Save phone transfer inventory")
            }
            if (expanded) {
                Text("Local transfer inventory only", style = MaterialTheme.typography.titleMedium)
                Text("Records exact v1 hashes and original torrent names from the current " +
                    "observed phone queue. A hash by itself cannot restore a torrent. " +
                    "This export contains no torrent metadata, magnets, payload files, " +
                    "piece verification or resume state.")
                if (stale || busyEngine) Text(
                    "The local engine inventory is stale or changing. Return after a fresh " +
                        "confirmed observation before exporting.",
                    color = MaterialTheme.colorScheme.error)
                if (capture != null) {
                    Text("Observed: ${capture.sourceCount}; recorded: " +
                        "${capture.inventoriedCount}; omitted: ${capture.omitted.size}; " +
                        "verified restorable: 0.", style = MaterialTheme.typography.bodyMedium)
                    Text("This is the currently loaded on-device engine, not remote rTorrent.")
                    if (capture.omitted.isNotEmpty())
                        Text("Some identities/names were omitted as unsafe or unsupported.",
                            color = MaterialTheme.colorScheme.error)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = historicalPaths, enabled = !saving,
                            role = Role.Checkbox,
                            onValueChange = { historicalPaths = it }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = historicalPaths, onCheckedChange = null)
                        Text("Include old phone paths (privacy-sensitive)")
                    }
                    Text("Saved path hints are historical only. They are not portable " +
                        "destinations and never become automatic restore targets.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = includeDefinitions, enabled = !saving && !inspectingResumes,
                            role = Role.Checkbox,
                            onValueChange = { includeDefinitions = it; verifiedResumes = null }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = includeDefinitions, onCheckedChange = null)
                        Text("Recover verified .torrent definitions (sensitive)")
                    }
                    if (includeDefinitions) {
                        Text("Private tracker credentials, announce addresses or passkeys " +
                            "may be present in recovered torrent metadata. This is optional " +
                            "and OFF by default. Keep any exported file private. " +
                            "Still no downloaded content or seeding-state migration.",
                            color = MaterialTheme.colorScheme.error)
                        OutlinedButton(
                            onClick = {
                                inspectingResumes = true
                                verifiedResumes = null
                                resumeProblem = null
                                val frozen = transfers.toList()
                                val usePaths = historicalPaths
                                scope.launch {
                                    try {
                                        val candidate = withContext(Dispatchers.IO) {
                                            LocalResumeMetainfoSourceV1.capture(
                                                File(context.filesDir, "local-engine"),
                                                frozen, Instant.now(), usePaths)
                                        }
                                        // Never advertise a preview from a now-stale source.
                                        if (!currentStale && !currentBusyEngine && currentOptIn &&
                                            currentReviewIdentity == frozen.map {
                                                Triple(it.infoHash, it.name,
                                                    if (usePaths) it.savePath else "")
                                            }) {
                                            verifiedResumes = VerifiedResumeReview(
                                                frozen.map {
                                                    Triple(it.infoHash, it.name,
                                                        if (usePaths) it.savePath else "")
                                                }, candidate)
                                        } else {
                                            resumeProblem = "Transfer inventory changed. " +
                                                "Refresh and inspect again."
                                        }
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (failure: Exception) {
                                        resumeProblem = "No verified torrent definitions available: " +
                                            (failure.message ?: "Unsafe or unsupported resume data")
                                    } finally {
                                        inspectingResumes = false
                                    }
                                }
                            },
                            enabled = !saving && !stale && !busyEngine &&
                                !inspectingResumes && capture.inventoriedCount > 0,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text(if (inspectingResumes) "Inspecting saved native records…" else
                            "Preview verified torrent definitions") }
                        verifiedResumes?.let { review ->
                            val recovery = review.recovery
                            Text("Verified real v1 definitions: " +
                                recovery.verifiedRestorableCount + "; remaining inventory-only: " +
                                recovery.inventoryOnlyCount + "; unsupported/omitted: " +
                                recovery.omittedCount + ".")
                            Text("Only source-verified original torrent names and hashes " +
                                "are accepted. This file is NOT a copy of its payload.",
                                style = MaterialTheme.typography.bodySmall)
                            Button(
                                onClick = {
                                    saving = true
                                    result = null
                                    resultVerified = false
                                    val selected = recovery
                                    scope.launch {
                                        try {
                                            val encoded = withContext(Dispatchers.IO) {
                                                selected.toArchive()
                                            }
                                            if (currentStale || currentBusyEngine || !currentOptIn ||
                                                currentReviewIdentity != review.identity) {
                                                result = "Source became stale. Preview again before saving."
                                                saving = false
                                            } else {
                                                pending = encoded
                                                pendingVerifiedDefinitions =
                                                    selected.verifiedRestorableCount
                                                val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                                                    .withZone(ZoneOffset.UTC).format(Instant.now())
                                                save.launch("chunks-phone-reviewed-$stamp.chunksnap")
                                            }
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (failure: Exception) {
                                            saving = false
                                            result = "Cannot encode verified torrent metadata: " +
                                                (failure.message ?: "Invalid snapshot")
                                        }
                                    }
                                },
                                enabled = !saving && !stale && !busyEngine &&
                                    recovery.verifiedRestorableCount > 0,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            ) { Text("Save reviewed sensitive .chunksnap") }
                        }
                        resumeProblem?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else Text("Cannot capture inventory: " +
                    (captureResult.exceptionOrNull()?.message ?: "Unknown problem"),
                    color = MaterialTheme.colorScheme.error)
                Button(
                    enabled = !saving && !stale && !busyEngine &&
                        capture != null && capture.inventoriedCount > 0,
                    onClick = {
                        val encoded = runCatching { capture?.toArchive() }
                        val bytes = encoded.getOrNull()
                        if (bytes == null) {
                            resultVerified = false
                            result = "Could not encode local inventory: " +
                                (encoded.exceptionOrNull()?.message ?: "Invalid capture")
                        } else {
                            pending = bytes
                            pendingVerifiedDefinitions = 0
                            saving = true
                            result = null
                            resultVerified = false
                            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                                .withZone(ZoneOffset.UTC).format(Instant.now())
                            save.launch("chunks-phone-inventory-$stamp.chunksnap")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) {
                    Text(if (saving) "Saving phone inventory…" else "Choose file for phone inventory")
                }
                result?.let {
                    Text(it, color = if (resultVerified) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
