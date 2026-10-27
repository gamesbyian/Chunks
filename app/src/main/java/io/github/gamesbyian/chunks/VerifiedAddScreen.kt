package io.github.gamesbyian.chunks

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteAddOutcome
import io.github.gamesbyian.chunks.rpc.StoppedRemoteAddPlan
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoPreview
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A deliberate destination-first stopped Add. A two-step confirmation gates
 * any RPC write; unexpected or uncertain outcomes never auto-retry.
 * ByteArray from SAF stays in this screen's process memory only.
 */
@Composable
fun VerifiedAddScreen(
    remote: RTorrentRemote,
    magnet: String,
    destination: String,
    onMagnet: (String) -> Unit,
    onDestination: (String) -> Unit,
    onBack: () -> Unit,
    onComplete: () -> Unit,
    onMutationStarted: () -> Boolean,
    onMutationSettled: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTorrent by remember { mutableStateOf<TorrentMetainfoPreview?>(null) }
    var selectedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var useFile by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var awaitingConfirmation by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<String?>(null) }
    var uncertain by remember { mutableStateOf(false) }
    var alreadyAttempted by remember { mutableStateOf(false) }
    var rechecking by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !choosing && !submitting && !alreadyAttempted) {
            choosing = true
            selectedTorrent = null
            selectedBytes = null
            fileError = null
            awaitingConfirmation = false
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("The selected document cannot be opened")
                        input.use {
                            val accumulator = ByteArrayOutputStream()
                            val buffer = ByteArray(16_384)
                            while (true) {
                                val count = it.read(buffer)
                                if (count < 0) break
                                require(accumulator.size() + count <= TorrentMetainfoReader.MAX_BYTES) {
                                    "Torrent metadata exceeds 2 MiB"
                                }
                                accumulator.write(buffer, 0, count)
                            }
                            val bytes = accumulator.toByteArray()
                            TorrentMetainfoReader.preview(bytes) to bytes
                        }
                    }
                    selectedTorrent = result.first
                    selectedBytes = result.second
                    useFile = true
                } catch (e: Exception) {
                    fileError = "This document is not supported v1 .torrent metainfo or exceeds the safety limit."
                } finally {
                    choosing = false
                }
            }
        }
    }

    var invalidDestination = false
    var addGuidance = "Enter a valid v1 magnet or select a .torrent file, then provide an absolute server directory. Chunks cannot pre-verify server permissions."
    val plan = try {
        val link = if (useFile && selectedTorrent != null && selectedBytes != null)
            "magnet:?xt=urn:btih:" + selectedTorrent!!.infoHashV1
        else magnet
        StoppedRemoteAddPlan.fromMagnet(link, destination)
    } catch (failure: IllegalArgumentException) {
        // Match only our fixed validation messages, never display arbitrary
        // untrusted magnet input or server-provided exception text.
        addGuidance = when (failure.message) {
            "Choose a specific absolute server destination" -> {
                invalidDestination = true
                "Choose an absolute server folder starting with / (for example, /downloads)."
            }
            "Invalid server destination path" -> {
                invalidDestination = true
                "Invalid destination: remove repeated slashes, '.' and '..' path segments."
            }
            "Unsupported characters in rTorrent destination" -> {
                invalidDestination = true
                "Invalid destination: this folder path contains unsupported punctuation or control characters."
            }
            else -> addGuidance
        }
        null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A place for every download.", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black)
                    Text("Choose one magnet or .torrent, pick its server directory, and confirm a stopped Add. No torrent starts automatically.")
                }
            }
        }
        item {
            OutlinedTextField(
                value = magnet,
                onValueChange = {
                    if (!alreadyAttempted) {
                        onMagnet(it)
                        useFile = false
                        awaitingConfirmation = false
                    }
                },
                label = { Text("Magnet link") },
                enabled = !submitting && !alreadyAttempted,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )
        }
        item {
            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) },
                enabled = !choosing && !submitting && !alreadyAttempted,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Text(if (choosing) "Reading torrent…" else "Choose a .torrent from Android Files")
            }
        }
        if (selectedTorrent != null) {
            item {
                val preview = selectedTorrent!!
                Text(
                    preview.name + " · " + preview.fileCount + " files · " + preview.sizeBytes + " bytes" +
                        "\nInfohash: " + preview.infoHashV1,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        if (fileError != null) {
            item { Text(fileError.orEmpty(), color = MaterialTheme.colorScheme.error) }
        }
        if (selectedTorrent != null && magnet.isNotBlank()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !useFile,
                        enabled = !submitting && !alreadyAttempted,
                        onClick = { useFile = false; awaitingConfirmation = false },
                        label = { Text("Magnet") }
                    )
                    FilterChip(
                        selected = useFile,
                        enabled = !submitting && !alreadyAttempted,
                        onClick = { useFile = true; awaitingConfirmation = false },
                        label = { Text(".torrent file") }
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = destination,
                onValueChange = {
                    if (!alreadyAttempted) {
                        onDestination(it)
                        awaitingConfirmation = false
                    }
                },
                label = { Text("Absolute destination on rTorrent server") },
                enabled = !submitting && !alreadyAttempted,
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 3
            )
        }
        if (!alreadyAttempted && plan == null) {
            item {
                Text(addGuidance,
                    color = if (invalidDestination) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (plan != null && !alreadyAttempted) {
            if (!awaitingConfirmation) {
                item {
                    Button(
                        onClick = { awaitingConfirmation = true },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        enabled = !submitting
                    ) { Text("Review stopped Add") }
                }
            } else {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Confirm one server operation", fontWeight = FontWeight.Bold)
                            Text("Source: " + if (useFile) "Selected .torrent file" else "Magnet link")
                            Text("Infohash: " + plan.expectedInfoHash)
                            Text("Destination: " + plan.destination)
                            Text("Loads stopped. It does not move existing files or guarantee that bytes are downloaded.")
                            val trackers = if (useFile) selectedTorrent?.trackerCount ?: 0
                                else TorrentMetainfoReader.magnetTrackerCount(magnet)
                            if (trackers == 0) {
                                Text(trackerlessWarning(useFile), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = {
                            if (onMutationStarted()) {
                            awaitingConfirmation = false
                            submitting = true
                            alreadyAttempted = true
                            val bytes = if (useFile) selectedBytes?.copyOf() else null
                            scope.launch {
                                try {
                                    val response = if (bytes != null)
                                        remote.loadStoppedTorrentBytes(bytes, plan.destination)
                                    else remote.loadStopped(plan)
                                    uncertain = response !is RemoteAddOutcome.VerifiedStopped
                                    outcome = when (response) {
                                        is RemoteAddOutcome.VerifiedStopped ->
                                            "Verified: rTorrent reports this infohash stopped at " +
                                                response.destination + ". Data download is not yet proven."
                                        is RemoteAddOutcome.AlreadyPresent ->
                                            "Already present. No Add was sent. Check its existing directory before acting."
                                        is RemoteAddOutcome.PendingVerification ->
                                            "The server answered, but the new hash/destination/stopped state were not all verified. Refresh and inspect before retrying."
                                        is RemoteAddOutcome.Uncertain ->
                                            "Request outcome uncertain. It may have succeeded. Refresh by hash and do not blindly retry."
                                    }
                                } catch (_: Exception) {
                                    uncertain = true
                                    outcome = "Could not verify the operation. Inspect the current server transfer list before attempting another Add. No automatic retry occurred."
                                } finally {
                                    submitting = false
                                    // Also request readback after a timeout/exception.
                                    // The write might have happened without an RPC reply.
                                    onMutationSettled()
                                }
                            }
                            } else {
                                awaitingConfirmation = false
                                uncertain = true
                                outcome = "An earlier remote command needs a fresh server read before another Add."
                            }
                        },
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) { Text("Confirm: add stopped") }
                }
                item {
                    OutlinedButton(
                        onClick = { awaitingConfirmation = false },
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text("Cancel, don't add") }
                }
            }
        }
        if (outcome != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor =
                    if (uncertain) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Operation outcome", fontWeight = FontWeight.Bold)
                        Text(outcome.orEmpty())
                    }
                }
            }
        }
        if (alreadyAttempted && plan != null) {
            item {
                OutlinedButton(
                    onClick = {
                        rechecking = true
                        val reviewedPlan = plan
                        scope.launch {
                            try {
                                val checked = remote.reconcileStoppedAdd(reviewedPlan)
                                uncertain = checked !is RemoteAddOutcome.VerifiedStopped
                                outcome = when (checked) {
                                    is RemoteAddOutcome.VerifiedStopped ->
                                        "Read-only check verified this exact hash is stopped at " +
                                            checked.destination + ". Chunks cannot determine which client originally added it."
                                    is RemoteAddOutcome.PendingVerification ->
                                        "Read-only check did not verify the exact hash, destination and stopped state. Inspect the server before deciding what to do next."
                                    is RemoteAddOutcome.Uncertain ->
                                        "Read-only server check could not complete. The earlier Add may still have succeeded; do not retry blindly."
                                    is RemoteAddOutcome.AlreadyPresent ->
                                        "The hash exists, but placement still requires inspection before another action."
                                }
                            } catch (_: Exception) {
                                uncertain = true
                                outcome = "Could not read server state. The earlier Add might have applied; inspect the transfer list before retrying."
                            } finally {
                                rechecking = false
                                onComplete()
                            }
                        }
                    },
                    enabled = !submitting && !rechecking,
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    Text(if (rechecking) "Checking server…" else "Check server state (read only)")
                }
            }
        }
        item {
            OutlinedButton(onClick = onBack, enabled = !submitting,
                modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text("Back to transfers")
            }
        }
    }
}

/** Seedhost evidence: some servers silently discard torrents without a tracker. */
internal fun trackerlessWarning(isFile: Boolean): String =
    (if (isFile) "This .torrent lists no tracker. " else "This magnet link lists no tracker. ") +
        "Some servers, including the tested Seedhost, silently discard tracker-less torrents, so it may not " +
        "appear after Add. Chunks will then report it as unconfirmed, not added, and will not retry."
