package io.github.gamesbyian.chunks

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteAddOutcome
import io.github.gamesbyian.chunks.rpc.RemoteAddStarter
import io.github.gamesbyian.chunks.rpc.RemoteAddAndStart
import io.github.gamesbyian.chunks.rpc.StoppedRemoteAddPlan
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoPreview
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An optional Place override and a two-step confirmation gate
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
    onMutationSettled: () -> Unit,
    recentPlaces: List<String> = emptyList(),
    /** Return whether the already-verified custom Place shortcut was persisted. */
    onCustomPlaceVerified: (String) -> Boolean = { true },
    /** A phone-prepared .torrent draft (transfer export or Android file); never auto-submitted. */
    initialTorrent: ByteArray? = null,
    onInitialTorrentConsumed: () -> Unit = {},
    /** Exact server account and address this Add goes to, shown in the confirmation. */
    environmentLabel: String? = null
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
    var startAfterAdd by remember { mutableStateOf(true) }
    var rechecking by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var configuredDefault by remember(remote) { mutableStateOf<String?>(null) }
    var defaultReadComplete by remember(remote) { mutableStateOf(false) }

    LaunchedEffect(remote) {
        configuredDefault = try {
            withContext(Dispatchers.IO) { remote.defaultDirectory() }
        } catch (_: Exception) {
            null
        }
        defaultReadComplete = true
    }

    var fromPhone by remember { mutableStateOf(false) }
    LaunchedEffect(initialTorrent) {
        val document = initialTorrent ?: return@LaunchedEffect
        onInitialTorrentConsumed()
        if (submitting || alreadyAttempted) return@LaunchedEffect
        try {
            val bytes = document.copyOf()
            selectedTorrent = TorrentMetainfoReader.preview(bytes)
            selectedBytes = bytes
            useFile = true
            fromPhone = true
            fileError = null
            awaitingConfirmation = false
        } catch (_: Exception) {
            fileError = "The phone transfer's torrent definition is not supported v1 metainfo."
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !choosing && !submitting && !alreadyAttempted) {
            choosing = true
            fromPhone = false
            selectedTorrent = null
            selectedBytes = null
            fileError = null
            awaitingConfirmation = false
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        // Share one fail-closed content:// reader with Android
                        // VIEW/SEND. The previous picker loop could spin forever
                        // on a broken provider that kept returning zero bytes.
                        val reviewed = SharedTorrentDocument.read(
                            context.contentResolver, uri)
                        reviewed.preview to reviewed.bytes
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
    val useServerDefault = destination.isBlank()
    val effectiveDestination = if (useServerDefault) configuredDefault.orEmpty() else destination
    var addGuidance = when {
        useServerDefault && !defaultReadComplete -> "Checking the server's configured download folder…"
        useServerDefault && configuredDefault == null ->
            "This server did not expose a usable default download folder. Enter an absolute server folder to proceed."
        else -> "Enter a valid v1 magnet or select a .torrent file. The Place override is optional."
    }
    val plan = try {
        if (useServerDefault && (!defaultReadComplete || configuredDefault == null)) null
        else {
            val link = if (useFile && selectedTorrent != null && selectedBytes != null)
                "magnet:?xt=urn:btih:" + selectedTorrent!!.infoHashV1
            else magnet
            if (useServerDefault) StoppedRemoteAddPlan.fromMagnetAtServerDefault(link, effectiveDestination)
            else StoppedRemoteAddPlan.fromMagnet(link, effectiveDestination)
        }
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
                    Text("Choose a magnet or .torrent. The configured server download folder is used unless you change it. Review the exact Place and whether to start after verification.")
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
                if (fromPhone) Text("Original .torrent prepared on this phone. Its " +
                    "tracker addresses may contain private passkeys, and the definition is " +
                    "sent to this server only when you confirm. The server downloads " +
                    "its own payload; no downloaded files are copied from the phone.",
                    style = MaterialTheme.typography.bodySmall)
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
                label = { Text("Custom server folder (optional)") },
                enabled = !submitting && !alreadyAttempted,
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 3
            )
            Text(when {
                destination.isNotBlank() ->
                    "Using custom Place. Clear this field to return to the server default; this choice does not change the server setting."
                !defaultReadComplete -> "Reading rTorrent's configured default Place…"
                configuredDefault != null ->
                    "Using rTorrent default Place: " + configuredDefault + ". No directory override will be sent."
                else ->
                    "No usable default Place was reported. Enter a custom absolute server folder before adding."
            }, style = MaterialTheme.typography.bodySmall)
        }
        if (!alreadyAttempted && recentPlaces.isNotEmpty()) {
            item {
                Text("Recently used custom folders", fontWeight = FontWeight.Bold)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    recentPlaces.forEach { parent ->
                        OutlinedButton(
                            onClick = {
                                onDestination(parent)
                                awaitingConfirmation = false
                            },
                            enabled = !submitting,
                            modifier = Modifier.height(48.dp)
                                .semantics { contentDescription = "Use recent server folder: $parent" }
                        ) {
                            Text(parent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 255.dp))
                        }
                    }
                }
                Text("Previously verified folders may have changed on the server. " +
                    "Chunks checks each Add again before reporting success.",
                    style = MaterialTheme.typography.bodySmall)
            }
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
            item {
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = startAfterAdd, enabled = !submitting, role = Role.Checkbox,
                        onValueChange = { startAfterAdd = it; awaitingConfirmation = false }
                    ).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = startAfterAdd, onCheckedChange = null)
                    Text("Start downloading after verified Add (recommended)",
                        modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (!awaitingConfirmation) {
                item {
                    Button(
                        onClick = { awaitingConfirmation = true },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        enabled = !submitting
                    ) { Text("Review Add") }
                }
            } else {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Confirm one server operation", fontWeight = FontWeight.Bold)
                            environmentLabel?.let { EnvironmentBadge("Adding to: $it", server = true) }
                            Text("Source: " + if (useFile) "Selected .torrent file" else "Magnet link")
                            Text("Infohash: " + plan.expectedInfoHash)
                            Text("Destination: " + plan.destination + if (plan.useServerDefault)
                                " (server default; no directory override)" else " (custom parent folder)")
                            Text(if (startAfterAdd)
                                "Step 1: Add stopped and verify exact hash and destination. Step 2: request Start once, and verify the server's running state. No retry if either result is uncertain."
                            else "Add stopped only; use Start later from Transfers. No file move or download is implied.")
                            val trackers = if (useFile) selectedTorrent?.trackerCount ?: 0
                                else TorrentMetainfoReader.magnetTrackerCount(magnet)
                            if (trackers == 0) {
                                // Red text on this green card measured 1.74:1; a dark inset banner keeps it readable.
                                ChunksBanner("No tracker", trackerlessWarning(useFile), ChunksTone.ERROR)
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
                                    val result = RemoteAddStarter.run(
                                        startAfterVerifiedAdd = startAfterAdd,
                                        add = {
                                            if (bytes != null) remote.loadStoppedTorrentBytes(
                                                bytes, plan.destination, plan.useServerDefault)
                                            else remote.loadStopped(plan)
                                        },
                                        start = { hash -> remote.setStarted(hash, true) }
                                    )
                                    uncertain = result !is RemoteAddAndStart.Started &&
                                        (result !is RemoteAddAndStart.AddedStopped ||
                                            result.result !is RemoteAddOutcome.VerifiedStopped)
                                    outcome = when (result) {
                                        is RemoteAddAndStart.Started ->
                                            "Verified: added and started on rTorrent. Peer availability and payload bytes still need observation in Transfers."
                                        is RemoteAddAndStart.StartNotVerified ->
                                            "Added and verified stopped. Start was requested but not verified; check server state before another Start. No automatic retry."
                                        is RemoteAddAndStart.AddedStopped -> when (result.result) {
                                            is RemoteAddOutcome.VerifiedStopped ->
                                                "Verified: added stopped at " + result.result.destination +
                                                    ". Start from Transfers whenever you're ready."
                                            is RemoteAddOutcome.AlreadyPresent ->
                                                "Already present. No new Add or Start was sent. Inspect the existing transfer."
                                            is RemoteAddOutcome.PendingVerification ->
                                                "The Add was not fully verified; no Start was sent. Inspect server state before retrying."
                                            is RemoteAddOutcome.Uncertain ->
                                                "Add uncertain; no Start was sent. Check the server before another operation."
                                        }
                                    }
                                    if (!plan.useServerDefault &&
                                        (result is RemoteAddAndStart.Started ||
                                         result is RemoteAddAndStart.StartNotVerified ||
                                         (result is RemoteAddAndStart.AddedStopped &&
                                          result.result is RemoteAddOutcome.VerifiedStopped))) {
                                        val saved = runCatching {
                                            onCustomPlaceVerified(plan.destination)
                                        }.getOrDefault(false)
                                        if (!saved) outcome = outcome.orEmpty() +
                                            " This folder was used but its Recent shortcut could not be saved."
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
                    ) { Text(if (startAfterAdd) "Confirm: add then start" else "Confirm: add stopped") }
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
