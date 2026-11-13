package io.github.gamesbyian.chunks

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.semantics.heading
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
 * One explicit Add transfer click acquires the existing frozen coordinator ticket
 * before any RPC write; unexpected or uncertain outcomes never auto-retry.
 * Metainfo and attempted outcomes stay in the parent session's process memory only.
 */
@Composable
internal fun VerifiedAddScreen(
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
    /** The person's saved default Add folder for this exact account, if any. */
    savedDefaultPlace: String? = null,
    /** Saves (or with null clears) that per-account default. False when it could not be saved. */
    onSaveDefaultPlace: (String?) -> Boolean = { false },
    /** Read-only paths observed on this exact current server; not a writable-folder probe. */
    observedPlaces: List<String> = emptyList(),
    /** Return whether the already-verified custom Place shortcut was persisted. */
    onCustomPlaceVerified: (String) -> Boolean = { true },
    /** A local-prepared .torrent draft (transfer export or Android file); never auto-submitted. */
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
    /** Parent-owned choice shared with local Add; no extra Save/Apply interaction. */
    addStartState: androidx.compose.runtime.MutableState<Boolean>? = null,
    addArtworkState: androidx.compose.runtime.MutableState<Boolean>? = null,
    addAliasState: androidx.compose.runtime.MutableState<String>? = null,
    /** Saves only the confirmed new exact-account Chunks list alias. */
    onVerifiedAlias: (String, String) -> Boolean = { _, _ -> false },
    /** Parent-owned exact-account review; remounting must not revive Add authority. */
    session: RemoteAddSession? = null,
    /** Inline shared Add-to selector; never grants remote admission. */
    addDestinationPicker: (@Composable () -> Unit)? = null,
    externalSourceReading: Boolean = false,
    /** Enables deterministic delayed-preference tests without touching external Add or RPC. */
    clipboardEnabledReader: suspend (android.content.Context) -> Boolean = { context ->
        withContext(Dispatchers.IO) { ClipboardMagnetPrefill.enabled(context) }
    },
    clipboardTextReader: (android.content.Context) -> String? = { context ->
        ClipboardMagnetPrefill.readText(context)
    },
    /**
     * A .torrent the person explicitly chose here (picker or HTTPS intake) becomes the app's
     * ONE reviewed document, so another Add target reviews the same newest file.
     */
    onTorrentAdopted: (TorrentMetainfoPreview, ByteArray) -> Unit = { _, _ -> },
    /** Read-only injectable HTTPS intake for deterministic stalled-request UI tests. */
    fetchTorrentLink: suspend (TorrentLinkCheck.Fetchable) -> TorrentLinkResult = { link ->
        withContext(Dispatchers.IO) { TorrentLinkFetcher().fetch(link) }
    },
    /** Isolated Compose race tests supply actual foreground focus as a fixture; ordinary app uses Android window focus. */
    testForegroundClipboardFocus: Boolean? = null
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
    // A newer Android share/local edit cannot change the displayed recovery identity.
    val sourceText = draft.attemptSource ?: magnet
    val currentSource = rememberUpdatedState(magnet)
    val latestExternalSourceReading = rememberUpdatedState(externalSourceReading)
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
        // Local and remote text fields share the parent source. A text edit
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
    var progressReadFailed by remember(remote, attemptedTicket) { mutableStateOf(false) }
    var progressReading by remember(remote, attemptedTicket) { mutableStateOf(attemptedTicket != null) }
    var progressRevision by remember { mutableStateOf(0) }
    LaunchedEffect(remote, attemptedTicket, outcome, progressRevision) {
        val ticket = attemptedTicket ?: run { progressReading = false; return@LaunchedEffect }
        // A freshly authorized ticket is not yet proof that any Add RPC was
        // attempted. Source-policy or artwork opt-out can still refuse it
        // before the wire. Do not probe remote job progress until the engine
        // adapter has returned a result, including an ambiguous result.
        if (outcome == null || !draft.alreadyAttempted) {
            progressReading = false
            return@LaunchedEffect
        }
        // A session-owned prior read is historical until this exact screen/account
        // obtains another positive result. Never show cached bytes as current
        // during the initial request or a reconnect.
        progressReading = true
        progressReadFailed = false
        pollRemoteAddReadOnlyProgress(
            read = { RemoteUniversalAddAdapter(remote).progress(ticket) },
            onFresh = { progress ->
                observedProgress = progress
                progressReading = false
                progressReadFailed = false
            },
            onReadError = {
                // A transient GET failure must not end the bounded, read-only
                // observation loop while the independently registered job runs.
                // Never replay Add/Start or claim current payload from a stale sample.
                progressReadFailed = true
                progressReading = false
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
                val submittedHash = draft.attemptTicket?.intent?.source?.expectedInfoHash
                val verifiedAlias = exactNewAddAlias(
                    draft.frozenAlias, submittedHash.orEmpty(), it)
                if (verifiedAlias != null) {
                    if (!runCatching { onVerifiedAlias(it, verifiedAlias) }.getOrDefault(false))
                        outcome = outcome.orEmpty() +
                        " The transfer was verified, but its Chunks display name could not be saved."
                    else addAliasState?.let { aliasDraft ->
                        if (aliasDraft.value == verifiedAlias) aliasDraft.value = ""
                    }
                }
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
    // Typed, read-only discovery of rTorrent's own default. A late reply only fills this
    // report; it never writes the person's per-Add folder or their saved default.
    var defaultReport by remember(remote) {
        mutableStateOf<io.github.gamesbyian.chunks.rpc.RTorrentRemote.DefaultDirectoryReport?>(null)
    }
    var defaultReadRevision by remember(remote) { mutableStateOf(0) }
    LaunchedEffect(remote, defaultReadRevision) {
        defaultReport = null
        defaultReport = withContext(Dispatchers.IO) { remote.defaultDirectoryReport() }
    }
    val configuredDefault = (defaultReport as?
        io.github.gamesbyian.chunks.rpc.RTorrentRemote.DefaultDirectoryReport.Absolute)?.path
    val defaultReadComplete = defaultReport != null

    var fromLocal by draft::fromLocal
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
            fromLocal = true
            fileError = null
            awaitingConfirmation = false
        } catch (_: Exception) {
            fileError = "The local transfer's torrent definition is not supported v1 metainfo."
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !choosing && !submitting && !alreadyAttempted) {
            choosing = true
            val sourceWitness = RemoteAddSourceReadWitness(currentSource.value, selectedBytes, useFile)
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
                    if (alreadyAttempted || submitting ||
                        !sourceWitness.stillSelected(currentSource.value, selectedBytes, useFile)) {
                        fileError = "The source changed while opening the .torrent. Your newer choice was kept."
                        return@launch
                    }
                    selectedTorrent = result.first
                    selectedBytes = result.second
                    // An explicitly opened .torrent is the newest source and replaces
                    // older typed text, exactly as local Add and HTTPS intake do. A
                    // lingering magnet would otherwise win a later Add-target switch.
                    draft.textAtFileSelection = ""
                    fromLocal = false
                    useFile = true
                    onMagnet("")
                    onTorrentAdopted(result.first, result.second)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    if (!alreadyAttempted && !submitting &&
                        sourceWitness.stillSelected(currentSource.value, selectedBytes, useFile)) {
                        fileError = "Could not open that .torrent (unsupported v1 metainfo or size limit)." +
                            if (selectedTorrent != null) " Your previous .torrent remains selected." else ""
                    }
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
    // Android 10+ denies clipboard reads until the window has input focus.
    // Preference loading suspends: a newer typed/shared source or selected
    // document must cancel the stale clipboard suggestion before it can write.
    val windowFocused = testForegroundClipboardFocus ?: androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    val clipboardBlocked = sourceText.isNotBlank() || initialTorrent != null ||
        selectedTorrent != null || useFile || alreadyAttempted || submitting || choosing
    val latestClipboardSource = rememberUpdatedState(sourceText)
    val latestClipboardBlocked = rememberUpdatedState(clipboardBlocked)
    var clipboardChecked by remember { mutableStateOf(false) }
    LaunchedEffect(windowFocused, sourceText, clipboardBlocked) {
        if (!windowFocused || clipboardChecked) return@LaunchedEffect
        clipboardChecked = true
        val on = clipboardEnabledReader(context)
        clipboardPrefillOn = on
        if (!on || latestClipboardBlocked.value) return@LaunchedEffect
        val filled = ClipboardMagnetPrefill.decide(true, latestClipboardSource.value,
            otherSourcePresent = latestClipboardBlocked.value,
            clipText = clipboardTextReader(context))
        if (filled != null && latestClipboardSource.value.isBlank() &&
            !latestClipboardBlocked.value) {
            onMagnet(filled)
            clipboardFilled = filled
        }
    }
    var invalidDestination = false
    // Precedence: this Add's folder > the person's saved default for this exact account >
    // rTorrent's reported absolute default. Never a guessed or fabricated path.
    val usingSavedDefault = destination.isBlank() && savedDefaultPlace != null
    val useServerDefault = destination.isBlank() && savedDefaultPlace == null
    val effectiveDestination = when {
        destination.isNotBlank() -> destination
        savedDefaultPlace != null -> savedDefaultPlace
        else -> configuredDefault.orEmpty()
    }
    val linkNeedsFetch = !useFile && sourceText.isNotBlank() &&
        TorrentLinkCheck.inspect(sourceText) !is TorrentLinkCheck.NotALink
    var addGuidance = when {
        sourceText.isBlank() && !useFile -> "Add a magnet or torrent link, or open a .torrent, to continue."
        linkNeedsFetch -> "Get the .torrent from this link first (Get torrent from link above)."
        else -> "Enter a valid v1 magnet or select a .torrent file. The Place override is optional."
    }
    val plan = try {
        run {
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
            Text("Add a transfer", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
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
                modifier = Modifier.fillMaxWidth().testTag(ChunksSemantics.ADD_SOURCE_FIELD),
                minLines = 2,
                maxLines = 4
            )
            if (!alreadyAttempted) PasteMagnetButton(
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
                fetching = linkFetching,
                enabled = !submitting && !choosing && !alreadyAttempted && !externalSourceReading,
                onFetch = fetch@{ link ->
                    // The button's composed URI may lag a newer text edit by
                    // one frame. Freeze the URL actually requested, including
                    // its signed query, and refuse the obsolete click *before*
                    // any network access rather than binding it to new text.
                    val sourceWitness = RemoteAddSourceReadWitness(
                        link.uri.toString(), selectedBytes, useFile)
                    if (latestExternalSourceReading.value ||
                        !sourceWitness.stillSelected(
                            currentSource.value.trim(), selectedBytes, useFile)) {
                        fileError = "The draft changed before fetching. Your newer source was kept."
                        return@fetch
                    }
                    linkFetching = true
                    scope.launch {
                        try {
                            when (val result = fetchTorrentLink(link)) {
                                is TorrentLinkResult.Fetched -> {
                                    if (alreadyAttempted || submitting || choosing ||
                                        latestExternalSourceReading.value ||
                                        !sourceWitness.stillSelected(
                                            currentSource.value.trim(), selectedBytes, useFile)) {
                                        fileError = "The draft changed while fetching. The current source was kept."
                                        return@launch
                                    }
                                    // Downloaded and validated on this device; the server never
                                    // fetches the link, so a private token stays on the local device.
                                    selectedTorrent = result.preview
                                    selectedBytes = result.bytes
                                    draft.textAtFileSelection = ""
                                    fromLocal = false
                                    useFile = true
                                    fileError = null
                                    awaitingConfirmation = false
                                    clipboardFilled = null
                                    onMagnet("")
                                    onTorrentAdopted(result.preview, result.bytes)
                                }
                                is TorrentLinkResult.Refused -> {
                                    // A refused old URL must not pollute a newer
                                    // explicitly selected source with its error.
                                    if (!alreadyAttempted && !submitting && !choosing &&
                                        !latestExternalSourceReading.value &&
                                        sourceWitness.stillSelected(
                                            currentSource.value.trim(), selectedBytes, useFile))
                                        fileError = result.message
                                }
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
                Text(if (choosing) "Reading torrent…" else "Open .torrent")
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
                if (fromLocal) Text("Original .torrent prepared on this device. Its " +
                    "tracker addresses may contain private passkeys, and the definition is " +
                    "sent to this server only when you confirm. The server downloads " +
                    "its own payload; no downloaded files are copied from the local device.",
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
        if (!alreadyAttempted && addDestinationPicker != null) item {
            addDestinationPicker()
        }
        // The same position owns the target and subsequent operation status.
        // A result replaces the intro rather than creating a second status card.
        if (outcome == null) item {
            if (submitting) Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Adding to " + (draft.submittedDisplayName ?: environmentLabel ?: "rTorrent"),
                        fontWeight = FontWeight.Bold)
                    Text("Submitting and checking the exact transfer. Do not submit again.")
                }
            } else RemoteAddScopeIntro(environmentLabel, finishInOrderScope)
        }
        if (outcome != null) {
            item {
                // A fresh active metadata session is ordinary progress. The
                // native payload Place still needs read-only confirmation,
                // so retain the uncertain-write fence without presenting a
                // healthy metadata wait as a storage/Start failure.
                val nativePlaceAwaitingConfirmation = uncertain &&
                    attemptedTicket?.intent?.startWhenAdded == true &&
                    attemptedTicket?.intent?.place is UniversalAddPlace.ServerDefault &&
                    (attemptedTicket?.intent?.place as? UniversalAddPlace.ServerDefault)
                        ?.observedDefaultPath?.isEmpty() == true &&
                    observedProgress?.registered == true && observedProgress?.started == true &&
                    observedProgress?.traffic?.active == true &&
                    observedProgress?.metadataPending != null && !progressReading && !progressReadFailed
                Card(modifier = Modifier.testTag(ChunksSemantics.ADD_REMOTE_OPERATION_STATUS),
                    colors = CardDefaults.cardColors(containerColor =
                        if (uncertain && !nativePlaceAwaitingConfirmation) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        val targetName = draft.submittedDisplayName ?: environmentLabel?.takeIf(String::isNotBlank) ?: "rTorrent"
                        Text(when {
                            nativePlaceAwaitingConfirmation -> "Checking $targetName"
                            uncertain -> "Couldn't confirm Add on $targetName"
                            draft.coordinator.phase == UniversalAddCoordinator.Phase.VERIFIED ->
                                "Added to $targetName"
                            draft.coordinator.phase == UniversalAddCoordinator.Phase.SESSION_CONFIRMED ->
                                "Confirmed on $targetName"
                            outcome.orEmpty().contains("refused", ignoreCase = true) ->
                                "Couldn't add to $targetName"
                            else -> "Add result on $targetName"
                        }, fontWeight = FontWeight.Bold)
                        Text(if (nativePlaceAwaitingConfirmation) {
                            if (observedProgress?.metadataPending == true)
                                "Waiting for magnet metadata. rTorrent reports this exact transfer active; its payload folder and final Add result are not yet verified. No Add or Start will be repeated."
                            else "Metadata is available. Check server state below to confirm the actual payload folder and requested Start. No Add or Start will be repeated."
                        } else outcome.orEmpty())
                        observedProgress?.let { progress ->
                            if (progressReading || progressReadFailed) {
                                Text("Last observed (not current): " + progress.summary())
                            } else {
                                Text(progress.summary())
                                val total = progress.payloadSizeBytes
                                val verified = progress.payloadVerifiedBytes
                                if (total != null && verified != null)
                                    Text("Payload: ${Units.bytes(verified)} verified of ${Units.bytes(total)}")
                            }
                        }
                        if (progressReading) Text("Reading exact transfer status…")
                        else if (progressReadFailed) Text(
                            "Transfer status could not be refreshed. Check the server state before another action.")
                        verifiedHash?.let { hash ->
                            OutlinedButton(
                                onClick = { onViewVerifiedTransfer(hash) },
                                enabled = !submitting && !rechecking,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                            ) { Text("View transfer") }
                        }
                    }
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
                usingSavedDefault ->
                    "Using this server's saved default folder: " + savedDefaultPlace + "."
                !defaultReadComplete -> "Using the server default. Reading its folder; Add remains available."
                configuredDefault != null ->
                    "Using rTorrent default Place: " + configuredDefault + ". No directory override will be sent."
                else ->
                    "Using the server default without a folder override. " + remoteDefaultUnavailableReason(defaultReport) +
                        " The actual folder must be confirmed after Add."
            }, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(ChunksSemantics.ADD_REMOTE_SAVE_TO))
            if (destination.isBlank() && savedDefaultPlace == null && defaultReport is
                io.github.gamesbyian.chunks.rpc.RTorrentRemote.DefaultDirectoryReport.Failed) TextButton(
                onClick = { defaultReadRevision++ }, enabled = !submitting && !alreadyAttempted,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Check the server's default folder again") }
            RemoteDefaultPlaceResetControl(
                show = destination.isNotBlank() && (configuredDefault != null || savedDefaultPlace != null),
                enabled = !submitting && !alreadyAttempted,
                label = if (savedDefaultPlace != null) "Use this server's saved default folder"
                    else "Reset to rTorrent default folder",
                onReset = {
                    onDestination("")
                    // Re-review the resolved server default; no server mutation.
                    awaitingConfirmation = false
                }
            )
            val typedValid = destination.isNotBlank() && destination != savedDefaultPlace &&
                RecentRemotePlaceStore.isValidParent(destination)
            var defaultSaveProblem by remember { mutableStateOf<String?>(null) }
            if (typedValid && !alreadyAttempted) TextButton(
                onClick = {
                    defaultSaveProblem = if (onSaveDefaultPlace(destination)) null
                        else "That folder could not be saved as this server's default."
                    if (defaultSaveProblem == null) { onDestination(""); awaitingConfirmation = false }
                }, enabled = !submitting,
                modifier = Modifier.heightIn(min = 48.dp).testTag(ChunksSemantics.ADD_REMOTE_SAVE_DEFAULT)
            ) { Text("Always use this folder on this server") }
            if (usingSavedDefault && !alreadyAttempted) TextButton(
                onClick = {
                    defaultSaveProblem = if (onSaveDefaultPlace(null)) null
                        else "The saved default folder could not be cleared."
                }, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("Stop using a saved default folder") }
            defaultSaveProblem?.let { Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall) }
        }

        if (!alreadyAttempted && plan != null) item {
            Text("Infohash: " + plan.expectedInfoHash, style = MaterialTheme.typography.bodySmall)
            Text("Destination: " + when {
                plan.destination.isEmpty() -> "Server default; actual folder checked after Add."
                plan.useServerDefault -> plan.destination + " (server default; no directory override)"
                else -> plan.destination + " (custom parent folder)"
            }, style = MaterialTheme.typography.bodySmall)
            // Restored from the former confirmation card: the one-click Add must still warn,
            // before submission, that a trackerless source can only find peers by DHT/web seeds.
            val trackers = if (useFile) selectedTorrent?.trackerCount ?: 0
                else TorrentMetainfoReader.magnetTrackerCount(sourceText)
            if (trackers == 0) ChunksBanner("No tracker", trackerlessWarning(useFile), ChunksTone.ERROR)
        }
        if (!alreadyAttempted) item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag(ChunksSemantics.ADD_START_PREFERENCE).toggleable(
                    value = startAfterAdd, enabled = !submitting && !choosing,
                    role = Role.Checkbox,
                    onValueChange = { startAfterAdd = it; awaitingConfirmation = false }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = startAfterAdd, onCheckedChange = null)
                Text("Start when added", modifier = Modifier.padding(start = 8.dp))
            }
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
        // The one Add action is ALWAYS present before an attempt. When it cannot be used yet it
        // is shown disabled with the exact unmet requirement, never silently omitted.
        if (!alreadyAttempted) {
            // Finish-in-order is eligible only for multi-file .torrent sources.
            val fioStore = finishInOrderStore
            if (plan != null && fioStore != null && useFile && (selectedTorrent?.fileCount ?: 0) >= 2) item {
                FinishInOrderAddChoice(
                    "Finish in Order: the first episode or numbered file gets High, then the next " +
                        "(set before downloading starts; advances while Chunks is open and connected)",
                    checked = finishInOrderAfterAdd, enabled = !submitting,
                    onChecked = { finishInOrderAfterAdd = it; awaitingConfirmation = false },
                    defaultOn = finishInOrderDefault,
                    onDefault = { on -> if (fioStore.setDefaultForNewAdds(on)) finishInOrderDefault = on })
            }
                item {
                    Button(
                        onClick = addClick@{
                            if (externalSourceReading) return@addClick
                            val plan = plan ?: return@addClick
                            // Both authorization and the one-shot ticket are acquired
                            // synchronously on the explicit Add click, before any
                            // suspending source, metadata or network operation.
                            val aliasRaw = addAliasState?.value.orEmpty()
                            if (!validUniversalAddAlias(aliasRaw)) return@addClick
                            val aliasAtClick = normalizedUniversalAddAlias(aliasRaw)
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
                            draft.submittedDisplayName = environmentLabel?.trim()?.takeIf(String::isNotBlank)
                            draft.attemptSource = magnet
                            draft.frozenAlias = aliasAtClick
                            val artworkEnvironment = ticket.intent.target.environment
                            scope.launch {
                                var engineOutcomeRecorded = false
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
                                    engineOutcomeRecorded = true
                                    if (result is RemoteAddAndStart.StartNotVerified) {
                                        // One exact-hash READ-ONLY diagnosis, never a remedial Start/Add.
                                        // The inspector returns fixed categories, not private d.message text.
                                        try {
                                            val diagnosis = io.github.gamesbyian.chunks.rpc.RemoteStallInspector.inspect(
                                                remote, plan.expectedInfoHash)
                                            outcome = outcome.orEmpty() + " " + diagnosis.explanation()
                                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) { /* Keep the original uncertain result. */ }
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
                                    // Navigating away during the optional read-only
                                    // diagnosis does not invalidate the engine result
                                    // already recorded above. Cancellation before
                                    // that point still requires uncertain recovery.
                                    if (!engineOutcomeRecorded) {
                                        draft.coordinator.requireReadback(ticket)
                                        uncertain = true
                                        outcome = "Add was interrupted; inspect the exact server state. No retry was sent."
                                    }
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
                        enabled = plan != null && !submitting && !choosing && !externalSourceReading &&
                            validUniversalAddAlias(addAliasState?.value.orEmpty()),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .testTag(ChunksSemantics.ADD_SUBMIT)
                            .semantics { if (plan == null) stateDescription = "Unavailable: " + addGuidance }
                    ) { Text("Add transfer") }
                    if (plan == null) Text(addGuidance,
                        color = if (invalidDestination) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp))
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
        // The Add coordinator owns acknowledgment eligibility, not an
        // incomplete screen-only hash after a read-only Recheck.
        if (canCloseRemoteAddReview(draft.coordinator.phase, verifiedHash, uncertain)) item {
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
                        val priorConfirmedHash = if (draft.coordinator.phase ==
                            UniversalAddCoordinator.Phase.SESSION_CONFIRMED)
                            verifiedHash else null
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
                                        val earlier = draft.coordinator.phase
                                        if (shouldProbeContradictoryRecheck(earlier)) {
                                            // Timestamp starts before the separate real read.
                                            // Delayed completions cannot masquerade as newer witnesses.
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
                                // A failed or incomplete read cannot revoke the last
                                // actual coordinator-confirmed session acknowledgment.
                                if (draft.attemptTicket === ticket && verifiedHash == null) {
                                    val retained = retainedAcknowledgedHash(
                                        verifiedHash, priorConfirmedHash, draft.coordinator.phase)
                                    if (retained != null) {
                                        verifiedHash = retained
                                        outcome = outcome.orEmpty() +
                                            " Previous exact-session confirmation is retained; " +
                                            "you may acknowledge it without resending Add or Start."
                                    }
                                }
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
 * Optional Add shortcuts must be discoverable on narrow locals and at 200%
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
    label: String = "Reset to rTorrent default folder",
    onReset: () -> Unit
) {
    if (show) TextButton(
        onClick = onReset, enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp)
            .semantics { contentDescription = if (label == "Reset to rTorrent default folder")
                "Use rTorrent default save folder for this Add"
                else "Use this server's saved default folder for this Add" }
    ) { Text(label) }
}

/** Explain an unknown preview without making the optional override mandatory. */
internal fun remoteDefaultUnavailableReason(
    report: io.github.gamesbyian.chunks.rpc.RTorrentRemote.DefaultDirectoryReport?
): String = when (report) {
    io.github.gamesbyian.chunks.rpc.RTorrentRemote.DefaultDirectoryReport.Unset ->
        "rTorrent has no default download folder configured; its native working directory will be used."
    io.github.gamesbyian.chunks.rpc.RTorrentRemote.DefaultDirectoryReport.NotAbsolute ->
        "rTorrent reports a relative default folder; its server working directory must also be observed."
    io.github.gamesbyian.chunks.rpc.RTorrentRemote.DefaultDirectoryReport.Unsupported ->
        "This rTorrent does not expose its default-folder getter."
    is io.github.gamesbyian.chunks.rpc.RTorrentRemote.DefaultDirectoryReport.Failed ->
        "The server's default folder could not be read (connection or permission). You can check again."
    else -> "The server's default folder has not yet been observed."
}

/** Scope stays visible before someone chooses a magnet, file or destination. */
@Composable
internal fun RemoteAddScopeIntro(environmentLabel: String?, aliasScope: String?) {
    // "Add a transfer" already titles the screen; the target is stated once, compactly.
    environmentLabel?.let { label ->
        EnvironmentBadge("Adding to: $label", server = true,
            environment = aliasScope?.let {
                io.github.gamesbyian.chunks.model.EnvironmentSnapshots.serverIdOfAliasScope(it)
            })
    }
    // Facts a person could get wrong (where bytes go, who seeds), available on request
    // rather than dominating the ordinary source → target → Save to → Start → Add flow.
    var showAbout by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { showAbout = !showAbout }, modifier = Modifier.heightIn(min = 48.dp)
        .semantics { stateDescription = if (showAbout) "Expanded" else "Collapsed" }) {
        Text(if (showAbout) "Hide details about adding to a server" else "About adding to a server")
    }
    if (showAbout) Text("Downloads to this server's own folder. Nothing is copied to this device by Add. " +
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

/** The coordinator's proven phase owns acknowledgment eligibility, even if
 * an incomplete read cannot refresh the presentation-only navigation hash. */
internal fun canCloseRemoteAddReview(
    phase: UniversalAddCoordinator.Phase,
    verifiedHash: String?,
    uncertain: Boolean
): Boolean = when (phase) {
    UniversalAddCoordinator.Phase.SESSION_CONFIRMED -> verifiedHash != null
    UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED -> true
    UniversalAddCoordinator.Phase.VERIFIED -> verifiedHash != null && !uncertain
    else -> false
}

/** Retain a previously confirmed exact-session hash only while the same
 * coordinator STILL owns that positive confirmation. */
internal fun retainedAcknowledgedHash(
    current: String?,
    previous: String?,
    phase: UniversalAddCoordinator.Phase
): String? = current ?: previous?.takeIf {
    phase == UniversalAddCoordinator.Phase.SESSION_CONFIRMED
}
