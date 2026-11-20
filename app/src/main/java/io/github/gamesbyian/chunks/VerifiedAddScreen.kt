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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.Units
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddDisplayProjector
import io.github.gamesbyian.chunks.model.UniversalAddSource
import io.github.gamesbyian.chunks.model.UniversalAddTarget
import io.github.gamesbyian.chunks.model.UniversalAddPlace
import io.github.gamesbyian.chunks.model.UniversalAddIntent
import io.github.gamesbyian.chunks.model.EnvironmentCapabilities
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteAddOutcome
import io.github.gamesbyian.chunks.rpc.RTorrentAddObserver
import io.github.gamesbyian.chunks.rpc.RemoteUniversalAddAdapter
import io.github.gamesbyian.chunks.rpc.RemotePreWriteRefusal
import io.github.gamesbyian.chunks.rpc.RemoteAddFileSkips
import io.github.gamesbyian.chunks.rpc.RemotePreStartSetup
import io.github.gamesbyian.chunks.rpc.RemoteAddAndStart
import io.github.gamesbyian.chunks.rpc.StoppedRemoteAddPlan
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoPreview
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import kotlinx.coroutines.CoroutineScope
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
    /** The person's own Android document behind [initialTorrent], if it came from one. */
    initialTorrentDocument: android.net.Uri? = null,
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
    /** A retained ViewModel scope keeps one authorized RPC and readback alive
     * across Add/Transfers navigation AND Activity configuration recreation.
     * Process death is still uncertain; never replay the submitted command.
     * Standalone screen tests without a supplied scope remain screen-scoped. */
    attemptScope: CoroutineScope? = null,
    /** The app-owned Add lifecycle owner; a standalone screen uses its own over [attemptScope]. */
    sharedAttemptController: UniversalAddAttemptController? = null,
    /** Inline shared Add-to selector; never grants remote admission. */
    addDestinationPicker: (@Composable () -> Unit)? = null,
    externalSourceReading: Boolean = false,
    /** A shared Android source is held behind this screen's attempt (#1786). */
    externalSourceWaiting: Boolean = false,
    /** The held source may now become the next review; the parent re-checks occupancy. */
    onVerifiedAttemptWithWaitingSource: () -> Unit = {},
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
    onTorrentAdopted: (TorrentMetainfoPreview, ByteArray, android.net.Uri?) -> Unit = { _, _, _ -> },
    /** Read-only injectable HTTPS intake for deterministic stalled-request UI tests. */
    fetchTorrentLink: suspend (TorrentLinkCheck.Fetchable) -> TorrentLinkResult = { link ->
        withContext(Dispatchers.IO) { TorrentLinkFetcher().fetch(link) }
    },
    /** Isolated Compose race tests supply actual foreground focus as a fixture; ordinary app uses Android window focus. */
    testForegroundClipboardFocus: Boolean? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The mutation/readback must outlive navigation and configuration changes
    // when production supplies its ViewModel scope. Clearing that owner (or
    // process death) cancels the task without authorizing a duplicate write.
    // A standalone screen still uses the composition-local fallback.
    val submitScope = UniversalAddExecutionScope.select(attemptScope, scope)
    val attemptController = sharedAttemptController
        ?: remember(submitScope) { UniversalAddAttemptController { submitScope } }
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
    // Publish the exact client's confirmed new job while Add is visible OR
    // after navigating away. Only the coordinator's VERIFIED provenance can
    // release a new-registration notification; post-timeout session existence
    // alone cannot. A single session-owned marker prevents double publishing.
    fun publishVerifiedAdd(hash: String) {
        if (draft.coordinator.phase != UniversalAddCoordinator.Phase.VERIFIED ||
            draft.reportedVerifiedHash == hash) return
        draft.reportedVerifiedHash = hash
        val submittedHash = draft.attemptTicket?.intent?.source?.expectedInfoHash
        val verifiedAlias = exactNewAddAlias(draft.frozenAlias, submittedHash.orEmpty(), hash)
        if (verifiedAlias != null) {
            if (!runCatching { onVerifiedAlias(hash, verifiedAlias) }.getOrDefault(false))
                outcome = outcome.orEmpty() +
                    " The transfer was verified, but its Chunks display name could not be saved."
            else addAliasState?.let { aliasDraft ->
                if (aliasDraft.value == verifiedAlias) aliasDraft.value = ""
            }
        }
        onVerifiedAdd(hash)
        if (draft.resetArtworkAfterVerification && magnet == draft.attemptSource && initialTorrent == null)
            automaticArtwork = true
    }
    LaunchedEffect(verifiedHash, draft.coordinator.phase) {
        verifiedHash?.let(::publishVerifiedAdd)
    }
    // Optional read-only shortcuts, keyed to the authenticated Add destination.
    // Never open another account's saved folder suggestions on a scope change.
    var showFolderShortcuts by rememberSaveable(finishInOrderScope, environmentLabel) {
        mutableStateOf(false)
    }
    var showServerFolderPicker by remember(remote) { mutableStateOf(false) }
    val finishInOrderStore = remember(finishInOrderScope) { finishInOrderScope?.let { FinishInOrderStore(context, it) } }
    var finishInOrderDefault by remember(finishInOrderScope) { mutableStateOf(finishInOrderStore?.defaultForNewAdds() == true) }
    if (!draft.finishInOrderInitialized || draft.finishInOrderHash != selectedTorrent?.infoHashV1) {
        draft.finishInOrder.value = finishInOrderDefault
        draft.finishInOrderInitialized = true
        draft.finishInOrderHash = selectedTorrent?.infoHashV1
    }
    var finishInOrderAfterAdd by draft.finishInOrder
    // Reviewed file skips belong to exactly one selected .torrent identity. Derived
    // (never reset by a write during composition): another source starts unskipped.
    val reviewedHash = selectedTorrent?.infoHashV1
    val skippedFiles = if (reviewedHash != null && draft.skippedFilesHash == reviewedHash)
        draft.skippedFiles else emptySet()
    val fileChoiceOpen = reviewedHash != null && draft.skippedFilesHash == reviewedHash && draft.fileChoiceOpen
    val highFiles = if (reviewedHash != null && draft.skippedFilesHash == reviewedHash)
        draft.highFiles - skippedFiles else emptySet()
    val declaredFiles = remember(selectedTorrent) { selectedTorrent?.let { AddFileChoices.declared(it) } }
    val noFileChosen = useFile && declaredFiles != null &&
        declaredFiles.none { it.index !in skippedFiles }
    val rechecking = attemptedTicket?.intent?.target?.environment?.let(attemptController::isChecking) == true
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
    // Keep/Archive/Delete applies only to the person's own document behind the
    // exact selected bytes; magnets, HTTPS fetches and local exports have none.
    val originalDocument = if (useFile) draft.originalDocumentFor(selectedBytes) else null
    var originalHandling by remember(originalDocument) { mutableStateOf<OriginalTorrentHandling?>(null) }
    var originalDeletable by remember(originalDocument) { mutableStateOf(false) }
    LaunchedEffect(originalDocument) {
        val document = originalDocument ?: return@LaunchedEffect
        val (saved, deletable) = withContext(Dispatchers.IO) {
            OriginalTorrentHandlingStore(context).default to
                OriginalTorrentDocuments.supportsDelete(context, document)
        }
        originalDeletable = deletable
        originalHandling = if (saved == OriginalTorrentHandling.DELETE && !deletable)
            OriginalTorrentHandling.ARCHIVE else saved
    }
    LaunchedEffect(initialTorrent, alreadyAttempted) {
        val document = initialTorrent ?: return@LaunchedEffect
        if (submitting || alreadyAttempted) return@LaunchedEffect
        onInitialTorrentConsumed()
        try {
            val bytes = document.copyOf()
            selectedTorrent = TorrentMetainfoReader.preview(bytes)
            selectedBytes = bytes
            draft.bindOriginalDocument(initialTorrentDocument, bytes)
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
                    // Only a document the person opened here can be archived or deleted later.
                    draft.bindOriginalDocument(uri, result.second)
                    // An explicitly opened .torrent is the newest source and replaces
                    // older typed text, exactly as local Add and HTTPS intake do. A
                    // lingering magnet would otherwise win a later Add-target switch.
                    draft.textAtFileSelection = ""
                    fromLocal = false
                    useFile = true
                    onMagnet("")
                    onTorrentAdopted(result.first, result.second, uri)
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

    // Reconciliation changes the coordinator after asynchronous exact-server
    // reads. Observe its settled phase in this composition, together with the
    // read/write activity states. Item-only state reads otherwise update the
    // result card but can leave LazyColumn's acknowledgment item list stale.
    // The coordinator remains the sole authority; this is presentation only.
    val settledPhase = draft.coordinator.phase.takeUnless { submitting || rechecking }
    val exactSessionConfirmed = settledPhase == UniversalAddCoordinator.Phase.SESSION_CONFIRMED
    val addedByThisAttempt = settledPhase == UniversalAddCoordinator.Phase.VERIFIED
    val registeredStartUnverified =
        settledPhase == UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED
    val canAcknowledge = settledPhase?.let {
        canCloseRemoteAddReview(it, verifiedHash, uncertain)
    } == true

    // The app-owned read-only follow-up may confirm the exact job after a lost
    // acknowledgement while this screen is away. Explain that evidence here in
    // the same words as Recheck; it never attributes origin or resends anything.
    val followedTicket = draft.attemptTicket
    val followUpRevision = followedTicket?.let {
        attemptController.observationRevision(it.intent.target.environment) } ?: 0
    LaunchedEffect(followUpRevision, followedTicket) {
        val ticket = followedTicket ?: return@LaunchedEffect
        if (followUpRevision == 0 || submitting || rechecking || draft.attemptTicket !== ticket) return@LaunchedEffect
        when (draft.coordinator.phase) {
            UniversalAddCoordinator.Phase.SESSION_CONFIRMED -> if (verifiedHash == null) {
                verifiedHash = ticket.intent.source.expectedInfoHash
                outcome = "Server now confirms this exact hash, destination and requested Start. " +
                    "Which client originally registered it remains unknown; no Start or Add was resent."
            }
            UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED ->
                outcome = "The exact server job and Place are confirmed, but requested Start is unverified. You may explicitly acknowledge this incomplete result; no Add or Start was resent."
            else -> Unit
        }
    }

    // #1786: a held Open With must not vanish behind a finishing attempt. Presentation-only
    // trigger; the parent re-runs its own verified-release and occupancy checks.
    LaunchedEffect(addedByThisAttempt && canAcknowledge, externalSourceWaiting) {
        if (addedByThisAttempt && canAcknowledge && externalSourceWaiting)
            onVerifiedAttemptWithWaitingSource()
    }

    UniversalAddForm(
        capabilities = UniversalAddClientCapabilities.RTORRENT,
        modifier = Modifier.fillMaxSize().testTag(ChunksSemantics.ADD_BODY_RTORRENT),
        lazyContentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        source = {
            // The one shared source-first intake (as Local and the protocol clients).
            UniversalAddSourceSection(
                text = sourceText,
                onTextChange = {
                    if (!alreadyAttempted) {
                        onMagnet(it)
                        useFile = false
                        awaitingConfirmation = false
                    }
                },
                enabled = !submitting && !choosing && !alreadyAttempted,
                onOpenTorrent = { picker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) },
                readingTorrent = choosing,
                linkFetching = linkFetching,
                onFetchLink = fetch@{ link ->
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
                                    // A fetched link has no deletable or archivable original.
                                    draft.bindOriginalDocument(null, null)
                                    onTorrentAdopted(result.preview, result.bytes, null)
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
                },
                onNothingToPaste = { fileError = UniversalAddSourceText.NOTHING_TO_PASTE },
                textIsSelectedSource = !useFile
            ) {
                if (clipboardFilled != null && sourceText == clipboardFilled && !alreadyAttempted) ClipboardPrefillNote(
                    onClear = { onMagnet(""); clipboardFilled = null }, enabled = !submitting)
            }
            clipboardPrefillOn?.let { on ->
                ClipboardPrefillToggle(checked = on, enabled = !submitting, onChange = { next ->
                    scope.launch {
                        if (withContext(Dispatchers.IO) { ClipboardMagnetPrefill.setEnabled(context, next) })
                            clipboardPrefillOn = next
                    }
                })
            }
            if (selectedTorrent != null) {
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
            if (fileError != null) {
                Text(fileError.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            if (selectedTorrent != null && sourceText.isNotBlank()) {
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
        },
        destination = {
            if (!alreadyAttempted) addDestinationPicker?.invoke()
            if (outcome == null && !submitting) RemoteAddScopeIntro(environmentLabel, finishInOrderScope)
        },
        saveTo = {
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
            // Only the actionable location stays in the default view.
            // Technical rTorrent working-directory/getter details are opt-in;
            // lack of a resolved absolute default never disables blank-place Add.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(remoteAddSaveToSummary(destination, savedDefaultPlace,
                    defaultReadComplete, configuredDefault),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f).testTag(ChunksSemantics.ADD_REMOTE_SAVE_TO))
                TextButton(
                    onClick = { showServerFolderPicker = true },
                    enabled = !submitting && !alreadyAttempted,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Change") }
            }
            if (showServerFolderPicker) RemoteFolderPicker(
                remote = remote,
                connectionName = environmentLabel ?: "rTorrent",
                initialFolder = destination.takeIf(String::isNotBlank)
                    ?: savedDefaultPlace ?: configuredDefault,
                onChoose = { selected ->
                    onDestination(selected)
                    awaitingConfirmation = false
                    showServerFolderPicker = false
                },
                onDismiss = { showServerFolderPicker = false }
            )
            val unresolvedDefault = destination.isBlank() && savedDefaultPlace == null &&
                defaultReadComplete && configuredDefault == null
            var showFolderExplanation by rememberSaveable(finishInOrderScope) {
                mutableStateOf(false)
            }
            if (unresolvedDefault) {
                TextButton(
                    onClick = { showFolderExplanation = !showFolderExplanation },
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        stateDescription = if (showFolderExplanation) "Expanded" else "Collapsed"
                    }
                ) {
                    Text(if (showFolderExplanation) "Hide server folder details"
                        else "About server folders")
                }
                if (showFolderExplanation) Text(
                    remoteDefaultUnavailableReason(defaultReport) +
                        " Chunks will confirm the actual folder after Add. " +
                        "Leaving the optional folder blank sends no directory override.",
                    style = MaterialTheme.typography.bodySmall)
            }
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
            if (!alreadyAttempted && plan != null) {
                Text("Infohash: " + plan.expectedInfoHash, style = MaterialTheme.typography.bodySmall)
                Text("Destination: " + when {
                    plan.destination.isEmpty() -> "Server default; actual folder checked after Add."
                    plan.useServerDefault -> plan.destination + " (server default; no directory override)"
                    else -> plan.destination + " (custom parent folder)"
                }, style = MaterialTheme.typography.bodySmall)
            }
            // Existing choices are suggestions, never permission/write probes. Keep
            // the effective default and manually editable custom Place above.
            // Disclosing or hiding a list must not invalidate an Add review or
            // trigger a new server command; selecting a path invalidates review.
            if (!alreadyAttempted && (recentPlaces.isNotEmpty() || observedPlaces.isNotEmpty())) {
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
                if (showFolderShortcuts) {
                    if (recentPlaces.isNotEmpty()) {
                        RecentRemotePlaces(recentPlaces, enabled = !submitting) { parent ->
                            onDestination(parent)
                            awaitingConfirmation = false
                            showFolderShortcuts = false
                        }
                    }
                    if (observedPlaces.isNotEmpty()) {
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
        },
        start = if (alreadyAttempted) null else UniversalAddToggle(startAfterAdd, !submitting && !choosing) {
            startAfterAdd = it
            awaitingConfirmation = false
        },
        files = { capabilities ->
            // "Files to download": the same reviewed-skip control as a Local Add.
            val reviewedFiles = declaredFiles
            if (!alreadyAttempted && capabilities.fileChoices && useFile && reviewedFiles != null) {
                AddFileChoice(reviewedFiles, skippedFiles, fileChoiceOpen,
                    enabled = !submitting,
                    onExpanded = { open ->
                        if (draft.skippedFilesHash != reviewedHash) {
                            draft.skippedFiles = emptySet(); draft.highFiles = emptySet()
                        }
                        draft.skippedFilesHash = reviewedHash
                        draft.fileChoiceOpen = open
                    },
                    // rTorrent stores off/normal/high: High is the only raised level.
                    priorityLevels = capabilities.priorityLevels.toList(),
                    priorities = highFiles.associateWith { io.github.gamesbyian.chunks.rpc.ClientFilePriority.HIGH },
                    onPriorityChange = { index, level ->
                        if (draft.skippedFilesHash != reviewedHash) {
                            draft.skippedFiles = emptySet(); draft.highFiles = emptySet()
                        }
                        draft.skippedFilesHash = reviewedHash
                        draft.highFiles = if (level == null) draft.highFiles - index else draft.highFiles + index
                        awaitingConfirmation = false
                    },
                    onSkippedChange = { chosen ->
                        if (draft.skippedFilesHash != reviewedHash) draft.highFiles = emptySet()
                        draft.skippedFilesHash = reviewedHash
                        draft.skippedFiles = chosen
                        draft.highFiles = draft.highFiles - chosen
                        awaitingConfirmation = false
                    })
            }
        },
        artwork = if (alreadyAttempted) null else UniversalAddToggle(automaticArtwork, !submitting && !choosing) {
            automaticArtwork = it
            awaitingConfirmation = false
        },
        options = {
            val shownHandling = originalHandling
            if (!alreadyAttempted && originalDocument != null && shownHandling != null)
                OriginalTorrentHandlingSummary(shownHandling)
            // Finish-in-order is eligible only for multi-file .torrent sources
            // where every file downloads (it manages all file priorities).
            val fioStore = finishInOrderStore
            if (!alreadyAttempted && UniversalAddClientCapabilities.RTORRENT.finishInOrderAtAdd &&
                plan != null && fioStore != null && useFile && (selectedTorrent?.fileCount ?: 0) >= 2 &&
                skippedFiles.isEmpty()) {
                FinishInOrderAddChoice(
                    "Finish in Order: the first episode or numbered file gets High, then the next " +
                        "(set before downloading starts; advances while Chunks is open and connected)",
                    checked = finishInOrderAfterAdd, enabled = !submitting,
                    onChecked = { finishInOrderAfterAdd = it; awaitingConfirmation = false },
                    defaultOn = finishInOrderDefault,
                    onDefault = { on -> if (fioStore.setDefaultForNewAdds(on)) finishInOrderDefault = on })
            }
            if (!alreadyAttempted && plan != null) {
                // Restored from the former confirmation card: the one-click Add must still warn,
                // before submission, that a trackerless source can only find peers by DHT/web seeds.
                val trackers = if (useFile) selectedTorrent?.trackerCount ?: 0
                    else TorrentMetainfoReader.magnetTrackerCount(sourceText)
                if (trackers == 0) ChunksBanner("No tracker", trackerlessWarning(useFile), ChunksTone.ERROR)
            }
        },
        submit = if (alreadyAttempted) null else UniversalAddAction(
            enabled = plan != null && !submitting && !choosing && !externalSourceReading &&
                !noFileChosen && validUniversalAddAlias(addAliasState?.value.orEmpty()),
            unavailableReason = addGuidance.takeIf { plan == null },
            onSubmit = addClick@{
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
                // Frozen at the click: the reviewed skips for exactly these bytes.
                val layoutAtClick = bytes?.let { reviewed ->
                    runCatching { AddFileChoices.declared(TorrentMetainfoReader.preview(reviewed)) }
                        .getOrNull()
                }
                // Skips count only for the identity they were reviewed against.
                val clickSkips = if (bytes != null && draft.skippedFilesHash ==
                        runCatching { TorrentMetainfoReader.preview(bytes).infoHashV1 }.getOrNull())
                    draft.skippedFiles else emptySet()
                if (layoutAtClick != null && layoutAtClick.none { it.index !in clickSkips })
                    return@addClick
                val skippedAtClick = layoutAtClick?.filter { it.index in clickSkips }.orEmpty()
                // High counts only for the same reviewed identity, never for a skipped file.
                val clickHigh = if (bytes != null && draft.skippedFilesHash ==
                        runCatching { TorrentMetainfoReader.preview(bytes).infoHashV1 }.getOrNull())
                    draft.highFiles - clickSkips else emptySet()
                val highAtClick = layoutAtClick?.filter { it.index in clickHigh }.orEmpty()
                // Finish in Order manages every file's priority itself: only
                // when the person made no file choices of their own.
                val orderScope = finishInOrderScope?.takeIf {
                    bytes != null && requestedStart && finishInOrderAfterAdd &&
                        (selectedTorrent?.fileCount ?: 0) >= 2 && skippedAtClick.isEmpty() &&
                        highAtClick.isEmpty()
                }
                val prioritiesFirst = orderScope != null || skippedAtClick.isNotEmpty() ||
                    highAtClick.isNotEmpty()
                val intent = runCatching {
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
                    intent
                }.getOrNull()
                // The one shared submit step (as the protocol clients): admission,
                // then exactly one launch in the app-owned controller.
                val submitted = intent?.let { reviewedIntent ->
                    UniversalAddSubmission.admitAndLaunch(
                        controller = attemptController,
                        coordinator = draft.coordinator,
                        intent = reviewedIntent,
                        capabilities = EnvironmentCapabilities.unprobed(reviewedIntent.target.environment),
                        availability = EnvironmentAvailability.LIVE,
                        acquireMutationGate = onMutationStarted,
                        submittedAtEpochMillis = System.currentTimeMillis(),
                        freeze = { ticket ->
                awaitingConfirmation = false
                submitting = true
                alreadyAttempted = true
                verifiedHash = null
                // Clear the previous before-wire refusal BEFORE
                // exposing the new ticket to Compose side effects.
                outcome = null
                observedProgress = null
                draft.attemptTicket = ticket
                // Frozen with this ticket: a later draft edit cannot redirect it.
                draft.frozenOriginal = bytes?.let { reviewed ->
                    draft.originalDocumentFor(reviewed)?.let { document ->
                        RemoteAddSession.FrozenOriginalHandling(document,
                            originalHandling ?: OriginalTorrentHandling.KEEP,
                            originalDeletable, reviewed.copyOf())
                    }
                }
                draft.originalDeletion = null
                draft.originalNote = null
                draft.submittedDisplayName = environmentLabel?.trim()?.takeIf(String::isNotBlank)
                draft.attemptSource = magnet
                draft.frozenAlias = aliasAtClick
                val artworkEnvironment = ticket.intent.target.environment
                // No alternate RPC executor: the remote port invokes the existing
                // native/stopped-first engine operations.
                // Give the callback its suspend type *before* passing it through
                // the optional parameter. A nested let infers Function1 instead.
                val priorityCallback: (suspend (String) -> RemotePreStartSetup)? =
                    if (skippedAtClick.isNotEmpty() || highAtClick.isNotEmpty()) { hash: String ->
                        // Start follows only if every choice reads back exactly.
                        RemoteAddFileSkips.apply(remote, hash, skippedAtClick, requestedStart,
                            high = highAtClick)
                    } else if (orderScope == null) null
                    else { hash: String ->
                        RemotePreStartSetup(enableRemoteFinishInOrderAfterAdd(
                            context, remote, orderScope, hash))
                    }
                val executor = io.github.gamesbyian.chunks.rpc.RTorrentAddExecutor(
                    remote, prioritiesFirst, priorityCallback,
                    prepareBeforeWrite = {
                        // Persist the opt-out before the executor can send its one load.
                        withContext(Dispatchers.IO) {
                            mediaController?.prepareForAdd(
                                io.github.gamesbyian.chunks.model.UnifiedTransferKey.of(
                                    artworkEnvironment, plan.expectedInfoHash), false) == true
                        }
                    })
                            executor
                        },
                    // An unresolved write keeps being observed read-only from the
                    // app-owned scope after leaving Add; never a second load.
                    followUp = RTorrentAddObserver(remote) {
                        draft.coordinator.phase },
                    onInterrupted = { mayHaveWritten ->
                        if (mayHaveWritten) {
                            uncertain = true
                            outcome = "Add was interrupted; inspect the exact server state. No retry was sent."
                        } else {
                            draft.refusedBeforeAdd()
                            outcome = "Add was interrupted before anything was sent. Nothing was added."
                        }
                    },
                    // Also request readback after a timeout/exception.
                    // The write might have happened without an RPC reply.
                    onFinished = { submitting = false; onMutationSettled() }) { run ->
                        val ticket = run.ticket
                    val execution = (run as? io.github.gamesbyian.chunks.model.UniversalAddRun.Completed)?.result
                    if (run is io.github.gamesbyian.chunks.model.UniversalAddRun.Failed) {
                        if (run.writeMayHaveHappened) {
                            verifiedHash = null
                            uncertain = true
                            outcome = "Could not verify the operation. Inspect the current server transfer list before attempting another Add. No automatic retry occurred."
                        } else {
                            draft.refusedBeforeAdd()
                            verifiedHash = null
                            uncertain = false
                            outcome = "The Add could not be prepared. Nothing was added. Review it again."
                        }
                        return@admitAndLaunch
                    }
                    when (execution) {
                        io.github.gamesbyian.chunks.rpc.RTorrentAddExecutor.Result.PreparationRefused -> {
                            // No RPC was sent. This known refusal can be corrected
                            // without weakening the uncertain-command retry latch.
                            draft.refusedBeforeAdd()
                            outcome = "The artwork exclusion could not be saved. Nothing was added. Try again or change Artwork & metadata."
                            return@admitAndLaunch
                        }
                        io.github.gamesbyian.chunks.rpc.RTorrentAddExecutor.Result.PreWriteRefused -> {
                            // The engine adapter proved no load was sent. Release
                            // only this exact ticket; a later attempt needs a new click.
                            draft.refusedBeforeAdd()
                            verifiedHash = null
                            uncertain = false
                            outcome = "The server refused the Add preflight before any write. Reconnect or refresh and review again. Nothing was added."
                            return@admitAndLaunch
                        }
                        else -> Unit
                    }
                    val executed = execution as io.github.gamesbyian.chunks.rpc.RTorrentAddExecutor.Result.Executed
                    try {
                        val result = executed.result
                        val orderNote = executed.orderNote
                        val existingWithoutWrite = executed.existingWithoutWrite
                        if (existingWithoutWrite) draft.refusedBeforeAdd()
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
                        // Continue the newly verified job into Transfers
                        // even when this Add composable is no longer mounted.
                        verifiedHash?.let(::publishVerifiedAdd)
                        applyRemoteOriginalHandling(context, draft, ticket, result)
                            ?.let { outcome = outcome.orEmpty() + " " + it }
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
                            verifiedCustomPlaceOutcome(result, priorityBeforeStart = prioritiesFirst)) {
                            val saved = runCatching {
                                onCustomPlaceVerified(plan.destination)
                            }.getOrDefault(false)
                            if (!saved) outcome = outcome.orEmpty() +
                                " This folder was used but its Recent shortcut could not be saved."
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        // Navigating away during the optional read-only follow-up does
                        // not invalidate the engine result already recorded above.
                        throw cancel
                    } catch (_: Exception) {
                        draft.coordinator.requireReadback(ticket)
                        verifiedHash = null
                        uncertain = true
                        outcome = "Could not verify the operation. Inspect the current server transfer list before attempting another Add. No automatic retry occurred."
                    }

                    }
                }
                when (submitted) {
                    is UniversalAddSubmission.Result.Launched -> Unit
                    is UniversalAddSubmission.Result.ClientBusy -> {
                        // Another write for this exact server is still settling; this
                        // ticket never reached a write and was disarmed by the controller.
                        draft.refusedBeforeAdd()
                        submitting = false
                        onMutationSettled()
                        outcome = "Another Add to this exact server is still settling. Nothing was submitted."
                    }
                    is UniversalAddSubmission.Result.Refused, null -> {
                        val decision = (submitted as? UniversalAddSubmission.Result.Refused)?.decision
                        if (decision?.mutationGateAcquired == true) onMutationSettled()
                        awaitingConfirmation = false
                        // The admission reasons are fixed app vocabulary
                        // (never server or source text); name the exact one.
                        val why = decision?.explanation?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
                        outcome = "Add could not be authorized for this exact server.$why Refresh the connection and review again; nothing was submitted."
                    }
                }
            }
        ),
        guidance = {
            if (!alreadyAttempted && plan == null) Text(addGuidance,
                color = if (invalidDestination) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium)
        },
        status = {
            if (outcome == null) {
                if (submitting) {
                    val receipt = UniversalAddDisplayProjector.from(
                        phase = draft.coordinator.phase,
                        clientLabel = draft.submittedDisplayName ?: environmentLabel ?: "rTorrent",
                        submitting = true
                    )
                    if (receipt != null) UniversalAddReceiptCard(
                        receipt = receipt,
                        modifier = Modifier.testTag(ChunksSemantics.ADD_REMOTE_OPERATION_STATUS)
                    ) {
                        if (attemptScope != null)
                            Text("You can return to Transfers while this client's result is checked.")
                    }
                }
            }
            if (outcome != null) {
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
                val targetName = draft.submittedDisplayName
                    ?: environmentLabel?.takeIf(String::isNotBlank) ?: "rTorrent"
                // All clients use one projected receipt. A synthetic fallback
                // is deliberately uncertain and grants no positive Add evidence.
                val receipt = UniversalAddDisplayProjector.from(
                    phase = draft.coordinator.phase,
                    clientLabel = targetName,
                    submitting = submitting,
                    knownNoWrite = !draft.alreadyAttempted
                ) ?: io.github.gamesbyian.chunks.model.UniversalAddDisplay(
                    stage = io.github.gamesbyian.chunks.model.UniversalAddDisplayStage.UNCONFIRMED,
                    title = "Add result on $targetName",
                    explanation = "Check this client's exact transfer state before another action.",
                    verifiedJobObserved = false
                )
                UniversalAddReceiptCard(
                    receipt = receipt,
                    modifier = Modifier.testTag(ChunksSemantics.ADD_REMOTE_OPERATION_STATUS),
                    needsAttention = uncertain && !nativePlaceAwaitingConfirmation
                ) {
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
                            UniversalAddContinuationAction(ChunksSemantics.ADD_VIEW_TRANSFER, "View transfer",
                                enabled = !submitting && !rechecking) { onViewVerifiedTransfer(hash) }
                        }
                }
            }
            OriginalAddDocumentStatus(draft.originalDocumentStatus, submitScope)
            // Read-only recovery can prove the exact hash, Place and Start
            // without proving that Chunks originally registered the job. Never
            // present this as an ordinary newly verified Add.
            if (alreadyAttempted && (initialTorrent != null || externalSourceWaiting ||
                    (magnet.isNotBlank() && magnet != draft.attemptSource))) {
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
            if (exactSessionConfirmed && verifiedHash != null) {
                Text(
                    "The server confirms the exact transfer, its destination and requested Start state. " +
                        "Chunks cannot prove which client originally registered it after the lost reply. " +
                        "Acknowledging this server state permits a separate new review; no Add or Start is resent.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (registeredStartUnverified) {
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
            if (canAcknowledge) {
                UniversalAddContinuationAction(ChunksSemantics.ADD_ANOTHER,
                    label = when {
                        registeredStartUnverified -> "Acknowledge registered job; Start unverified"
                        exactSessionConfirmed -> "Acknowledge session and start another Add"
                        else -> "Start another Add"
                    },
                    enabled = !submitting && !rechecking && !choosing && !linkFetching && !draft.originalDocumentStatus.deleting
                ) {
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
                }
            }
            if (alreadyAttempted && attemptedTicket != null &&
                draft.coordinator.phase != UniversalAddCoordinator.Phase.VERIFIED) {
                UniversalAddContinuationAction(ChunksSemantics.ADD_CHECK_STATUS,
                    label = if (rechecking) "Checking server…" else "Check server state (read only)",
                    enabled = !submitting && !rechecking
                ) {
                    val ticket = attemptedTicket
                    val priorPhase = draft.coordinator.phase
                    val observer = RTorrentAddObserver(remote) { draft.coordinator.phase }
                    attemptController.recheck(draft.coordinator, observer) { readback ->
                        if (draft.attemptTicket !== ticket) return@recheck
                        progressRevision++
                        when (draft.coordinator.phase) {
                            UniversalAddCoordinator.Phase.SESSION_CONFIRMED -> {
                                verifiedHash = ticket.intent.source.expectedInfoHash
                                val custom = ticket.intent.place as? UniversalAddPlace.ServerCustom
                                outcome = when {
                                    readback != UniversalAddCoordinator.Readback.EXACT_SESSION_CONFIRMED ->
                                        "Previous exact-session confirmation is retained. No Start or Add was resent."
                                    ticket.intent.startWhenAdded -> {
                                        val saved = custom == null || runCatching {
                                            onCustomPlaceVerified(custom.absolutePath)
                                        }.getOrDefault(false)
                                        "Server now confirms this exact hash, destination and active engine. " +
                                            "Which client originally registered it remains unknown. No Start or Add was resent." +
                                            if (saved) "" else " Recent folder shortcut could not be saved."
                                    }
                                    else -> "Read-only check verified this exact hash is stopped at the reviewed destination. " +
                                        "Chunks cannot determine which client originally added it."
                                }
                            }
                            UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED -> {
                                verifiedHash = null
                                uncertain = true
                                outcome = "The exact server job and Place are confirmed, but requested Start is unverified. " +
                                    "You may explicitly acknowledge this incomplete result; no Add or Start was resent."
                            }
                            else -> {
                                verifiedHash = null
                                uncertain = true
                                outcome = when {
                                    !shouldProbeContradictoryRecheck(priorPhase) || readback == null ->
                                        "Read-only check did not confirm this exact hash, destination and requested Start. No Add or Start was resent."
                                    readback == UniversalAddCoordinator.Readback.NOT_OBSERVED ->
                                        "A newer authenticated exact-job read confirms this hash is absent from the reviewed server. " +
                                            "No Add or Start was resent."
                                    else -> "A newer authenticated exact-job read could not confirm the reviewed destination. " +
                                        "No Add or Start was resent."
                                }
                            }
                        }
                        onComplete()
                    }
                }
            }
            // Production passes an app-root attempt scope. Leaving Add only
            // removes the screen, never cancels its authorized RPC/readback.
            // Standalone screen-owned tests retain the old cancellation gate.
            OutlinedButton(onClick = onBack, enabled = !submitting || attemptScope != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                Text("Back to transfers")
            }
        }
    )
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

/**
 * The ordinary Add screen needs a concise, source-specific destination.
 * This is presentation only: the reviewed Add ticket, actual remote RPC Place,
 * and postcondition checks are unchanged. Unobserved is never called invalid.
 */
internal fun remoteAddSaveToSummary(
    customPlace: String,
    savedDefaultPlace: String?,
    defaultReadComplete: Boolean,
    observedAbsoluteDefault: String?
): String = when {
    customPlace.isNotBlank() -> "Save to: $customPlace (this Add only)"
    savedDefaultPlace != null -> "Save to: $savedDefaultPlace (saved default)"
    !defaultReadComplete -> "Save to: Server default (checking; Add is available)"
    observedAbsoluteDefault != null ->
        "Save to: $observedAbsoluteDefault (rTorrent default)"
    else -> "Save to: Server default (confirm after adding)"
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

/**
 * Keep/Archive/Delete for the person's own .torrent document, after a remote
 * Add the engine positively verified as a NEW exact job of the reviewed
 * infohash on this exact account. Already-present, refused, uncertain or
 * Start-unverified results keep the original. Delete is only ever a separate
 * request the person must confirm; nothing here touches the document itself.
 */
internal suspend fun applyRemoteOriginalHandling(
    context: android.content.Context,
    draft: RemoteAddSession,
    ticket: UniversalAddCoordinator.Ticket,
    result: RemoteAddAndStart
): String? {
    val frozen = draft.frozenOriginal ?: return null
    draft.frozenOriginal = null
    if (frozen.handling == OriginalTorrentHandling.KEEP) return null
    val verifiedNew = remoteVerifiedNewSourceHash(result, ticket.intent.source.expectedInfoHash)
    val retirement = OriginalSourceRetirement.afterAdd(context, frozen,
        ticket.intent.target.environment.value, verifiedNew)
    draft.originalDeletion = retirement.deletion
    return retirement.note
}

/** The reviewed infohash, lower-case, only when [result] verified a NEW job of exactly it. */
internal fun remoteVerifiedNewSourceHash(result: RemoteAddAndStart, reviewedInfoHash: String?): String? {
    val reviewed = reviewedInfoHash?.lowercase(java.util.Locale.ROOT) ?: return null
    val verified = verifiedAddNavigationHash(result) ?: return null
    return reviewed.takeIf { verified.equals(it, ignoreCase = true) }
}
