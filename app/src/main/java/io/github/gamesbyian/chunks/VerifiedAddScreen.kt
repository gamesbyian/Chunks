package io.github.gamesbyian.chunks

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.Units
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
    /** Read-only paths observed on this exact current server; not a writable-folder probe. */
    observedPlaces: List<String> = emptyList(),
    /** Return whether the already-verified custom Place shortcut was persisted. */
    onCustomPlaceVerified: (String) -> Boolean = { true },
    /** A phone-prepared .torrent draft (transfer export or Android file); never auto-submitted. */
    initialTorrent: ByteArray? = null,
    onInitialTorrentConsumed: () -> Unit = {},
    /** Authenticated endpoint + account scope; null hides the Finish in Order option. */
    finishInOrderScope: String? = null,
    /** Exact server account and address this Add goes to, shown in the confirmation. */
    environmentLabel: String? = null,
    /** View only a positively verified hash in this authenticated server inventory. */
    onViewVerifiedTransfer: (String) -> Unit = {},
    /** Reports each positively verified Add hash once, so the list can explain a hidden result. */
    onVerifiedAdd: (String) -> Unit = {}
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
    var verifiedHash by remember(remote) { mutableStateOf<String?>(null) }
    val reviewKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val reviewFocus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(verifiedHash) { verifiedHash?.let(onVerifiedAdd) }
    // Freeze the command actually submitted: mutable checkbox values do not
    // authorize treating an already-started Add as a stopped Add on recheck.
    var nativeStartedAttempt by remember { mutableStateOf(false) }
    var reviewedAttempt by remember { mutableStateOf<StoppedRemoteAddPlan?>(null) }
    var submittedPlan by remember { mutableStateOf<StoppedRemoteAddPlan?>(null) }
    var observedProgress by remember { mutableStateOf<io.github.gamesbyian.chunks.rpc.RemoteAddProgress?>(null) }
    var progressReadFailed by remember { mutableStateOf(false) }
    var progressRevision by remember { mutableStateOf(0) }
    LaunchedEffect(remote, submittedPlan, progressRevision) {
        val target = submittedPlan ?: return@LaunchedEffect
        repeat(60) {
            try {
                observedProgress = remote.readAddProgress(target)
                progressReadFailed = false
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                progressReadFailed = true
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(2_000L)
        }
    }
    var startAfterAdd by remember { mutableStateOf(true) }
    // Optional read-only shortcuts, keyed to the authenticated Add destination.
    // Never open another account's saved folder suggestions on a scope change.
    var showFolderShortcuts by rememberSaveable(finishInOrderScope, environmentLabel) {
        mutableStateOf(false)
    }
    val finishInOrderStore = remember(finishInOrderScope) { finishInOrderScope?.let { FinishInOrderStore(context, it) } }
    var finishInOrderDefault by remember(finishInOrderScope) { mutableStateOf(finishInOrderStore?.defaultForNewAdds() == true) }
    var finishInOrderAfterAdd by remember(selectedTorrent?.infoHashV1) { mutableStateOf(finishInOrderDefault) }
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
            // Preserve an existing reviewed .torrent until the replacement
            // has been fully read and validated. An invalid provider document
            // must not erase the user's working Add draft.
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
                    fromPhone = false
                    useFile = true
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    fileError = "Could not open that .torrent (unsupported v1 metainfo or size limit)." +
                        if (selectedTorrent != null) " Your previous .torrent remains selected." else ""
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
                    RemoteAddScopeIntro(environmentLabel, finishInOrderScope)
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
                // Autocorrect must never rewrite a magnet's hash or trackers.
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                enabled = !submitting && !choosing && !alreadyAttempted,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )
        }
        item {
            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) },
                enabled = !choosing && !submitting && !alreadyAttempted,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
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
                        enabled = !submitting && !choosing && !alreadyAttempted,
                        onClick = { useFile = false; awaitingConfirmation = false },
                        label = { Text("Magnet") }
                    )
                    FilterChip(
                        selected = useFile,
                        enabled = !submitting && !choosing && !alreadyAttempted,
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
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                enabled = !submitting && !alreadyAttempted,
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 3
            )
            Text(when {
                destination.isNotBlank() ->
                    "Using a custom folder for this Add only. The server's configured default remains unchanged."
                !defaultReadComplete -> "Reading rTorrent's configured default Place…"
                configuredDefault != null ->
                    "Using rTorrent default Place: " + configuredDefault + ". No directory override will be sent."
                else ->
                    "No usable default Place was reported. Enter a custom absolute server folder before adding."
            }, style = MaterialTheme.typography.bodySmall)
            RemoteDefaultPlaceResetControl(
                show = destination.isNotBlank() && configuredDefault != null,
                enabled = !submitting && !alreadyAttempted,
                onReset = {
                    onDestination("")
                    // Re-review the resolved server default; no server mutation.
                    awaitingConfirmation = false
                }
            )
        }
        // Existing choices are suggestions, never permission/write probes. Keep
        // the effective default and manually editable custom Place above.
        // Disclosing or hiding a list must not invalidate an Add review or
        // trigger a new server command; selecting a path invalidates review.
        if (!alreadyAttempted && (recentPlaces.isNotEmpty() || observedPlaces.isNotEmpty())) {
            item {
                OutlinedButton(
                    onClick = { showFolderShortcuts = !showFolderShortcuts },
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics {
                            stateDescription = if (showFolderShortcuts) "Expanded" else "Collapsed"
                        }
                ) {
                    val recentCount = recentPlaces.size
                    val observedCount = observedPlaces.take(8).size
                    Text(
                        (if (showFolderShortcuts) "Hide" else "Choose") +
                            " server folder suggestions · $recentCount recent · $observedCount observed"
                    )
                }
            }
            if (showFolderShortcuts) {
                if (recentPlaces.isNotEmpty()) {
                    item {
                        RecentRemotePlaces(recentPlaces, enabled = !submitting) { parent ->
                            onDestination(parent)
                            awaitingConfirmation = false
                            showFolderShortcuts = false
                        }
                    }
                }
                if (observedPlaces.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Folders seen on this server", fontWeight = FontWeight.Bold)
                            Text("Choose a folder used by an existing transfer. These are server-reported " +
                                "suggestions, not a filesystem browser or proof that new files can be saved there.",
                                style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                observedPlaces.take(8).forEach { parent ->
                                    FilterChip(
                                        selected = destination == parent,
                                        enabled = !submitting,
                                        onClick = {
                                            onDestination(parent)
                                            awaitingConfirmation = false
                                            showFolderShortcuts = false
                                        },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        label = { Text(parent) }
                                    )
                                }
                            }
                        }
                    }
                }
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
            // Only a .torrent shows its file list before Add; magnets are excluded.
            val fioStore = finishInOrderStore
            if (fioStore != null && useFile && (selectedTorrent?.fileCount ?: 0) >= 2) item {
                FinishInOrderAddChoice(
                    "Finish in Order: the first episode or numbered file gets High, then the next " +
                        "(set before downloading starts; advances while Chunks is open and connected)",
                    checked = finishInOrderAfterAdd, enabled = !submitting,
                    onChecked = { finishInOrderAfterAdd = it; awaitingConfirmation = false },
                    defaultOn = finishInOrderDefault,
                    onDefault = { on -> if (fioStore.setDefaultForNewAdds(on)) finishInOrderDefault = on })
            }
            if (!awaitingConfirmation) {
                item {
                    Button(
                        onClick = {
                            // The review replaces the form; close the keyboard first.
                            reviewKeyboard?.hide()
                            reviewFocus.clearFocus()
                            awaitingConfirmation = true
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        enabled = !submitting && !choosing
                    ) { Text("Review Add") }
                }
            } else {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Confirm one server operation", fontWeight = FontWeight.Bold)
                            environmentLabel?.let { EnvironmentBadge("Adding to: $it", server = true,
                                // finishInOrderScope is the authenticated endpoint + NUL + username.
                                environment = finishInOrderScope?.let {
                                    io.github.gamesbyian.chunks.model.EnvironmentSnapshots.serverIdOfAliasScope(it)
                                }) }
                            Text("Source: " + if (useFile) "Selected .torrent file" else "Magnet link")
                            Text("Infohash: " + plan.expectedInfoHash)
                            Text("Destination: " + plan.destination + if (plan.useServerDefault)
                                " (server default; no directory override)" else " (custom parent folder)")
                            Text(if (startAfterAdd && !(useFile && finishInOrderAfterAdd &&
                                    (selectedTorrent?.fileCount ?: 0) >= 2 && finishInOrderScope != null))
                                "Use rTorrent's native add-and-start command once, then verify the exact torrent identity, destination and active engine state. An uncertain response is never retried automatically."
                            else if (startAfterAdd)
                                "Add stopped, apply reviewed file priorities, then Start once. Verify each outcome; never retry an uncertain command automatically."
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
                            verifiedHash = null
                            reviewedAttempt = plan
                            val bytes = if (useFile) selectedBytes?.copyOf() else null
                            val orderScope = finishInOrderScope?.takeIf {
                                bytes != null && finishInOrderAfterAdd && (selectedTorrent?.fileCount ?: 0) >= 2 }
                            nativeStartedAttempt = startAfterAdd && orderScope == null
                            var orderNote: String? = null
                            scope.launch {
                                try {
                                    // ruTorrent's ordinary Add starts inside load.start or
                                    // load.raw_start. Use this native path for default
                                    // Start-after-Add, retaining stopped-first for
                                    // reviewed Finish in Order priorities.
                                    val result = if (startAfterAdd && orderScope == null) {
                                        if (bytes != null) remote.loadStartedTorrentBytes(
                                            bytes, plan.destination, plan.useServerDefault)
                                        else remote.loadStarted(plan)
                                    } else RemoteAddStarter.run(
                                        startAfterVerifiedAdd = startAfterAdd,
                                        add = {
                                            val added = if (bytes != null) remote.loadStoppedTorrentBytes(
                                                bytes, plan.destination, plan.useServerDefault)
                                            else remote.loadStopped(plan)
                                            // Priorities before any Start, only for a verified stopped Add.
                                            if (orderScope != null && added is RemoteAddOutcome.VerifiedStopped)
                                                orderNote = enableRemoteFinishInOrderAfterAdd(
                                                    context, remote, orderScope, added.hash)
                                            added
                                        },
                                        start = { hash -> remote.setStarted(hash, true) }
                                    )
                                    uncertain = result !is RemoteAddAndStart.Started &&
                                        (result !is RemoteAddAndStart.AddedStopped ||
                                            result.result !is RemoteAddOutcome.VerifiedStopped)
                                    if (bytes == null) submittedPlan = plan
                                    verifiedHash = verifiedAddNavigationHash(result)
                                    outcome = when (result) {
                                        is RemoteAddAndStart.Started ->
                                            "Verified: added and started on rTorrent. Peer availability and payload bytes still need observation in Transfers."
                                        is RemoteAddAndStart.StartNotVerified ->
                                            "Start after Add was not verified. The torrent may already be registered or started; refresh its exact server state before another action. No automatic retry."
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
                                    orderNote?.let { outcome = outcome.orEmpty() + " " + it }
                                    if (!plan.useServerDefault &&
                                        verifiedCustomPlaceOutcome(result, priorityBeforeStart = orderScope != null)) {
                                        val saved = runCatching {
                                            onCustomPlaceVerified(plan.destination)
                                        }.getOrDefault(false)
                                        if (!saved) outcome = outcome.orEmpty() +
                                            " This folder was used but its Recent shortcut could not be saved."
                                    }
                                } catch (_: Exception) {
                                    verifiedHash = null
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
                        enabled = !submitting && !choosing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    ) { Text(if (startAfterAdd) "Confirm: add then start" else "Confirm: add stopped") }
                }
                item {
                    OutlinedButton(
                        onClick = { awaitingConfirmation = false },
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
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
                        observedProgress?.let { progress ->
                            Text(progress.summary())
                            val total = progress.payloadSizeBytes
                            val verified = progress.payloadVerifiedBytes
                            if (total != null && verified != null)
                                Text("Payload: ${Units.bytes(verified)} verified of ${Units.bytes(total)}")
                        }
                        if (progressReadFailed) Text(
                            "Transfer status could not be refreshed. Check the server state before another action.")
                    }
                }
            }
            verifiedHash?.let { hash ->
                item {
                    OutlinedButton(
                        onClick = { onViewVerifiedTransfer(hash) },
                        enabled = !submitting && !rechecking,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                    ) { Text("View verified transfer in rTorrent") }
                }
            }
        }
        if (alreadyAttempted && reviewedAttempt != null) {
            item {
                OutlinedButton(
                    onClick = {
                        rechecking = true
                        progressRevision++
                        val reviewedPlan = requireNotNull(reviewedAttempt)
                        scope.launch {
                            try {
                                if (nativeStartedAttempt) {
                                    // A native load may have applied before the gateway
                                    // lost its reply. Verify the original started intent
                                    // with exact hash, Place and ACTIVE engine, read only.
                                    val checked = remote.reconcileStartedAdd(reviewedPlan)
                                    uncertain = checked !is RemoteAddAndStart.Started
                                    verifiedHash = verifiedAddNavigationHash(checked)
                                    outcome = if (checked is RemoteAddAndStart.Started) {
                                        if (!reviewedPlan.useServerDefault) {
                                            val saved = runCatching {
                                                onCustomPlaceVerified(reviewedPlan.destination)
                                            }.getOrDefault(false)
                                            if (!saved) "Server now confirms this exact hash, destination and active engine. Recent folder shortcut could not be saved."
                                            else "Server now confirms this exact hash, destination and active engine. No Start or Add was resent."
                                        } else "Server now confirms this exact hash, destination and active engine. No Start or Add was resent."
                                    } else "Read-only check did not confirm this exact hash is started AND active at the selected destination. It may be queued, stopped, missing or unavailable. Inspect current server state; no Add or Start was resent."
                                } else {
                                    val checked = remote.reconcileStoppedAdd(reviewedPlan)
                                    uncertain = checked !is RemoteAddOutcome.VerifiedStopped
                                    verifiedHash = (checked as? RemoteAddOutcome.VerifiedStopped)?.hash
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
                                }
                            } catch (_: Exception) {
                                verifiedHash = null
                                uncertain = true
                                outcome = "Could not read server state. The earlier Add might have applied; inspect the transfer list before retrying."
                            } finally {
                                rechecking = false
                                onComplete()
                            }
                        }
                    },
                    enabled = !submitting && !rechecking,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                ) {
                    Text(if (rechecking) "Checking server…" else "Check server state (read only)")
                }
            }
        }
        item {
            OutlinedButton(onClick = onBack, enabled = !submitting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                Text("Back to transfers")
            }
        }
    }
}

/** Never navigate as verified from an ambiguous Add/Start acknowledgement. */
internal fun verifiedAddNavigationHash(result: RemoteAddAndStart): String? = when (result) {
    is RemoteAddAndStart.Started -> result.hash
    is RemoteAddAndStart.AddedStopped ->
        (result.result as? RemoteAddOutcome.VerifiedStopped)?.hash
    is RemoteAddAndStart.StartNotVerified -> null
}

/** Seedhost evidence: some servers silently discard torrents without a tracker. */
internal fun trackerlessWarning(isFile: Boolean): String =
    (if (isFile) "This .torrent lists no tracker. " else "This magnet link lists no tracker. ") +
        "Some servers, including the tested Seedhost, silently discard tracker-less torrents, so it may not " +
        "appear after Add. Chunks will then report it as unconfirmed, not added, and will not retry."


/**
 * Optional Add shortcuts must be discoverable on narrow phones and at 200%
 * font size without a horizontal-only gesture. Choosing a shortcut changes
 * only this Add draft, never the remote default or a file already on disk.
 */
@Composable
internal fun RecentRemotePlaces(
    paths: List<String>,
    enabled: Boolean,
    onChoose: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recent save folders", fontWeight = FontWeight.Bold)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            paths.forEach { parent ->
                OutlinedButton(
                    onClick = { onChoose(parent) },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { contentDescription = "Use recent server folder: $parent" }
                ) {
                    Text(parent, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 255.dp))
                }
            }
        }
        Text("These folders may have changed on the server. Chunks checks the " +
            "chosen folder again before reporting Add as verified.",
            style = MaterialTheme.typography.bodySmall)
    }
}


/**
 * Reset only the draft's one-off destination override, without changing the
 * server's default folder or performing any RPC. The parent screen must
 * invalidate an existing Add review after changing the draft.
 */
@Composable
internal fun RemoteDefaultPlaceResetControl(
    show: Boolean,
    enabled: Boolean,
    onReset: () -> Unit
) {
    if (show) TextButton(
        onClick = onReset, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp)
            .semantics { contentDescription = "Use rTorrent default save folder for this Add" }
    ) { Text("Reset to rTorrent default folder") }
}

/** Scope stays visible before someone chooses a magnet, file or destination. */
@Composable
internal fun RemoteAddScopeIntro(environmentLabel: String?, aliasScope: String?) {
    Text("Add a download to this server", style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Black)
    environmentLabel?.let { label ->
        EnvironmentBadge("Adding to: $label", server = true,
            environment = aliasScope?.let {
                io.github.gamesbyian.chunks.model.EnvironmentSnapshots.serverIdOfAliasScope(it)
            })
    }
    // The visible controls already say how to add and where; keep only the
    // facts a person could otherwise get wrong (where bytes go, who seeds).
    Text("Downloads to this server's own folder. Nothing is copied to this phone by Add. " +
        "Saved Chunks server-seeding rules are not applied yet; rTorrent controls seeding.",
        style = MaterialTheme.typography.bodySmall)
}

/**
 * Only truly verified Adds may create a trusted Recent Place shortcut.
 * Native start-on-load's uncertain receipt does not prove the destination.
 * Stopped-first, priority-before-start flow already verified exact Place
 * before its distinct Start attempt.
 */
internal fun verifiedCustomPlaceOutcome(
    result: RemoteAddAndStart,
    priorityBeforeStart: Boolean
): Boolean = when (result) {
    is RemoteAddAndStart.Started -> true
    is RemoteAddAndStart.StartNotVerified -> priorityBeforeStart
    is RemoteAddAndStart.AddedStopped ->
        result.result is RemoteAddOutcome.VerifiedStopped
}
