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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddSource
import io.github.gamesbyian.chunks.model.UniversalAddTarget
import io.github.gamesbyian.chunks.model.UniversalAddPlace
import io.github.gamesbyian.chunks.model.UniversalAddIntent
import io.github.gamesbyian.chunks.model.EnvironmentCapabilities
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteAddOutcome
import io.github.gamesbyian.chunks.rpc.RemoteUniversalAddAdapter
import io.github.gamesbyian.chunks.rpc.RemotePreWriteRefusal
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
 * Metainfo and attempted outcomes stay in the parent session's process memory only.
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
    onVerifiedAdd: (String) -> Unit = {},
    /** Parent-owned choice shared with phone Add; no extra Save/Apply interaction. */
    addStartState: androidx.compose.runtime.MutableState<Boolean>? = null,
    addArtworkState: androidx.compose.runtime.MutableState<Boolean>? = null,
    /** Parent-owned exact-account review; remounting must not revive Add authority. */
    session: RemoteAddSession? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = session ?: remember(remote, finishInOrderScope) { RemoteAddSession(finishInOrderScope) }
    require(draft.accountScope == finishInOrderScope) { "Add review belongs to another server account" }
    var selectedTorrent by draft::selectedTorrent
    var selectedBytes by draft::selectedBytes
    var useFile by draft::useFile
    var choosing by remember { mutableStateOf(false) }
    // Confirmation is deliberately local: return requires a new Review click.
    var awaitingConfirmation by remember { mutableStateOf(false) }
    var submitting by draft::submitting
    var outcome by draft::outcome
    var uncertain by draft::uncertain
    var alreadyAttempted by draft::alreadyAttempted
    var verifiedHash by draft::verifiedHash
    // A newer Android share/phone edit cannot change the displayed recovery identity.
    val sourceText = draft.attemptSource ?: magnet
    val currentSource = rememberUpdatedState(magnet)
    DisposableEffect(draft) {
        onDispose {
            if (draft.alreadyAttempted && draft.reportedVerifiedHash == null)
                draft.resetArtworkAfterVerification = false
        }
    }
    LaunchedEffect(sourceText, destination, selectedBytes, useFile) {
        awaitingConfirmation = false
    }
    LaunchedEffect(magnet, alreadyAttempted) {
        // Phone and remote text fields share the parent source. A text edit
        // while this form was absent selects that newer source on return.
        // An explicit incoming document takes precedence in its own review.
        if (!alreadyAttempted && initialTorrent == null && useFile &&
            draft.textAtFileSelection != magnet) draft.preferMagnet()
    }
    val reviewKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val reviewFocus = androidx.compose.ui.platform.LocalFocusManager.current
    // Freeze the command actually submitted: mutable checkbox values do not
    // authorize treating an already-started Add as a stopped Add on recheck.
    // The only submitted intent is Agent 1's shared ticket, retained by
    // the exact-account session above navigation and reauthentication.
    val attemptedTicket = draft.attemptTicket
    var observedProgress by draft::observedProgress
    var progressReadFailed by remember { mutableStateOf(false) }
    var progressRevision by remember { mutableStateOf(0) }
    LaunchedEffect(remote, attemptedTicket, outcome, progressRevision) {
        val ticket = attemptedTicket ?: return@LaunchedEffect
        // A freshly authorized ticket is not yet proof that any Add RPC was
        // attempted. Source-policy or artwork opt-out can still refuse it
        // before the wire. Do not probe remote job progress until the engine
        // adapter has returned a result, including an ambiguous result.
        if (outcome == null || !draft.alreadyAttempted) return@LaunchedEffect
        // A session-owned prior read is historical until this exact screen/account
        // obtains another positive result. Never show cached bytes as current
        // during the initial request or a reconnect.
        progressReadFailed = true
        pollRemoteAddReadOnlyProgress(
            read = { RemoteUniversalAddAdapter(remote).progress(ticket) },
            onFresh = { progress ->
                observedProgress = progress
                progressReadFailed = false
            },
            onReadError = {
                // A transient GET failure must not end the bounded, read-only
                // observation loop while the independently registered job runs.
                // Never replay Add/Start or claim current payload from a stale sample.
                progressReadFailed = true
            }
        )
    }
    val defaultStartHolder = remember { mutableStateOf(true) }
    val startHolder = addStartState ?: defaultStartHolder
    var startAfterAdd by startHolder
    val artworkHolder = addArtworkState ?: rememberSaveable { mutableStateOf(true) }
    var automaticArtwork by artworkHolder
    val mediaController = io.github.gamesbyian.chunks.media.LocalTransferMediaController.current
    LaunchedEffect(verifiedHash, draft.coordinator.phase) {
        verifiedHash?.let {
            // Merely finding the exact job after a lost acknowledgement does
            // not prove Chunks registered it. Only a positively verified NEW
            // Add may be reported to the parent as the app's latest Added job.
            if (draft.coordinator.phase == UniversalAddCoordinator.Phase.VERIFIED &&
                draft.reportedVerifiedHash != it) {
                draft.reportedVerifiedHash = it
                onVerifiedAdd(it)
                if (draft.resetArtworkAfterVerification && magnet == draft.attemptSource && initialTorrent == null)
                    automaticArtwork = true
            }
        }
    }
    // Optional read-only shortcuts, keyed to the authenticated Add destination.
    // Never open another account's saved folder suggestions on a scope change.
    var showFolderShortcuts by rememberSaveable(finishInOrderScope, environmentLabel) {
        mutableStateOf(false)
    }
    val finishInOrderStore = remember(finishInOrderScope) { finishInOrderScope?.let { FinishInOrderStore(context, it) } }
    var finishInOrderDefault by remember(finishInOrderScope) { mutableStateOf(finishInOrderStore?.defaultForNewAdds() == true) }
    if (!draft.finishInOrderInitialized || draft.finishInOrderHash != selectedTorrent?.infoHashV1) {
        draft.finishInOrder.value = finishInOrderDefault
        draft.finishInOrderInitialized = true
        draft.finishInOrderHash = selectedTorrent?.infoHashV1
    }
    var finishInOrderAfterAdd by draft.finishInOrder
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

    var fromPhone by draft::fromPhone
    LaunchedEffect(initialTorrent, alreadyAttempted) {
        val document = initialTorrent ?: return@LaunchedEffect
        if (submitting || alreadyAttempted) return@LaunchedEffect
        onInitialTorrentConsumed()
        try {
            val bytes = document.copyOf()
            selectedTorrent = TorrentMetainfoReader.preview(bytes)
            selectedBytes = bytes
            draft.textAtFileSelection = magnet
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
                    draft.textAtFileSelection = magnet
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

    val magnetCheck = remember(sourceText) { MagnetDraftCheck.inspect(sourceText) }
    val linkCheck = remember(sourceText) { TorrentLinkCheck.inspect(sourceText) }
    var linkFetching by remember { mutableStateOf(false) }
    // Foreground clipboard prefill, once when this Add screen opens.
    var clipboardPrefillOn by remember { mutableStateOf<Boolean?>(null) }
    var clipboardFilled by remember { mutableStateOf<String?>(null) }
    // Android 10+ denies clipboard reads until the window has input focus; read once focused.
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    var clipboardChecked by remember { mutableStateOf(false) }
    LaunchedEffect(windowFocused) {
        if (!windowFocused || clipboardChecked) return@LaunchedEffect
        clipboardChecked = true
        val on = withContext(Dispatchers.IO) { ClipboardMagnetPrefill.enabled(context) }
        clipboardPrefillOn = on
        if (!on) return@LaunchedEffect
        val filled = ClipboardMagnetPrefill.decide(true, sourceText,
            otherSourcePresent = initialTorrent != null || selectedTorrent != null || alreadyAttempted,
            clipText = ClipboardMagnetPrefill.readText(context))
        if (filled != null) {
            onMagnet(filled)
            clipboardFilled = filled
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
            else (magnetCheck as? MagnetDraftCheck.Valid)?.normalized ?: sourceText
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
                value = sourceText,
                onValueChange = {
                    if (!alreadyAttempted) {
                        onMagnet(it)
                        useFile = false
                        awaitingConfirmation = false
                    }
                },
                label = { Text("Magnet or torrent link") },
                isError = !useFile && (if (linkCheck is TorrentLinkCheck.NotALink)
                    magnetCheck is MagnetDraftCheck.Problem else linkCheck is TorrentLinkCheck.Problem),
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
            if (sourceText.isBlank() && !alreadyAttempted) PasteMagnetButton(
                enabled = !submitting && !choosing,
                onPaste = {
                    onMagnet(it)
                    useFile = false
                    awaitingConfirmation = false
                },
                onNothingToPaste = { fileError = "The clipboard has no text to paste. Copy a magnet or torrent link first." })
            if (clipboardFilled != null && sourceText == clipboardFilled && !alreadyAttempted) ClipboardPrefillNote(
                onClear = { onMagnet(""); clipboardFilled = null }, enabled = !submitting)
            if (!useFile && linkCheck !is TorrentLinkCheck.NotALink) TorrentLinkPanel(linkCheck,
                fetching = linkFetching, enabled = !submitting && !choosing && !alreadyAttempted,
                onFetch = { link ->
                    val requestedSource = magnet
                    linkFetching = true
                    scope.launch {
                        try {
                            when (val result = withContext(Dispatchers.IO) { TorrentLinkFetcher().fetch(link) }) {
                                is TorrentLinkResult.Fetched -> {
                                    if (alreadyAttempted || submitting || currentSource.value != requestedSource) {
                                        fileError = "The draft changed while fetching. The current source was kept."
                                        return@launch
                                    }
                                    // Downloaded and validated on this phone; the server never
                                    // fetches the link, so a private token stays on the phone.
                                    selectedTorrent = result.preview
                                    selectedBytes = result.bytes
                                    draft.textAtFileSelection = ""
                                    fromPhone = false
                                    useFile = true
                                    fileError = null
                                    awaitingConfirmation = false
                                    clipboardFilled = null
                                    onMagnet("")
                                }
                                is TorrentLinkResult.Refused -> fileError = result.message
                            }
                        } finally {
                            linkFetching = false
                        }
                    }
                })
            else if (!useFile) MagnetDraftSummary(magnetCheck)
            clipboardPrefillOn?.let { on ->
                ClipboardPrefillToggle(checked = on, enabled = !submitting, onChange = { next ->
                    scope.launch {
                        if (withContext(Dispatchers.IO) { ClipboardMagnetPrefill.setEnabled(context, next) })
                            clipboardPrefillOn = next
                    }
                })
            }
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
        if (selectedTorrent != null && sourceText.isNotBlank()) {
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
                        onClick = { useFile = true; draft.textAtFileSelection = magnet; awaitingConfirmation = false },
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
        if (!alreadyAttempted) item {
            AddArtworkChoice(automaticArtwork, enabled = !submitting && !choosing) {
                automaticArtwork = it
                awaitingConfirmation = false
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
                                else TorrentMetainfoReader.magnetTrackerCount(sourceText)
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
                            // Both authorization and the one-shot ticket are acquired
                            // synchronously on this explicit Confirm click, before any
                            // suspending source, metadata or network operation.
                            val bytes = if (useFile) selectedBytes?.copyOf() else null
                            val requestedStart = startAfterAdd
                            val allowArtwork = automaticArtwork
                            val orderScope = finishInOrderScope?.takeIf {
                                bytes != null && requestedStart && finishInOrderAfterAdd &&
                                    (selectedTorrent?.fileCount ?: 0) >= 2
                            }
                            var mutationGateAcquired = false
                            val ticket = runCatching {
                                val reviewedSource = if (bytes != null)
                                    UniversalAddSource.metainfo(bytes, requireNotNull(selectedTorrent).infoHashV1)
                                else UniversalAddSource.magnet(plan.source, plan.expectedInfoHash)
                                val target = UniversalAddTarget.AuthenticatedServer(
                                    finishInOrderScope ?: remote.addAccountScope())
                                val reviewedPlace = if (plan.useServerDefault)
                                    UniversalAddPlace.ServerDefault(plan.destination)
                                else UniversalAddPlace.ServerCustom(plan.destination)
                                val intent = UniversalAddIntent(
                                    reviewedSource, target, reviewedPlace, requestedStart, allowArtwork)
                                val review = draft.coordinator.review(
                                    intent, EnvironmentCapabilities.unprobed(target.environment),
                                    EnvironmentAvailability.LIVE)
                                if (!review.accepted || !draft.coordinator.authorizeByUser()) null
                                else {
                                    mutationGateAcquired = onMutationStarted()
                                    if (mutationGateAcquired)
                                        draft.coordinator.beginSubmission(System.currentTimeMillis())
                                    else null
                                }
                            }.getOrNull()
                            if (ticket != null) {
                            awaitingConfirmation = false
                            submitting = true
                            alreadyAttempted = true
                            verifiedHash = null
                            // Clear the previous before-wire refusal BEFORE
                            // exposing the new ticket to Compose side effects.
                            outcome = null
                            observedProgress = null
                            draft.attemptTicket = ticket
                            draft.attemptSource = magnet
                            val artworkEnvironment = ticket.intent.target.environment
                            scope.launch {
                                try {
                                    if (!allowArtwork && withContext(Dispatchers.IO) {
                                        artworkEnvironment == null || mediaController?.prepareForAdd(
                                            io.github.gamesbyian.chunks.model.UnifiedTransferKey.of(
                                                artworkEnvironment, plan.expectedInfoHash), false) != true
                                    }) {
                                        // No RPC was sent. This known refusal can be corrected
                                        // without weakening the uncertain-command retry latch.
                                        draft.refusedBeforeAdd()
                                        outcome = "The artwork exclusion could not be saved. Nothing was added. Try again or change Artwork & metadata."
                                        return@launch
                                    }
                                    // No alternate RPC executor: the remote port invokes
                                    // the existing native/stopped-first engine operations.
                                    val adapter = RemoteUniversalAddAdapter(remote)
                                    // Give the callback its suspend type *before*
                                    // passing it through the optional parameter. A
                                    // nested let infers Function1 instead and fails
                                    // the Kotlin compile despite compatible bodies.
                                    val priorityCallback: (suspend (String) -> String?)? =
                                        if (orderScope == null) null
                                        else { hash: String ->
                                            enableRemoteFinishInOrderAfterAdd(
                                                context, remote, orderScope, hash)
                                        }
                                    // Record the evidence's acquisition order
                                    // before any suspend/RPC. Translating a late
                                    // response with the completion time could
                                    // make an older read overwrite a newer one.
                                    val submissionReadStartedAt = System.currentTimeMillis()
                                    val execution = adapter.submit(
                                        ticket, prioritiesBeforeStart = orderScope != null,
                                        onVerifiedStoppedBeforeStart = priorityCallback)
                                    // A second Start in priority mode verifies active
                                    // state, but only this read-only pass verifies Place
                                    // after that second mutation as well.
                                    val (result, evidenceReadStartedAt) = if (orderScope != null &&
                                        execution.result is RemoteAddAndStart.Started) {
                                        val at = System.currentTimeMillis()
                                        adapter.reconcile(ticket) to at
                                    } else execution.result to submissionReadStartedAt
                                    val orderNote = execution.orderNote
                                    val existingWithoutWrite = result is RemoteAddAndStart.AddedStopped &&
                                        result.result is RemoteAddOutcome.AlreadyPresent
                                    if (existingWithoutWrite) draft.refusedBeforeAdd()
                                    else {
                                        draft.coordinator.requireReadback(ticket)
                                        // The existing engine's bounded exact-hash
                                        // getters have proved the command's result,
                                        // even for a job hidden from the main view.
                                        adapter.exactObservation(
                                            ticket, result, newRegistrationVerified = true,
                                            observedAtEpochMillis = evidenceReadStartedAt
                                        )?.let { exact ->
                                            draft.coordinator.reconcileExactSession(ticket, exact)
                                        }
                                    }
                                    uncertain = !existingWithoutWrite && result !is RemoteAddAndStart.Started &&
                                        (result !is RemoteAddAndStart.AddedStopped ||
                                            result.result !is RemoteAddOutcome.VerifiedStopped)
                                    // Both metainfo and magnets observe the same exact
                                    // submitted hash and Place; this performs reads only.
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
                                } catch (_: RemotePreWriteRefusal) {
                                    // The engine adapter proved no load was
                                    // sent. Release only this exact ticket;
                                    // a later attempt still needs a new click.
                                    draft.refusedBeforeAdd()
                                    verifiedHash = null
                                    uncertain = false
                                    outcome = "The server refused the Add preflight before any write. Reconnect or refresh and review again. Nothing was added."
                                } catch (cancel: kotlinx.coroutines.CancellationException) {
                                    draft.coordinator.requireReadback(ticket)
                                    uncertain = true
                                    outcome = "Add was interrupted; inspect the exact server state. No retry was sent."
                                    throw cancel
                                } catch (_: Exception) {
                                    draft.coordinator.requireReadback(ticket)
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
                                if (mutationGateAcquired) onMutationSettled()
                                awaitingConfirmation = false
                                outcome = "Add could not be authorized for this exact server. Refresh the connection and review again; nothing was submitted."
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
                            if (progressReadFailed) {
                                Text("Last observed (not current): " + progress.summary())
                            } else {
                                Text(progress.summary())
                                val total = progress.payloadSizeBytes
                                val verified = progress.payloadVerifiedBytes
                                if (total != null && verified != null)
                                    Text("Payload: ${Units.bytes(verified)} verified of ${Units.bytes(total)}")
                            }
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
        // Read-only recovery can prove the exact hash, Place and Start
        // without proving that Chunks originally registered the job. Never
        // present this as an ordinary newly verified Add.
        val exactSessionConfirmed =
            draft.coordinator.phase == UniversalAddCoordinator.Phase.SESSION_CONFIRMED
        val addedByThisAttempt =
            draft.coordinator.phase == UniversalAddCoordinator.Phase.VERIFIED
        val registeredStartUnverified =
            draft.coordinator.phase == UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED
        if (alreadyAttempted && (initialTorrent != null ||
                (magnet.isNotBlank() && magnet != draft.attemptSource))) item {
            Text(when {
                exactSessionConfirmed ->
                    "A new source is waiting. Acknowledge this confirmed server session before reviewing it."
                registeredStartUnverified ->
                    "A new source is waiting. The server confirms the prior job and Place, but not Start. Acknowledge that incomplete result first."
                addedByThisAttempt && !uncertain ->
                    "A new source is waiting. Choose Start another Add to review it."
                else ->
                    "A new source is waiting. Finish checking this Add before starting another draft."
            })
        }
        if (exactSessionConfirmed && verifiedHash != null) item {
            Text(
                "The server confirms the exact transfer, its destination and requested Start state. " +
                    "Chunks cannot prove which client originally registered it after the lost reply. " +
                    "Acknowledging this server state permits a separate new review; no Add or Start is resent.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (registeredStartUnverified) item {
            Text(
                "rTorrent confirms this exact transfer and its reviewed destination. " +
                    "Requested Start is still unverified; metadata or peers may be pending. " +
                    "Chunks cannot prove who registered the job after the lost reply. " +
                    "Acknowledging permits a fresh review without replaying Add or Start.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (registeredStartUnverified ||
            (verifiedHash != null &&
                (exactSessionConfirmed || (addedByThisAttempt && !uncertain)))) item {
            OutlinedButton(onClick = {
                val oldSource = draft.attemptSource
                // The single explicit click acknowledges a confirmed session
                // or finishes an attested Add. Unresolved write latches stay
                // fenced by RemoteAddSession.beginNextDraft().
                if (draft.beginNextDraft()) {
                    if (magnet == oldSource) onMagnet("")
                    onDestination("")
                    awaitingConfirmation = false
                    fileError = null
                }
            }, enabled = !submitting && !rechecking && !choosing && !linkFetching,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                Text(when {
                    registeredStartUnverified -> "Acknowledge registered job; Start unverified"
                    exactSessionConfirmed -> "Acknowledge session and start another Add"
                    else -> "Start another Add"
                })
            }
        }
        if (alreadyAttempted && attemptedTicket != null) {
            item {
                OutlinedButton(
                    onClick = {
                        rechecking = true
                        progressRevision++
                        val ticket = requireNotNull(draft.attemptTicket)
                        scope.launch {
                            try {
                                val adapter = RemoteUniversalAddAdapter(remote)
                                val reviewedPlan = adapter.planFor(ticket)
                                // Preserve read REQUEST order, never response
                                // completion order: late RPC A must not look
                                // fresher than a completed newer RPC B.
                                val readStartedAt = System.currentTimeMillis()
                                val checkedOutcome = adapter.reconcile(ticket)
                                if (ticket.intent.startWhenAdded) {
                                    // This original Start intent also covers priority-
                                    // before-Start, not only native load.start. The
                                    // selected server/route is rechecked before reads.
                                    val checked = checkedOutcome
                                    uncertain = checked !is RemoteAddAndStart.Started
                                    verifiedHash = verifiedAddNavigationHash(checked)
                                    outcome = if (checked is RemoteAddAndStart.Started) {
                                        if (!reviewedPlan.useServerDefault) {
                                            val saved = runCatching {
                                                onCustomPlaceVerified(reviewedPlan.destination)
                                            }.getOrDefault(false)
                                            if (!saved) "Server now confirms this exact hash, destination and active engine. Recent folder shortcut could not be saved."
                                            else "Server now confirms this exact hash, destination and active engine. Which client originally registered it remains unknown; no Start or Add was resent."
                                        } else "Server now confirms this exact hash, destination and active engine. No Start or Add was resent."
                                    } else "Read-only check did not confirm this exact hash is started AND active at the selected destination. It may be queued, stopped, missing or unavailable. Inspect current server state; no Add or Start was resent."
                                } else {
                                    val checked = (checkedOutcome as RemoteAddAndStart.AddedStopped).result
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
                                // Exact-session proof is distinct from
                                // provenance: after a lost acknowledgement the
                                // job may now be verified, but which client
                                // registered it remains unknown. This permits
                                // an explicit next draft without replaying Add.
                                val exact = adapter.exactObservation(
                                    ticket, checkedOutcome, newRegistrationVerified = false,
                                    observedAtEpochMillis = readStartedAt)
                                if (exact != null) {
                                    draft.coordinator.reconcileExactSession(ticket, exact)
                                } else {
                                    // A magnet may exist at the exact reviewed
                                    // Place before rTorrent can prove active
                                    // payload Start. Independently verify
                                    // identity+Place, never use a mere
                                    // progress.registered hint.
                                    val registered = if (checkedOutcome is RemoteAddAndStart.StartNotVerified)
                                        adapter.registeredJobWithUnverifiedStart(
                                            ticket, observedAtEpochMillis = readStartedAt) else null
                                    if (registered != null) {
                                        draft.coordinator.reconcileExactSession(ticket, registered)
                                        if (draft.coordinator.phase ==
                                            UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED)
                                            outcome = "The exact server job and Place are confirmed, but requested Start is unverified. You may explicitly acknowledge this incomplete result; no Add or Start was resent."
                                    } else {
                                        // A newer authenticated exact-job read
                                        // can contradict an unacknowledged
                                        // positive CTA. Catalog absence, stale
                                        // status and read errors cannot.
                                        val earlier = draft.coordinator.phase
                                        if (shouldProbeContradictoryRecheck(earlier)) {
                                            // This timestamp precedes this
                                            // individual RPC read, so delayed
                                            // completions retain true read order.
                                            val witnessReadStartedAt = System.currentTimeMillis()
                                            val witness = adapter.contradictoryExactSession(
                                                ticket, observedAtEpochMillis = witnessReadStartedAt)
                                            if (witness != null) {
                                                draft.coordinator.reconcileExactSession(ticket, witness)
                                                if (draft.coordinator.phase != earlier) {
                                                    verifiedHash = null
                                                    uncertain = true
                                                    outcome = if (!witness.registered)
                                                        "A newer authenticated exact-job read confirms this hash is absent from the reviewed server. The earlier session acknowledgment is no longer available. No Add or Start was resent."
                                                    else
                                                        "A newer authenticated exact-job read found this hash at a different destination. The earlier session acknowledgment is no longer available. No Add or Start was resent."
                                                }
                                            } else draft.coordinator.requireReadback(ticket)
                                        } else draft.coordinator.requireReadback(ticket)
                                    }
                                }
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                draft.coordinator.requireReadback(ticket)
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

/**
 * Poll an exact remote Add ticket's read-only progress through transient failures.
 *
 * The caller supplies its already-frozen account, source, hash and Place read.
 * This helper owns no mutation, retry-of-write, transport or saved state.
 * A failed read never fabricates a negative server observation and never
 * promotes cached payload bytes to current status.
 */
internal suspend fun pollRemoteAddReadOnlyProgress(
    maxObservations: Int = 60,
    intervalMillis: Long = 2_000L,
    read: suspend () -> io.github.gamesbyian.chunks.rpc.RemoteAddProgress,
    onFresh: (io.github.gamesbyian.chunks.rpc.RemoteAddProgress) -> Unit,
    onReadError: () -> Unit
) {
    require(maxObservations in 1..60)
    require(intervalMillis >= 0L)
    repeat(maxObservations) {
        val status = try {
            read()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            onReadError()
            // An authentic gateway denial is not a transient poll failure.
            // Stop this effect rather than issuing repeated credential
            // challenges or pressuring a rate-limited account. The user can
            // explicitly reconnect or Recheck; no Add/Start write is retried.
            if (failure is io.github.gamesbyian.chunks.rpc.RpcHttpStatusException &&
                failure.status in setOf(401, 403, 407, 429)) return
            null
        }
        if (status != null) onFresh(status)
        kotlinx.coroutines.delay(intervalMillis)
    }
}

/** A negative exact-job witness is useful only to revoke a positive,
 * not-yet-acknowledged session readback. No source, account or RPC state here. */
internal fun shouldProbeContradictoryRecheck(
    phase: UniversalAddCoordinator.Phase
): Boolean = phase == UniversalAddCoordinator.Phase.SESSION_CONFIRMED ||
    phase == UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED
