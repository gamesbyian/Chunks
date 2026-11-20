package io.github.gamesbyian.chunks

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddLifecycle
import io.github.gamesbyian.chunks.model.UniversalAddRun
import io.github.gamesbyian.chunks.rpc.ClientAddRequest
import io.github.gamesbyian.chunks.rpc.ClientAddSource
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientHashes
import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.ClientOperations
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One frozen Add attempt for a Transmission / qBittorrent / Deluge profile.
 * Held by the caller above navigation; an attempted Add is never replayed.
 */
internal class ProtocolAddAttempt {
    var attemptedHash by mutableStateOf<String?>(null)
        private set
    var outcome by mutableStateOf<ClientOperationOutcome?>(null)
        internal set
    var profileId: String? = null
        private set

    val busy: Boolean get() = attemptedHash != null && outcome == null

    // The displayed source can change after a write, including via Android
    // Share. Remember *which exact draft* was submitted, without retaining a
    // second plaintext magnet/passkey or serializing private metainfo.
    private var submittedTextDigest: ByteArray? = null
    private var submittedFile: PendingLocalAdd? = null
    private var submittedFileDigest: ByteArray? = null
    private fun digest(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun textDigest(value: String) = digest(value.toByteArray(Charsets.UTF_8))

    internal fun submittedTextStillSelected(current: String): Boolean =
        submittedTextDigest?.let { java.security.MessageDigest.isEqual(it, textDigest(current)) } == true

    internal fun submittedFileStillSelected(current: PendingLocalAdd?): Boolean =
        current != null && current === submittedFile && submittedFileDigest?.let {
            java.security.MessageDigest.isEqual(it, digest(current.bytes))
        } == true

    private fun discardSubmittedDraftFingerprint() {
        submittedTextDigest = null
        submittedFile = null
        submittedFileDigest = null
    }

    /** Claims the single submission; false when another attempt owns it. */
    fun claim(profile: String, hash: String,
        sourceText: String = "", reviewedFile: PendingLocalAdd? = null): Boolean {
        if (busy) return false
        attemptedHash = hash
        profileId = profile
        outcome = null
        submittedTextDigest = textDigest(sourceText)
        submittedFile = reviewedFile
        submittedFileDigest = reviewedFile?.let { digest(it.bytes) }
        return true
    }

    /** A claim that never reached the write boundary (admission refused after the claim). */
    fun releaseUnwritten(hash: String) {
        if (attemptedHash == hash && outcome == null) {
            attemptedHash = null
            profileId = null
            discardSubmittedDraftFingerprint()
        }
    }

    // The shared Universal Add review → approval → one-ticket fence, one per exact client.
    private val coordinators = mutableMapOf<String, io.github.gamesbyian.chunks.model.UniversalAddCoordinator>()
    fun coordinator(profile: String) =
        coordinators.getOrPut(profile) { io.github.gamesbyian.chunks.model.UniversalAddCoordinator() }

    /** The latest read-only Check status result, in fixed app vocabulary. */
    var checkNote by mutableStateOf<String?>(null)
        internal set

    /** The fixed admission reason when an Add was refused before any write; null otherwise. */
    var admissionRefusal by mutableStateOf<String?>(null)
        internal set

    /** Keep/Archive/Delete frozen with the Add click; only a verified NEW job may act on it. */
    internal var frozenOriginal: RemoteAddSession.FrozenOriginalHandling? = null
    val originalDocument = OriginalAddDocumentState()
    var originalDeletion: OriginalDeletionRequest? by originalDocument::deletion
        internal set
    var originalNote: String? by originalDocument::note
        internal set

    fun canStartAnother(profile: String): Boolean {
        if (busy || profileId != profile || originalDeletion != null) return false
        return when (coordinator(profile).phase) {
            UniversalAddCoordinator.Phase.VERIFIED,
            UniversalAddCoordinator.Phase.SESSION_CONFIRMED,
            UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED -> true
            UniversalAddCoordinator.Phase.REVIEWED -> outcome is ClientOperationOutcome.Refused
            else -> false
        }
    }

    fun clearSettled(profile: String): Boolean {
        if (!canStartAnother(profile)) return false
        val coordinator = coordinator(profile)
        coordinator.finishVerified() || coordinator.acknowledgeExactSession() || coordinator.acknowledgeObservedJob()
        attemptedHash = null
        outcome = null
        profileId = null
        admissionRefusal = null
        checkNote = null
        frozenOriginal = null
        originalNote = null
        discardSubmittedDraftFingerprint()
        return true
    }

}

/**
 * Source-first review for a remote protocol client: the same draft magnet or
 * reviewed .torrent as every other Add, the client's own default folder (or
 * an exact absolute folder), and the shared Start choice. Submission is one
 * [ClientOperations.add]: exact-absence precondition, ONE write, exact
 * readback of registration, folder and stopped/started state.
 */
@Composable
internal fun ProtocolAddScreen(
    profileId: String,
    clientLabel: String,
    createOperations: () -> ClientOperations,
    destinationPicker: @Composable () -> Unit,
    sourceDraft: MutableState<String>,
    reviewedTorrent: MutableState<PendingLocalAdd?>,
    startState: MutableState<Boolean>,
    artworkState: MutableState<Boolean>? = null,
    attempt: ProtocolAddAttempt,
    attemptScope: CoroutineScope,
    onViewTransfer: (String) -> Unit,
    /** The app-owned Add lifecycle owner; a standalone screen uses its own. */
    attemptController: UniversalAddAttemptController? = null,
    /** HTTPS .torrent links are fetched and validated on this device, never by the client. */
    fetchTorrentLink: suspend (TorrentLinkCheck.Fetchable) -> TorrentLinkResult = { link ->
        withContext(Dispatchers.IO) { TorrentLinkFetcher().fetch(link) }
    },
    /** The shared Add draft's optional Chunks display name (the destination picker edits it). */
    aliasState: MutableState<String>? = null,
    externalSourceReading: Boolean = false,
    /**
     * Saves a Chunks-only display name for a verified NEW exact registration under this
     * exact profile's alias scope; never overwrites an existing name, never calls the client.
     */
    saveVerifiedAlias: ((hash: String, alias: String) -> Boolean)? = null
) {
    val controller = attemptController ?: remember(attemptScope) { UniversalAddAttemptController { attemptScope } }
    val context = LocalContext.current
    val sourceScope = rememberCoroutineScope()
    val currentExternalRead = rememberUpdatedState(externalSourceReading)
    val environment = intentEnvironment(profileId)
    controller.observationRevision(environment)
    val mediaController = io.github.gamesbyian.chunks.media.LocalTransferMediaController.current
    val artworkHolder = artworkState ?: remember(profileId) { mutableStateOf(true) }
    var automaticArtwork by artworkHolder
    val operations = remember(profileId, createOperations) { createOperations() }
    var session by remember(operations) { mutableStateOf<ClientSessionInfo?>(null) }
    var sessionError by remember(operations) { mutableStateOf<String?>(null) }
    var customFolder by remember(profileId) { mutableStateOf("") }
    var useCustom by remember(profileId) { mutableStateOf(false) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var readingTorrent by remember { mutableStateOf(false) }
    var linkFetching by remember { mutableStateOf(false) }
    var documentSelection by remember(profileId) { mutableStateOf<ProtocolAddSourceSelection?>(null) }
    fun sourceSelection() = ProtocolAddSourceSelection(sourceDraft.value, reviewedTorrent.value)
    fun stillSelected(witness: ProtocolAddSourceSelection): Boolean =
        !attempt.busy && !currentExternalRead.value &&
            witness.text == sourceDraft.value && witness.torrent === reviewedTorrent.value


    LaunchedEffect(operations) {
        try {
            session = operations.session()
        } catch (cancel: CancellationException) { throw cancel } catch (failure: Exception) {
            sessionError = RemoteProtocolConnect.explain(failure)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val witness = documentSelection
        documentSelection = null
        if (uri == null || witness == null || !stillSelected(witness)) return@rememberLauncherForActivityResult
        readingTorrent = true
        sourceScope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // Bounded read without API 33-only InputStream calls (minSdk 29).
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (out.size() <= TorrentMetainfoReader.MAX_BYTES) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                        val bytes = out.toByteArray()
                        require(bytes.size <= TorrentMetainfoReader.MAX_BYTES) { "This .torrent is too large" }
                        // The person's own document: eligible for the Keep/Archive/Delete policy.
                        PendingLocalAdd(TorrentMetainfoReader.preview(bytes), bytes, uri)
                    } ?: error("Could not open the document")
                }
            }
            readingTorrent = false
            if (!stillSelected(witness)) return@launch
            read.onSuccess { reviewedTorrent.value = it; sourceDraft.value = ""; fileError = null }
                .onFailure { fileError = "This document could not be read as a supported .torrent file." }
        }
    }

    val draft = sourceDraft.value.trim()
    val torrent = reviewedTorrent.value
    // The same magnet check as every client: one v1 identity, base32 normalized to hex.
    val magnetCheck = remember(draft) { MagnetDraftCheck.inspect(draft) }
    val source: ClientAddSource? = remember(draft, torrent, magnetCheck) {
        when {
            magnetCheck is MagnetDraftCheck.Valid ->
                runCatching { ClientAddSource.Magnet(magnetCheck.normalized) }.getOrNull()
            draft.isEmpty() && torrent != null -> runCatching { ClientAddSource.TorrentFile(torrent.bytes) }.getOrNull()
            else -> null
        }
    }
    // Keep/Archive/Delete applies only to the person's own document behind the reviewed bytes.
    val originalDocument = torrent?.originalDocument?.takeIf { draft.isEmpty() }
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
    val folder = if (useCustom) customFolder.trim() else null
    val defaultFolder = session?.defaultDownloadDirectory?.let { directory ->
        runCatching { ClientHashes.requireDirectory(directory) }.getOrNull()
    }
    val folderValid = if (folder == null) defaultFolder != null
        else runCatching { ClientHashes.requireDirectory(folder) }.isSuccess
    // The one engine-neutral capability descriptor decides what this form offers.
    val capabilities = UniversalAddClientCapabilities.protocol(session)
    val sourceRefusal = source?.let {
        capabilities.refusal(isMagnet = it is ClientAddSource.Magnet, startNow = startState.value, clientLabel = clientLabel)
    }
    val supported = source != null && sourceRefusal == null
    // Files to download: the same shared component and reviewed-layout rule as
    // every Add; skips belong only to the exact reviewed .torrent identity.
    val layout = remember(torrent) {
        torrent?.let { runCatching { AddFileChoices.declared(it.preview) }.getOrNull() }
    }.takeIf { draft.isEmpty() }
    var skipped by remember(torrent?.preview?.infoHashV1) { mutableStateOf<Set<Int>>(emptySet()) }
    // Reviewed Low/High per wanted file, for exactly this reviewed identity.
    var levels by remember(torrent?.preview?.infoHashV1) {
        mutableStateOf<Map<Int, io.github.gamesbyian.chunks.rpc.ClientFilePriority>>(emptyMap())
    }
    var fileChoiceOpen by remember(torrent?.preview?.infoHashV1) { mutableStateOf(false) }
    val canChooseFiles = capabilities.fileChoices
    val chosenSkips = if (layout != null && canChooseFiles) skipped.filter { i -> layout.any { it.index == i } }.toSet()
        else emptySet()
    val noFileChosen = layout != null && layout.none { it.index !in chosenSkips }
    val mine = attempt.profileId == null || attempt.profileId == profileId
    // An unresolved earlier write on this client (uncertain, readback pending, Start
    // unverified) blocks a new Add until the person dismisses it: never a blind retry.
    // A session found by Check status also needs the person's acknowledgement first.
    val unresolved = attempt.coordinator(profileId).phase.let {
        it in io.github.gamesbyian.chunks.model.UniversalAddLifecycle.UNRESOLVED ||
            it == UniversalAddCoordinator.Phase.SESSION_CONFIRMED
    }
    val canSubmit = session != null && source != null && supported && folderValid && !attempt.busy && mine &&
        validUniversalAddAlias(aliasState?.value.orEmpty()) &&
        !unresolved && !externalSourceReading && !readingTorrent && !linkFetching &&
        attempt.originalDeletion == null && !noFileChosen

    UniversalAddForm(
        capabilities = capabilities,
        modifier = Modifier.fillMaxSize().testTag(ChunksSemantics.ADD_BODY_PROTOCOL)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        source = {
        UniversalAddSourceSection(
            text = sourceDraft.value,
            // A complete magnet replaces a reviewed .torrent; a typed link keeps it until fetched.
            onTextChange = { value ->
                sourceDraft.value = value
                if (MagnetDraftCheck.inspect(value.trim()) is MagnetDraftCheck.Valid) reviewedTorrent.value = null
            },
            enabled = !attempt.busy && !unresolved && !linkFetching && !externalSourceReading,
            onOpenTorrent = {
                documentSelection = sourceSelection()
                picker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*"))
            },
            readingTorrent = readingTorrent,
            linkFetching = linkFetching,
            onFetchLink = fetch@{ link ->
                val witness = sourceSelection()
                if (!stillSelected(witness) || unresolved || linkFetching || readingTorrent ||
                    witness.text.trim() != link.uri.toString()) return@fetch
                linkFetching = true
                sourceScope.launch {
                    try {
                        when (val result = fetchTorrentLink(link)) {
                            is TorrentLinkResult.Fetched -> if (stillSelected(witness)) {
                                // Downloaded and validated on this device; a fetched link has no
                                // deletable or archivable original.
                                reviewedTorrent.value = PendingLocalAdd(result.preview, result.bytes)
                                sourceDraft.value = ""
                                fileError = null
                            } else fileError = "The draft changed while fetching. The current source was kept."
                            is TorrentLinkResult.Refused -> if (stillSelected(witness))
                                fileError = result.message
                        }
                    } finally { linkFetching = false }
                }
            },
            onNothingToPaste = { fileError = UniversalAddSourceText.NOTHING_TO_PASTE },
            textIsSelectedSource = draft.isNotEmpty() || torrent == null
        )
        torrent?.takeIf { draft.isEmpty() }?.let {
            Text("Using " + it.preview.name + " · " + it.preview.fileCount.coerceAtLeast(1) + " file(s)",
                style = MaterialTheme.typography.bodyMedium)
        }
        fileError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        },
        destination = {
            destinationPicker()
            sessionError?.let { Text("$clientLabel is unavailable: $it", color = ChunksColors.Yellow) }
        },
        saveTo = {
        Text(when {
            useCustom -> "A folder you enter on $clientLabel"
            defaultFolder != null -> "$clientLabel's default folder ($defaultFolder)"
            session != null -> "$clientLabel's default folder could not be confirmed"
            else -> "Checking $clientLabel's default folder…"
        },
            style = MaterialTheme.typography.bodyMedium)
        if (capabilities.customFolder) OutlinedButton(onClick = { useCustom = !useCustom },
            enabled = !attempt.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (useCustom) "Use the default folder" else "Change folder")
        }
        if (useCustom) OutlinedTextField(
            value = customFolder, onValueChange = { customFolder = it },
            label = { Text("Absolute folder on $clientLabel") },
            isError = !folderValid, singleLine = true, enabled = !attempt.busy,
            modifier = Modifier.fillMaxWidth()
        )
        },
        start = UniversalAddToggle(startState.value, !attempt.busy) { startState.value = it },
        files = { options ->
        if (layout != null && options.fileChoices) AddFileChoice(
            files = layout, skipped = chosenSkips, expanded = fileChoiceOpen, enabled = !attempt.busy,
            onExpanded = { fileChoiceOpen = it },
            // Only the levels this exact client stores (never a silent Low → Normal).
            priorityLevels = listOf(io.github.gamesbyian.chunks.rpc.ClientFilePriority.HIGH,
                io.github.gamesbyian.chunks.rpc.ClientFilePriority.LOW)
                .filter { it in options.priorityLevels },
            priorities = levels.filterKeys { it !in chosenSkips },
            onPriorityChange = { index, level -> levels = if (level == null) levels - index else levels + (index to level) },
            onSkippedChange = { skipped = it; levels = levels - it })
        else if (layout != null && session != null) Text("$clientLabel cannot choose files at Add; " +
            "every file will download.", style = MaterialTheme.typography.bodySmall)
        },
        artwork = UniversalAddToggle(automaticArtwork, !attempt.busy && mine && !unresolved) {
            automaticArtwork = it
        },
        options = {
            originalHandling?.takeIf { originalDocument != null && !attempt.busy }
                ?.let { OriginalTorrentHandlingSummary(it) }
            if (session != null && sourceRefusal != null) Text(sourceRefusal,
                style = MaterialTheme.typography.bodySmall, color = ChunksColors.Yellow)
        },
        submit = UniversalAddAction(canSubmit, onSubmit = submit@{
                val frozen = source ?: return@submit
                if (!canSubmit || currentExternalRead.value || sourceDraft.value.trim() != draft ||
                    reviewedTorrent.value !== torrent) return@submit
                // Frozen at the click: reviewed index → path for exactly these bytes.
                val skips = if (frozen is ClientAddSource.TorrentFile) layout.orEmpty()
                    .filter { it.index in chosenSkips }.associate { it.index to it.path } else emptyMap()
                val reviewedLevels = if (frozen is ClientAddSource.TorrentFile) layout.orEmpty()
                    .filter { it.index !in chosenSkips && levels[it.index] != null }
                    .associate { it.index to io.github.gamesbyian.chunks.rpc.ReviewedFilePriority(
                        it.path, levels.getValue(it.index)) } else emptyMap()
                val request = ClientAddRequest(frozen, folder, startState.value, skips, reviewedLevels)
                val allowArtwork = automaticArtwork // freeze on this one explicit click
                // The same Universal Add admission as Local and rTorrent: capability-bound
                // review, the explicit tap as approval, this client's one-Add claim as the
                // mutation gate, then ONE immutable ticket, all before the write boundary.
                val coordinator = attempt.coordinator(profileId)
                // A positively verified earlier Add needs no recovery; a new reviewed
                // source may follow it (as before). Pending/uncertain results still block
                // until the person dismisses them ("Add another"): never a blind retry.
                if (!attempt.busy) coordinator.finishVerified()
                // The display name is frozen with the click: a later draft edit cannot relabel it.
                val frozenAlias = runCatching { normalizedUniversalAddAlias(aliasState?.value.orEmpty()) }
                    .getOrElse {
                        attempt.admissionRefusal = "Not added: that display name contains unsupported characters or exceeds 160 characters."
                        return@submit
                    }
                val intent = io.github.gamesbyian.chunks.rpc.ProtocolUniversalAddAdapter.intent(
                    profileId, request, defaultFolder, allowArtwork)
                val appContext = context.applicationContext
                val aliasStore = saveVerifiedAlias ?: { h: String, value: String ->
                    TransferAliasStore(appContext).saveVerifiedNewAddAlias(ProtocolAliasScope.of(profileId), h, value)
                }
                val hash = frozen.infoHash
                // The one shared submit step (as rTorrent): admission, then exactly one
                // launch in the app-owned controller, which settles the ticket.
                val submitted = UniversalAddSubmission.admitAndLaunch(
                    controller = controller, coordinator = coordinator, intent = intent,
                    capabilities = io.github.gamesbyian.chunks.model.EnvironmentCapabilities.unprobed(intent.target.environment),
                    availability = io.github.gamesbyian.chunks.model.EnvironmentAvailability.LIVE,
                    acquireMutationGate = {
                        attempt.claim(profileId, frozen.infoHash, sourceDraft.value, reviewedTorrent.value)
                    },
                    submittedAtEpochMillis = System.currentTimeMillis(),
                    freeze = {
                        attempt.admissionRefusal = null
                        attempt.checkNote = null
                        // Frozen with this ticket: a later draft edit cannot redirect it.
                        attempt.frozenOriginal = (frozen as? ClientAddSource.TorrentFile)?.let { file ->
                            originalDocument?.let { document ->
                                RemoteAddSession.FrozenOriginalHandling(document,
                                    originalHandling ?: OriginalTorrentHandling.KEEP, originalDeletable, file.bytes.copyOf())
                            }
                        }
                        attempt.originalDeletion = null
                        attempt.originalNote = null
                        io.github.gamesbyian.chunks.rpc.ProtocolAddExecutor(operations, request,
                            prepareBeforeWrite = {
                                allowArtwork || withContext(Dispatchers.IO) {
                                    mediaController?.prepareForAdd(
                                        io.github.gamesbyian.chunks.model.UnifiedTransferKey.of(
                                            intent.target.environment, hash), false) == true
                                }
                            })
                    },
                    // A Pending/Uncertain write keeps being observed read-only from the
                    // app-owned scope after leaving Add; never a second write.
                    followUp = io.github.gamesbyian.chunks.rpc.ProtocolAddObserver(operations.client),
                    onInterrupted = { mayHaveWritten ->
                        attempt.outcome = if (mayHaveWritten) ClientOperationOutcome.Uncertain(hash,
                            "The Add was interrupted after it was sent; check its status")
                        else ClientOperationOutcome.Refused(hash, "Interrupted before anything was sent")
                    }) { run ->
                    val settled = when (run) {
                        is UniversalAddRun.Completed -> run.result
                        is UniversalAddRun.Failed ->
                            if (run.writeMayHaveHappened) ClientOperationOutcome.Uncertain(hash, "unexpected failure")
                            else ClientOperationOutcome.Refused(hash, "The Add could not be prepared; nothing was sent")
                    }
                    try {
                        // A display name only for this Add's positively verified NEW registration.
                        val verifiedNewHash = (settled as? ClientOperationOutcome.Verified)?.hash
                            ?.takeIf { coordinator.phase == io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.VERIFIED }
                        exactNewAddAlias(frozenAlias, hash, verifiedNewHash)?.let { alias ->
                            val saved = withContext(Dispatchers.IO) {
                                runCatching { aliasStore(hash.lowercase(java.util.Locale.ROOT), alias) }.getOrDefault(false)
                            }
                            if (saved) aliasState?.let { if (it.value.trim() == alias) it.value = "" }
                            else attempt.checkNote = "Added, but its Chunks display name was not saved."
                        }
                        // The shared original-.torrent policy: only this Add's positively
                        // verified NEW registration may archive or ask to delete it.
                        val original = attempt.frozenOriginal
                        attempt.frozenOriginal = null
                        if (original != null) {
                            val verifiedNew = (settled as? ClientOperationOutcome.Verified)?.hash
                                ?.takeIf { it.equals(hash, ignoreCase = true) &&
                                    coordinator.phase == io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.VERIFIED }
                            val retirement = OriginalSourceRetirement.afterAdd(appContext, original,
                                intent.target.environment.value, verifiedNew?.lowercase(java.util.Locale.ROOT))
                            attempt.originalDeletion = retirement.deletion
                            attempt.originalNote = retirement.note
                        }
                        if (settled is ClientOperationOutcome.Verified) automaticArtwork = true
                    } finally {
                        attempt.outcome = settled
                    }
                }
                when (submitted) {
                    is UniversalAddSubmission.Result.Launched -> Unit
                    is UniversalAddSubmission.Result.Refused -> {
                        if (submitted.decision.mutationGateAcquired) attempt.releaseUnwritten(hash)
                        attempt.admissionRefusal = "Not added: " + submitted.decision.explanation
                    }
                    is UniversalAddSubmission.Result.ClientBusy -> {
                        attempt.releaseUnwritten(hash)
                        attempt.admissionRefusal = "Not added: another Add to $clientLabel is still settling."
                    }
                }
        }),
        status = {
        // A refusal before any write (fixed admission vocabulary) shares the one status location.
        attempt.admissionRefusal?.takeIf { attempt.profileId == null || attempt.profileId == profileId }?.let { why ->
            Text(why, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(ChunksSemantics.ADD_REMOTE_OPERATION_STATUS))
        }
        val outcome = attempt.outcome.takeIf { attempt.profileId == profileId }
        // The one shared, engine-neutral receipt (as Local and rTorrent): the coordinator's
        // evidence decides the heading; the client's own reason is a subordinate detail.
        val receipt = if (attempt.profileId == profileId)
            io.github.gamesbyian.chunks.model.UniversalAddDisplayProjector.from(
                phase = attempt.coordinator(profileId).phase, clientLabel = clientLabel,
                submitting = attempt.busy,
                knownNoWrite = outcome is ClientOperationOutcome.Refused) else null
        if (receipt != null) UniversalAddReceiptCard(receipt,
            modifier = Modifier.testTag(ChunksSemantics.ADD_REMOTE_OPERATION_STATUS),
            needsAttention = receipt.stage != io.github.gamesbyian.chunks.model.UniversalAddDisplayStage.VERIFIED &&
                receipt.stage != io.github.gamesbyian.chunks.model.UniversalAddDisplayStage.SUBMITTING) {
            outcome?.takeIf { it !is ClientOperationOutcome.Verified }?.let { result ->
                Text(ProtocolOperationText.describe("Add", result, clientLabel),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        outcome?.let { result ->
            val coordinatorNow = attempt.coordinator(profileId)
            val phase = coordinatorNow.phase
            if (phase == UniversalAddCoordinator.Phase.VERIFIED || phase == UniversalAddCoordinator.Phase.SESSION_CONFIRMED) {
                UniversalAddContinuationAction(ChunksSemantics.ADD_VIEW_TRANSFER, "View transfer") {
                    onViewTransfer(result.hash)
                }
            }
            // Read-only recovery of an unresolved write: one exact-hash read on
            // this client, never a second Add.
            if (result !is ClientOperationOutcome.Refused && phase in UniversalAddLifecycle.UNRESOLVED) {
                val checkingNow = controller.isChecking(intentEnvironment(profileId))
                OutlinedButton(onClick = {
                    controller.recheck(coordinatorNow,
                        io.github.gamesbyian.chunks.rpc.ProtocolAddObserver(operations.client)) { readback ->
                        attempt.checkNote = ProtocolAddCheckText.describe(readback, coordinatorNow.phase, clientLabel)
                    }
                }, enabled = !checkingNow,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag(ChunksSemantics.ADD_CHECK_STATUS)) {
                    Text(if (checkingNow) "Checking $clientLabel…" else "Check status")
                }
            }
            attempt.checkNote?.let { note ->
                Text(note, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (attempt.canStartAnother(profileId)) UniversalAddContinuationAction(ChunksSemantics.ADD_ANOTHER,
                label = when (phase) {
                    UniversalAddCoordinator.Phase.SESSION_CONFIRMED -> "Acknowledge session and start another Add"
                    UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED -> "Acknowledge registered job; Start unverified"
                    UniversalAddCoordinator.Phase.REVIEWED -> "Review again"
                    else -> "Start another Add"
                }
            ) {
                // A newer Share/picker/text edit can occur while the old
                // attempt is settling. Only retire the exact source captured
                // by that old write; never drop a newer document or passkey.
                val sameText = attempt.submittedTextStillSelected(sourceDraft.value)
                val sameFile = attempt.submittedFileStillSelected(reviewedTorrent.value)
                if (attempt.clearSettled(profileId) && phase != UniversalAddCoordinator.Phase.REVIEWED) {
                    if (sameText) sourceDraft.value = ""
                    if (sameFile) reviewedTorrent.value = null
                    if (sameText && (sameFile || reviewedTorrent.value == null)) automaticArtwork = true
                }
            }
        }
        if (attempt.profileId == profileId) OriginalAddDocumentStatus(attempt.originalDocument, attemptScope)
        }
    )
}

private class ProtocolAddSourceSelection(val text: String, val torrent: PendingLocalAdd?)

private fun intentEnvironment(profileId: String) = io.github.gamesbyian.chunks.model.EnvironmentId(profileId)

/** Fixed, engine-neutral text for a read-only protocol Add status check. */
internal object ProtocolAddCheckText {
    fun describe(
        readback: io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Readback?,
        phase: io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase,
        clientLabel: String
    ): String {
        return when {
            readback == null -> "$clientLabel could not be read. Nothing was sent; check again later."
            phase == io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.SESSION_CONFIRMED ->
                "$clientLabel has this exact transfer in the reviewed folder with the requested Start. " +
                    "Chunks cannot prove this Add registered it. Nothing was resent."
            phase == io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED ->
                "$clientLabel has this exact transfer in the reviewed folder, but the requested Start is not confirmed. Nothing was resent."
            phase == io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.OBSERVED_UNCERTAIN ->
                "$clientLabel has this hash, but not in the reviewed folder. Nothing was resent."
            else -> "$clientLabel does not list this transfer yet. Nothing was resent; it may still appear."
        }
    }
}
