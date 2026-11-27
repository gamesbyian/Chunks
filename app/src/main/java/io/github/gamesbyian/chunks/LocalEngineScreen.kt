package io.github.gamesbyian.chunks

import android.Manifest
import android.content.Context
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.model.TransferMove
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalDeleteDataDispatch
import io.github.gamesbyian.chunks.engine.LocalDeleteDataReadback
import io.github.gamesbyian.chunks.engine.LocalDeleteDataReceipt
import io.github.gamesbyian.chunks.engine.LocalEpisodeFocusPlanner
import io.github.gamesbyian.chunks.engine.LocalFileBatchOutcome
import io.github.gamesbyian.chunks.engine.LocalRateLimits
import io.github.gamesbyian.chunks.engine.LocalTrackerRepairOutcome
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.Units
import io.github.gamesbyian.chunks.model.UniversalAddDisplayProjector
import io.github.gamesbyian.chunks.model.LocalInventoryFrame
import io.github.gamesbyian.chunks.snapshots.LocalInventorySnapshotPanel
import io.github.gamesbyian.chunks.snapshots.SnapshotFilePriorities
import io.github.gamesbyian.chunks.snapshots.applyLocalSnapshotAlias
import io.github.gamesbyian.chunks.snapshots.SnapshotSettingsCapture
import io.github.gamesbyian.chunks.model.TorrentIdentityHandoff
import io.github.gamesbyian.chunks.model.LocalAddStoragePreflight
import io.github.gamesbyian.chunks.model.LocalAddStoragePlanner
import io.github.gamesbyian.chunks.model.LocalFolderInventoryProbe
import io.github.gamesbyian.chunks.model.LocalInventoryFreshness
import io.github.gamesbyian.chunks.model.LocalProgressHistory
import io.github.gamesbyian.chunks.model.CollectionNameRegistryRules
import io.github.gamesbyian.chunks.model.ManualCollectionPager
import io.github.gamesbyian.chunks.model.LocalQueueCatalog
import io.github.gamesbyian.chunks.model.LocalQueueFilter
import io.github.gamesbyian.chunks.model.LocalQueueOrder
import io.github.gamesbyian.chunks.model.TransferSortOrdering
import io.github.gamesbyian.chunks.model.UnifiedSortDirection
import io.github.gamesbyian.chunks.snapshots.LocalSnapshotRestorePreviewPanel
import io.github.gamesbyian.chunks.snapshots.LocalSnapshotPriorityRestore
import io.github.gamesbyian.chunks.snapshots.LocalSnapshotStoppedRestoreDraft
import io.github.gamesbyian.chunks.snapshots.LocalSnapshotStoppedRestoreReview
import io.github.gamesbyian.chunks.snapshots.LocalSnapshotBatchStoppedRestoreDraft
import io.github.gamesbyian.chunks.snapshots.LocalSnapshotBatchStoppedRestoreReview
import io.github.gamesbyian.chunks.snapshots.SnapshotLocalBatchRestoreV1
import io.github.gamesbyian.chunks.snapshots.SnapshotLocalBatchRecoveryV1
import io.github.gamesbyian.chunks.snapshots.LocalSnapshotBatchRecoveryDraft
import io.github.gamesbyian.chunks.snapshots.LocalSnapshotBatchRecoveryReview
import io.github.gamesbyian.chunks.snapshots.LocalSnapshotRestoreFingerprint
import io.github.gamesbyian.chunks.snapshots.SnapshotManifestV1
import io.github.gamesbyian.chunks.snapshots.SnapshotVerifiedTorrentDocumentV1
import io.github.gamesbyian.chunks.snapshots.SnapshotRestoreJournalV1
import io.github.gamesbyian.chunks.snapshots.AndroidSnapshotRestoreJournalStore
import io.github.gamesbyian.chunks.snapshots.SnapshotRestoreOnceV1
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoPreview
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import io.github.gamesbyian.chunks.model.LocalBatchLifecycle
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One engine per process. It lives only as long as Android keeps Chunks running. */
/** Adds one exact .torrent as a stopped job at the signed-in server's default Place. */
internal class LocalServerCopyLink(
    val hostLabel: String,
    val addStoppedAtDefault: suspend (ByteArray) -> io.github.gamesbyian.chunks.rpc.RemoteAddOutcome
)

/** The connected rTorrent account as seen by local-side Move Transfer. */
internal class LocalMoveServer(
    val aliasScope: String,
    val observe: suspend (String) -> TransferMove.Observation
)

private data class LocalScreenReadout(
    val transfers: List<LocalTransferStatus>?,
    val filesHash: String?,
    val files: List<LocalTransferFile>?,
    val restoreNotice: String?,
    val revision: Long
)

/**
 * One screen observation lane per process, not per navigation instance.
 * If a libtorrent JNI call never returns, leaving and reopening the screen
 * cannot create a second blocked worker or a growing backlog of reads.
 */
private object LocalScreenReader {
    val revision get() = LocalMutationFence.revision
    val pump = LocalScreenObservationPump<LocalScreenReadout>(
        java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "chunks-local-screen-status").apply { isDaemon = true }
        }
    )
}

/**
 * Process-memory-only reviewed local .torrent. Never Bundle/save metainfo:
 * tracker tokens may be embedded. The digest covers the WHOLE source, not
 * only the v1 infohash, so a changed same-host tracker URL cannot silently
 * acquire the user's authorization for the previously reviewed document.
 */
internal class PendingLocalAdd(
    val preview: TorrentMetainfoPreview, val bytes: ByteArray,
    /** The user's own Android document, when the bytes came from one (never a fetched URL). */
    val originalDocument: Uri? = null
) {
    private val reviewedSourceSha256 =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

    fun stillMatchesReviewedSource(candidate: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(reviewedSourceSha256,
            java.security.MessageDigest.getInstance("SHA-256").digest(candidate))
}

/** Presentation destinations share the same local engine and Add coordinator. */
/** An explicit local handoff is only a draft. HTTPS still needs Get torrent,
 * strict metainfo verification, Place and the ordinary Add confirmation. */
internal fun reviewableLocalExternalText(value: String?): Boolean =
    value != null && (value.startsWith("magnet:?", ignoreCase = true) ||
        TorrentLinkCheck.inspect(value) is TorrentLinkCheck.Fetchable)

internal enum class LocalPane { TRANSFERS, ADD, OPTIONS }

@Composable
internal fun LocalEngineScreen(
    onBack: (() -> Unit)? = null,
    pane: LocalPane = LocalPane.TRANSFERS,
    showNavigationFooter: Boolean = true,
    /** Opens Smart Seeding Rules; null hides the entry. */
    onSeedingRules: (() -> Unit)? = null,
    /** Options > Downloads: Smart Routing rules (suggest an Add target). */
    onSmartRouting: (() -> Unit)? = null,
    /** Options → Artwork & media info (app-wide presentation preferences). */
    onMediaSettings: (() -> Unit)? = null,
    /** App-wide local tools, reachable from Local options without leaving via the server entry. */
    onCreateTorrent: (() -> Unit)? = null,
    onZipReview: (() -> Unit)? = null,
    /** Opens the read-only All environments view; null hides the entry. */
    onCombined: (() -> Unit)? = null,
    /** Only true when no remote connection OR saved remote profile exists. */
    showFirstRunWelcome: Boolean = false,
    /** The central Connections route; no connection is attempted by navigating. */
    onConnections: (() -> Unit)? = null,
    /** Existing authenticated rTorrent destinations; null when no exact current account. */
    remoteEnvironmentLabel: String? = null,
    onRemotePlaces: (() -> Unit)? = null,
    onRemoteOrganize: (() -> Unit)? = null,
    onRemoteNames: (() -> Unit)? = null,
    onRemoteSnapshot: (() -> Unit)? = null,
    /** Opens the existing device Add review, without submitting or choosing Place. */
    onOpenAdd: (() -> Unit)? = null,
    /** Snapshot recovery only: open local review without rebinding a remote
     * .torrent choice or replacing the frozen snapshot restore draft. */
    onShowLocalSnapshotReview: (() -> Unit)? = null,
    /** Opens the app-wide Options destination without changing an Add draft. */
    onOpenOptions: (() -> Unit)? = null,
    initialMagnet: String? = null,
    initialTorrentUri: Uri? = null,
    /** A connected, independently privacy-gated rTorrent Add screen is available. */
    canReviewOnConnectedServer: Boolean = false,
    /** The signed-in rTorrent account for batch Copy Transfer to server; null when signed out. */
    serverCopy: LocalServerCopyLink? = null,
    /** The connected rTorrent account, for Move Transfer checks; null when signed out. */
    moveServer: LocalMoveServer? = null,
    onTorrentConsumed: (Uri) -> Unit = {},
    onPrefillConsumed: () -> Unit = {},
    /** Pre-fills the list search (for example an exact infohash from All environments). */
    initialSearch: String? = null,
    onSearchConsumed: () -> Unit = {},
    /** Reports each Add the local device engine acknowledged and read back, by its exact hash. */
    onAcknowledgedAdd: (String) -> Unit = {},
    onViewAddTransfer: ((String) -> Unit)? = null,
    /** The Local options tool just closed ("SEEDING", "CREATE", "ARCHIVES"); its button regains focus. */
    returnFocusTool: String? = null,
    onReturnFocusConsumed: () -> Unit = {},
    onPrepareRemote: ((String) -> Unit)? = null,
    /** Explicit remote review: a current reviewed source carries its provenance;
     * a transfer export passes null and cannot inherit an Android document. */
    onPrepareRemoteTorrent: ((ByteArray, PendingLocalAdd?) -> Boolean)? = null,
    /**
     * Navigation-only state owned by the caller so that leaving for All
     * environments, Seeding rules or a server and returning keeps the list
     * position and search. Never carries selections or confirmations.
     */
    listScroll: androidx.compose.foundation.ScrollState? = null,
    searchState: androidx.compose.runtime.MutableState<String>? = null,
    optionsSearchState: androidx.compose.runtime.MutableState<String>? = null,
    optionsCategoryState: androidx.compose.runtime.MutableState<OptionsCategory?>? = null,
    optionsReturnQueryState: androidx.compose.runtime.MutableState<String>? = null,
    optionsIndexScrollState: androidx.compose.runtime.MutableState<Int>? = null,
    /**
     * Typed magnet draft held by the caller in process memory, so visiting All
     * environments or Seeding rules does not discard it. Never saved to
     * instance state: a magnet may carry a private tracker passkey.
     */
    magnetDraftState: androidx.compose.runtime.MutableState<String>? = null,
    /** Non-secret Add intent survives navigation independently of the saved default. */
    addPlaceState: androidx.compose.runtime.MutableState<String?>? = null,
    addFolderState: androidx.compose.runtime.MutableState<String>? = null,
    addStartState: androidx.compose.runtime.MutableState<Boolean>? = null,
    addArtworkState: androidx.compose.runtime.MutableState<Boolean>? = null,
    addArtworkExpandedState: androidx.compose.runtime.MutableState<Boolean>? = null,
    onLeaveAdd: (() -> Unit)? = null,
    /** Shared source-first Add target selector, embedded without creating a second route. */
    addDestinationPicker: (@Composable () -> Unit)? = null,
    /** One parent-owned optional Chunks list label across local/server draft switches. */
    addAliasState: androidx.compose.runtime.MutableState<String>? = null,
    externalSourceReading: Boolean = false,
    /** Reviewed .torrent bytes outlive local screen navigation, never Activity/process recreation. */
    pendingAddState: androidx.compose.runtime.MutableState<PendingLocalAdd?>? = null,
    /** App-owned process-only attempt/reconciliation authority, injected by Agent 5. */
    localAddAttemptSession: LocalAddAttemptSession? = null,
    /** ViewModel-owned Local Add lifetime, shared with remote Add. Only authorized
     * Add writes use it; screen-only list and other mutations stay screen-scoped.
     * Process death cancels it and never authorizes automatic replay. */
    addAttemptScope: kotlinx.coroutines.CoroutineScope? = null,
    /** The app-owned Add lifecycle owner shared with remote Adds; standalone screens use their own. */
    addAttemptController: UniversalAddAttemptController? = null,
    /** Read-only HTTPS metadata intake, injectable for source-replacement regression tests. */
    fetchTorrentDocument: (TorrentLinkCheck.Fetchable) -> TorrentLinkResult = { TorrentLinkFetcher().fetch(it) }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colourRevision = EnvironmentColourStore.revision
    var showEnvironmentColours by remember { mutableStateOf(true) }
    var savingEnvironmentColours by remember { mutableStateOf(false) }
    var colourPreferenceNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(context, colourRevision) {
        try {
            showEnvironmentColours = withContext(Dispatchers.IO) {
                EnvironmentColourStore(context).showEnvironmentColours()
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { colourPreferenceNotice = "Colour preference could not be read." }
    }
    val finishInOrderStore = remember { FinishInOrderStore(context) }
    val seedingAssignments = remember { io.github.gamesbyian.chunks.seeding.PreferencesAssignmentStore(context) }
    val moveStore = remember { TransferMoveStore(context) }
    var moveRevision by remember { mutableStateOf(0) }
    val collectionStore = remember(context) { TransferCollectionStore(context) }
    val aliasStore = remember(context) { TransferAliasStore(context) }
    var aliasRevision by remember { mutableStateOf(0) }
    val nameRegistry = remember(context) { CollectionNameRegistry(context) }
    var registeredNames by remember {
        mutableStateOf(nameRegistry.names(TransferCollectionStore.LOCAL_ENGINE_SCOPE))
    }
    var inventoryFrame by remember {
        mutableStateOf(LocalInventoryFrame<LocalTransferStatus>(emptyList(),
            stale = LocalScreenReader.revision.blocksMutations()))
    }
    // An initial empty Compose list is not an observed empty engine. Restore
    // comparisons require at least one actually accepted native list read.
    var hasConfirmedLocalInventory by remember { mutableStateOf(false) }
    // Accept timestamp is updated only by the exact current native poll generation.
    var lastAcceptedInventoryAt by remember { mutableStateOf(0L) }
    val transfers = inventoryFrame.entries
    val inventoryStale = inventoryFrame.stale || LocalScreenReader.revision.blocksMutations()
    val addInventoryReady = LocalUniversalAddAdapter.inventoryReadyForReview(
        hasConfirmedLocalInventory, inventoryStale)
    var transferPage by remember { mutableStateOf(0) }
    val transferSearchHolder = searchState ?: remember { mutableStateOf("") }
    var transferSearch by transferSearchHolder
    // View-only disclosure; no native engine work is started by these flags.
    var showLocalTools by rememberSaveable { mutableStateOf(false) }
    var showListOptions by rememberSaveable { mutableStateOf(false) }
    val localFilterStore = remember(context) { LocalTransferFilterStore(context) }
    var transferFilter by remember(localFilterStore) { mutableStateOf(localFilterStore.load()) }
    fun chooseLocalFilter(choice: LocalQueueFilter) {
        transferFilter = choice
        localFilterStore.save(choice)
    }
    // Exact hash of the latest acknowledged local Add, for "hidden by view" only.
    var lastAddedHash by remember { mutableStateOf<String?>(null) }
    // The search/filter in effect when that Add happened. Any later deliberate
    // view change retires the notice, so an old Add is never called "new".
    var lastAddedView by remember { mutableStateOf<String?>(null) }
    // The outcome text an acknowledged Add produced, with its exact hash, so
    // only that notice offers "Show new transfer". Process memory only.
    var addedOutcome by remember { mutableStateOf<Pair<String?, String>?>(null) }
    fun rememberAdded(hash: String?, outcome: String?) {
        lastAddedHash = hash
        lastAddedView = hash?.let { transferSearch + "\u0000" + transferFilter.name }
        addedOutcome = hash?.let { outcome to it }
        hash?.let(onAcknowledgedAdd)
    }
    LaunchedEffect(initialSearch) {
        if (!initialSearch.isNullOrBlank()) {
            transferSearch = initialSearch
            // A filter must not hide the transfer the user asked to open.
            transferFilter = LocalQueueFilter.ALL
            onSearchConsumed()
        }
    }
    val sortStore = remember(context) { TransferListSortStore(context) }
    val savedSort = remember(sortStore) {
        sortStore.get(TransferListSortStore.LOCAL, LocalQueueOrder.entries.toTypedArray())
    }
    var transferOrder by remember { mutableStateOf(savedSort?.first ?: LocalQueueOrder.NAME) }
    var transferDirection by remember {
        mutableStateOf(savedSort?.second ?: transferOrder.naturalDirection)
    }
    fun chooseTransferSort(order: LocalQueueOrder, direction: UnifiedSortDirection) {
        transferOrder = order
        transferDirection = direction
        sortStore.set(TransferListSortStore.LOCAL, order, direction)
    }
    // Confirmed media titles are searchable too, keyed by the exact local job.
    val mediaTitles by io.github.gamesbyian.chunks.media.rememberMediaSearchTitles()
    val displayedTransfers = remember(transfers, transferSearch, transferFilter,
        transferOrder, transferDirection, aliasRevision, mediaTitles) {
        LocalQueueCatalog.visible(transfers, transferSearch, transferFilter, transferOrder,
            transferDirection,
            displayName = { transfer -> aliasStore.get(TransferCollectionStore.LOCAL_ENGINE_SCOPE,
                transfer.infoHash) ?: transfer.name },
            mediaTitle = { transfer -> localTransferKey(transfer.infoHash)
                ?.let { mediaTitles[io.github.gamesbyian.chunks.media.MediaTransferKey.of(it)] } })
    }
    // Changing the result set or its order always returns to page one. A new
    // engine observation does not change filter/search or silently select items.
    LaunchedEffect(transferSearch, transferFilter, transferOrder, transferDirection) { transferPage = 0 }
    val transferSlice = remember(displayedTransfers, transferPage) {
        ManualCollectionPager.slice(displayedTransfers, transferPage, size = 20)
    }
    var collectionRevision by remember { mutableStateOf(0) }
    var knownLocalCollections by remember { mutableStateOf<List<String>>(emptyList()) }
    val localHashes = remember(transfers) { transfers.map { it.infoHash } }
    LaunchedEffect(localHashes, collectionRevision, registeredNames) {
        knownLocalCollections = withContext(Dispatchers.IO) {
            CollectionNameRegistryRules.ordered(registeredNames + localHashes.asSequence()
                .flatMap {
                    collectionStore.forTransfer(TransferCollectionStore.LOCAL_ENGINE_SCOPE, it).asSequence()
                }.toList())
        }
    }
    var filesFor by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<LocalTransferFile>?>(null) }
    val localReviewHolder = pendingAddState ?: remember { mutableStateOf<PendingLocalAdd?>(null) }
    var pending by localReviewHolder
    var snapshotRestoreDraft by remember { mutableStateOf<LocalSnapshotStoppedRestoreDraft?>(null) }
    var snapshotBatchDraft by remember { mutableStateOf<LocalSnapshotBatchStoppedRestoreDraft?>(null) }
    var snapshotBatchRecoveryDraft by remember {
        mutableStateOf<LocalSnapshotBatchRecoveryDraft?>(null)
    }
    var snapshotPreparationRevision by remember { mutableIntStateOf(0) }
    var repairSelectionHash by remember { mutableStateOf<String?>(null) }
    var repairSelectionBusy by remember { mutableStateOf(false) }
    var repairReview by remember {
        mutableStateOf<Pair<String, SharedTorrentDocument.Reviewed>?>(null)
    }
    var addStorage by remember {
        mutableStateOf<Pair<String, LocalAddStoragePreflight>?>(null)
    }
    // Read-only foreign partial-file offer for the reviewed .torrent and exact folder.
    // Reuse requires the person's explicit choice; it resets whenever either changes.
    var addRecovery by remember {
        mutableStateOf<Pair<String, io.github.gamesbyian.chunks.engine.ForeignPartialAddPlan.Offer>?>(null)
    }
    var useRecovery by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // The latest outcome that needs attention (refused, uncertain, failed).
    // Tone is chosen where the outcome is known, never guessed from text.
    var problemOutcome by remember { mutableStateOf<String?>(null) }
    fun noteProblem(text: String) {
        message = text
        problemOutcome = text
    }
    var busy by remember { mutableStateOf(false) }
    val originalAddDocument = localAddAttemptSession?.originalDocument ?: remember { OriginalAddDocumentState() }
    // Parent-owned private source is available to all intake callbacks, including
    // the asynchronous picker. Keep it in RAM rather than Android SavedState.
    val magnetHolder = magnetDraftState ?: remember { mutableStateOf("") }
    var magnet by magnetHolder
    // One cancellable source-read owner, shared by picker, Android handoff and
    // HTTPS intake. Cancellation is read-only; it never consumes a queued URI.
    var sourceReadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val sourceReading = sourceReadJob != null || externalSourceReading
    fun cancelSourceRead() {
        val obsolete = sourceReadJob
        sourceReadJob = null
        obsolete?.cancel()
    }
    suspend fun beginSourceRead(): kotlinx.coroutines.Job {
        val current = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
        sourceReadJob?.takeUnless { it === current }?.cancel()
        sourceReadJob = current
        return current
    }
    suspend fun reviewDocument(uri: Uri, shared: Boolean): Boolean? {
        val read = beginSourceRead()
        message = if (shared) "Opening shared .torrent for review. No download has started."
            else "Opening .torrent for review. Your current draft is kept until validation finishes."
        try {
            androidx.compose.runtime.snapshotFlow { busy }.first { !it }
            val reviewed = withContext(Dispatchers.IO) { SharedTorrentDocument.read(context.contentResolver, uri) }
            if (sourceReadJob !== read) return null
            pending = PendingLocalAdd(reviewed.preview, reviewed.bytes, uri)
            // The independently validated user-selected document supersedes
            // a previously typed URL/magnet only after a successful read.
            magnet = ""
            message = if (shared) "Shared torrent reviewed locally. Confirm its destination and Add below."
                else "Torrent reviewed locally. Confirm its destination and Add below."
            return true
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (sourceReadJob !== read) return null
            val error = if (shared)
                "The shared file was unavailable, invalid or not supported v1 .torrent metadata. No torrent was added."
            else "This document is not a supported v1 .torrent file, was inaccessible or exceeds 2 MiB."
            noteProblem(error + if (pending != null) " Your previous .torrent remains selected." else "")
            return false
        } finally {
            if (sourceReadJob === read) sourceReadJob = null
        }
    }
    val progressHistory = remember { LocalProgressHistory() }
    var observedDeltas by remember {
        mutableStateOf<Map<String, LocalProgressHistory.ObservedDelta>>(emptyMap())
    }

    // The admission/observation gate is process-wide: navigating away and back
    // must not revive controls before a post-command native read succeeds.
    fun performLocalMutation(
        retainAddAfterNavigation: Boolean = false,
        operation: suspend () -> Unit
    ) {
        if (busy || inventoryFrame.stale || !LocalScreenReader.revision.beginMutation()) {
            message = "A prior native action needs a fresh confirmed transfer list before another change."
            return
        }
        // A new native mutation invalidates cross-command growth attribution.
        progressHistory.clear()
        observedDeltas = emptyMap()
        inventoryFrame = inventoryFrame.copy(stale = true)
        busy = true
        // An accepted Add must not be cancelled just because its Compose route
        // is removed. Other mutations keep their existing screen lifetime.
        val executionScope = if (retainAddAfterNavigation)
            UniversalAddExecutionScope.select(addAttemptScope, scope) else scope
        executionScope.launch {
            try { operation() } finally {
                LocalScreenReader.revision.settledMutation()
                inventoryFrame = inventoryFrame.copy(stale = true)
                busy = false
            }
        }
    }
    val localAttemptController = addAttemptController ?: remember(addAttemptScope, scope) {
        UniversalAddAttemptController { UniversalAddExecutionScope.select(addAttemptScope, scope) }
    }
    val addKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val addFocus = androidx.compose.ui.platform.LocalFocusManager.current
    fun selectTextSource(value: String) {
        cancelSourceRead()
        initialTorrentUri?.let(onTorrentConsumed)
        // A complete, explicitly entered magnet outranks a previously
        // reviewed document. For a typed URL, keep the validated document
        // until a new fetched candidate independently validates.
        if (MagnetDraftCheck.inspect(value) is MagnetDraftCheck.Valid) pending = null
        magnet = value
    }
    // A kept draft reopens its form on return, rather than hiding it.
    var addExpanded by rememberSaveable { mutableStateOf(magnet.isNotEmpty()) }
    var finishInOrderDefault by remember { mutableStateOf(finishInOrderStore.defaultForNewAdds()) }
    var magnetFinishInOrder by remember(magnet) { mutableStateOf(finishInOrderDefault) }
    LaunchedEffect(pending?.preview?.infoHashV1) {
        if (pending != null) addExpanded = true
    }
    LaunchedEffect(initialMagnet, busy, localAddAttemptSession?.attemptedTicket, sourceReading, initialTorrentUri) {
        if (busy || localAddAttemptSession?.attemptedTicket != null) return@LaunchedEffect
        val handoff = initialMagnet ?: return@LaunchedEffect
        // Exactly one outcome per handoff, so it can never wait unseen or replay later.
        // An existing typed or reviewed source is the person's work: never replace it.
        val occupied = (magnet.isNotBlank() && magnet != handoff) || pending != null ||
            sourceReading || initialTorrentUri != null
        if (occupied) {
            message = "A source is already being reviewed here, so the remote job's link was " +
                "not copied. Finish or clear this source, then copy it again."
            onPrefillConsumed()
        } else if (!reviewableLocalExternalText(handoff)) {
            message = "The remote job's link could not be reviewed here. Nothing was added."
            onPrefillConsumed()
        } else {
            selectTextSource(handoff)
            addExpanded = true
            onPrefillConsumed()
        }
    }
    // Foreground clipboard prefill: read once each time the Add form opens.
    var clipboardPrefillOn by remember { mutableStateOf<Boolean?>(null) }
    var clipboardPrefillSaving by remember { mutableStateOf(false) }
    var clipboardPrefillError by remember { mutableStateOf<String?>(null) }
    var clipboardFilled by remember { mutableStateOf<String?>(null) }
    var linkFetching by remember { mutableStateOf(false) }
    val offeredClips = remember { HashSet<String>() }
    LaunchedEffect(Unit) {
        clipboardPrefillOn = withContext(Dispatchers.IO) { ClipboardMagnetPrefill.enabled(context) }
    }
    // Returning to Chunks (for example after copying a magnet in a browser)
    // re-checks once while the Add form is open and still empty.
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Android 10+ lets an app read the clipboard only while its window has input focus, and
    // ON_RESUME arrives before focus returns: reading then is silently denied. Read once focused.
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(pane, addExpanded, clipboardPrefillOn, resumeTick, windowFocused,
        localAddAttemptSession?.attemptedTicket) {
        // The dedicated Add destination renders the form even before the
        // legacy inline disclosure is expanded. Clipboard intake is still
        // focus-gated, one-shot and never replaces a draft or pending review.
        if ((pane != LocalPane.ADD && !addExpanded) ||
            clipboardPrefillOn != true || !windowFocused ||
            busy || localAddAttemptSession?.attemptedTicket != null) return@LaunchedEffect
        // The persisted choice is authoritative. An Options opt-out commits off the main
        // thread before this screen's cached copy updates; never fill against it. The
        // preference file is already in memory from the IO read that set the cached copy.
        if (!ClipboardMagnetPrefill.enabled(context)) {
            clipboardPrefillOn = false
            return@LaunchedEffect
        }
        val filled = ClipboardMagnetPrefill.decide(
            enabled = true, currentDraft = magnet,
            otherSourcePresent = pending != null || initialMagnet != null || initialTorrentUri != null,
            clipText = ClipboardMagnetPrefill.readText(context))
        // Offer each copied magnet once: never refill one the person cleared or already added.
        if (filled != null && offeredClips.add(filled)) {
            magnet = filled
            clipboardFilled = filled
        }
    }
    val addStartHolder: androidx.compose.runtime.MutableState<Boolean> =
        addStartState ?: rememberSaveable { mutableStateOf(true) }
    var startAfterAdd by addStartHolder
    val artworkHolder = addArtworkState ?: rememberSaveable { mutableStateOf(true) }
    var automaticArtwork by artworkHolder
    val mediaController = io.github.gamesbyian.chunks.media.LocalTransferMediaController.current
    val addFolderHolder: androidx.compose.runtime.MutableState<String> =
        addFolderState ?: rememberSaveable { mutableStateOf("") }
    var addFolderDraft by addFolderHolder
    var dht by remember { mutableStateOf(LocalEngineHolder.dhtPreferred(context)) }
    var dhtSaving by remember { mutableStateOf(false) }
    var dhtError by remember { mutableStateOf<String?>(null) }
    // Both the preferences switch and a trackerless-magnet shortcut use this
    // same readback-gated transaction. Never flip the UI state optimistically.
    fun requestDhtSetting(wanted: Boolean) {
        if (dhtSaving) return
        dhtSaving = true
        dhtError = null
        scope.launch {
            val confirmed = withContext(Dispatchers.IO) {
                try { LocalEngineHolder.setDhtPreferred(context, wanted) }
                catch (_: Exception) { false }
            }
            dht = LocalEngineHolder.dhtPreferred(context)
            dhtError = if (confirmed) null else
                "DHT setting could not be confirmed. The live network state may be uncertain; " +
                "do not assume discovery is enabled or disabled until you check again."
            dhtSaving = false
        }
    }
    // The resolved default Place for new Adds, write-probed on the IO lane.
    // Choosing another Place is optional and only offered once verified.
    var placeResolution by remember { mutableStateOf<LocalPlaceResolution?>(null) }
    var placeCheck by remember { mutableIntStateOf(0) }
    val addPlaceHolder = addPlaceState ?: rememberSaveable { mutableStateOf<String?>(null) }
    var currentAddPlaceId by addPlaceHolder
    var placeSaving by remember { mutableStateOf(false) }
    // Android 10: after the user answers, re-probe; a grant alone never selects
    // Downloads, the write probe and the remembered choice still decide.
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { placeCheck++ }
    LaunchedEffect(placeCheck, resumeTick) {
        placeResolution = null
        placeResolution = withContext(Dispatchers.IO) {
            try { LocalDownloadPlaces.resolve(context) } catch (_: Exception) {
                LocalPlaceResolution(null, emptyList(),
                    "Chunks could not check local download locations.")
            }
        }
    }
    val addPlaceResolution = placeResolution?.let { LocalAddPlacePolicy.resolve(it, currentAddPlaceId) }
    val place = addPlaceResolution?.selected
    val destination = place?.root
    // The app-owned LocalNativeAddObserver follow-up (UniversalAddAttemptController)
    // resolves the exact local Add identity, not this screen. The screen only
    // explains each new retained phase once, whenever it is shown.
    val localAddObservations = localAttemptController.observationRevision(
        io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL)
    LaunchedEffect(localAddObservations, localAddAttemptSession?.attemptedTicket, busy) {
        // Before noteNativeResult has captured this Add's generation receipt
        // the phase is still the write's own; explain only settled evidence.
        if (busy) return@LaunchedEffect
        val session = localAddAttemptSession ?: return@LaunchedEffect
        val ticket = session.attemptedTicket ?: return@LaunchedEffect
        val phase = session.coordinator.phase
        if (!session.notePhaseOnce(ticket, phase)) return@LaunchedEffect
        when (phase) {
            io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.VERIFIED -> {
                message = "The new local transfer was verified in a fresh native list. Review the next Add when ready."
                // A positive native registration may already have been
                // announced to Combined. Independent readback upgrades only
                // the reviewed Place/Start outcome, never sends a second notice.
                if (session.announceVerifiedRegistrationOnce(ticket))
                    rememberAdded(ticket.intent.source.expectedInfoHash, message)
            }
            io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.SESSION_CONFIRMED ->
                noteProblem("This torrent exists on the local device at the reviewed location, but its Add origin is uncertain. No retry occurred.")
            io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED ->
                noteProblem("The local job exists at the reviewed location, but the requested payload Start is unverified. No retry occurred.")
            io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.OBSERVED_UNCERTAIN ->
                noteProblem("A matching local torrent was observed, but its Place, Start or new-Add identity is unverified. No retry occurred.")
            else -> Unit
        }
    }
    val proposedAddDestination = remember(destination, addFolderDraft) {
        LocalOwnedAddFolder.proposed(destination, addFolderDraft)
    }
    LaunchedEffect(pending?.preview?.infoHashV1, proposedAddDestination) {
        addRecovery = null
        useRecovery = false
        val review = pending
        val folder = proposedAddDestination
        if (review != null && folder != null) {
            val offer = withContext(Dispatchers.IO) {
                try {
                    io.github.gamesbyian.chunks.engine.ForeignPartialAddPlan.offer(review.bytes, folder)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) { null }
            }
            addRecovery = offer?.let { review.preview.infoHashV1 to it }
        }
    }
    // This is invoked *only* inside performLocalMutation after the existing
    // local mutation gate has been acquired, before any IO/native call.
    // Null sessions are used only by standalone UI test harnesses until the
    // app owner injects its one process-only local coordinator.
    // performLocalMutation already acquired the process-wide native gate; the
    // shared UniversalAddSubmission.admitAndRun step makes the same pure
    // admission decision as for every client, from Local's own evidence.
    fun localAddCapabilities() = io.github.gamesbyian.chunks.model.EnvironmentCapabilities.local()
    fun localAddAvailability(inventoryReadyAtClick: Boolean) = if (inventoryReadyAtClick)
        io.github.gamesbyian.chunks.model.EnvironmentAvailability.LIVE
        else io.github.gamesbyian.chunks.model.EnvironmentAvailability.STALE
    fun noteLocalAdmissionRefused(decision: io.github.gamesbyian.chunks.model.UniversalAddAdmission.Decision) =
        noteProblem("This local Add cannot be authorized: " +
            decision.explanation.removeSuffix(" Nothing was sent.") + " Nothing was sent.")
    val addFolderNameInvalid = remember(addFolderDraft) {
        LocalOwnedAddFolder.normalizedName(addFolderDraft) == null
    }
    LaunchedEffect(pending?.preview?.infoHashV1, destination) {
        addStorage = null
        val review = pending
        // A lazily created public folder may not exist yet: measure its volume.
        val folder = destination?.let { if (it.isDirectory) it else it.parentFile }
        if (review != null && folder != null) {
            val estimate = withContext(Dispatchers.IO) {
                val survey = try {
                    LocalFolderInventoryProbe.measure(folder)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) { null }
                val cap = try {
                    LocalCapPreferenceStore(context).get(folder)
                } catch (_: Exception) { null }
                // Query *the selected download volume*, not filesDir's volume.
                val free = try {
                    StatFs(folder.absolutePath).availableBytes.takeIf { it >= 0L }
                } catch (_: Exception) { null }
                LocalAddStoragePlanner.assess(review.preview.sizeBytes, survey, cap, free)
            }
            addStorage = review.preview.infoHashV1 to estimate
        }
    }
    // The ongoing notification is how Android lets downloads continue in the background.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    var restoreNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            // Consume exactly one completed native observation without awaiting JNI
            // on this coroutine. A read that never returns must not freeze the UI.
            when (val outcome = LocalScreenReader.pump.takeCompleted()) {
                is LocalScreenPollOutcome.Fresh -> {
                    val frame = outcome.value
                    // A sample scheduled before an uncertain mutation cannot
                    // clear its safety latch, even if it finishes afterward.
                    if (LocalScreenReader.revision.acceptObservation(frame.revision, frame.transfers)) {
                        if (frame.transfers != null) {
                            hasConfirmedLocalInventory = true
                            lastAcceptedInventoryAt = System.currentTimeMillis()
                        }
                        inventoryFrame = LocalInventoryFreshness.advance(inventoryFrame, frame.transfers)
                        observedDeltas = if (frame.transfers != null) {
                            progressHistory.observe(frame.transfers, SystemClock.elapsedRealtime())
                        } else {
                            progressHistory.clear()
                            emptyMap()
                        }
                        restoreNotice = frame.restoreNotice
                        if (frame.transfers != null && frame.filesHash != null &&
                            frame.filesHash == filesFor) files = frame.files
                    }
                }
                LocalScreenPollOutcome.Unavailable -> {
                    progressHistory.clear()
                    observedDeltas = emptyMap()
                    inventoryFrame = LocalInventoryFreshness.advance(inventoryFrame, null)
                }
                null -> Unit
            }
            // A hung native read cannot produce an exception or be cancelled.
            // Retain the last known queue but withhold mutations until a new,
            // genuinely successful observation eventually arrives.
            if ((LocalScreenReader.pump.inFlightMillis() ?: 0L) >= 15_000L) {
                progressHistory.clear()
                observedDeltas = emptyMap()
                inventoryFrame = LocalInventoryFreshness.advance(inventoryFrame, null)
            }

            val chosenFilesHash = filesFor
            val sampleRevision = LocalScreenReader.revision.snapshot()
            val saveDue = ++tick % 30 == 0
            LocalScreenReader.pump.tryObserve {
                try {
                    val engine = LocalEngineHolder.current() ?: LocalEngineHolder.restoreIfSaved(context)
                    val problems = engine?.restoreProblems.orEmpty()
                    val notice = if (problems.isEmpty()) null
                        else problems.size.toString() + " saved transfer record(s) could not be restored and were left untouched."
                    // A failed list is unknown, not an authoritative empty queue.
                    val observed = try { engine?.list().orEmpty() } catch (_: Exception) { null }
                    val observedFiles = chosenFilesHash?.let { hash ->
                        try { engine?.files(hash) } catch (_: Exception) { null }
                    }
                    // Persist periodically off the screen coroutine. A blocked
                    // native save occupies this same bounded observation slot.
                    if (saveDue) try { engine?.saveResumeData() } catch (_: Exception) { }
                    LocalScreenReadout(observed, chosenFilesHash, observedFiles, notice, sampleRevision)
                } catch (_: Exception) {
                    LocalScreenReadout(null, chosenFilesHash, null, "Saved transfers could not be restored.", sampleRevision)
                }
            }
            delay(1_000)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Explicit picker selection supersedes an older queued handoff. Keep
        // an already validated review until these replacement bytes validate.
        initialTorrentUri?.let(onTorrentConsumed)
        scope.launch { reviewDocument(uri, shared = false) }
    }

    val trackerRepairPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val selectedHash = repairSelectionHash
        repairSelectionHash = null
        if (uri == null || selectedHash == null) return@rememberLauncherForActivityResult
        repairSelectionBusy = true
        repairReview = null
        scope.launch {
            try {
                val reviewed = withContext(Dispatchers.IO) {
                    SharedTorrentDocument.read(context.contentResolver, uri)
                }
                if (reviewed.preview.infoHashV1 != selectedHash) {
                    noteProblem("This .torrent has a different infohash. The existing local transfer was untouched.")
                } else if (reviewed.preview.trackerCount < 1) {
                    noteProblem("That .torrent contains no usable tracker announce endpoints. Nothing changed.")
                } else {
                    repairReview = selectedHash to reviewed
                    message = "Original identity verified. Review the tracker repair below before confirming."
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                noteProblem("This .torrent was unavailable, invalid or unsupported. No tracker change occurred.")
            } finally {
                repairSelectionBusy = false
            }
        }
    }

    LaunchedEffect(initialTorrentUri, busy, localAddAttemptSession?.attemptedTicket) {
        val externalUri = initialTorrentUri ?: return@LaunchedEffect
        // Keep an incoming share queued while an earlier write is unresolved.
        if (localAddAttemptSession?.attemptedTicket != null) return@LaunchedEffect
        // A new source may arrive while an approved Add is settling. Keep it
        // queued until that operation finishes instead of letting its cleanup
        // discard the newer review. Source intake itself never authorizes Add.
        if (busy) return@LaunchedEffect
        addExpanded = true
        val terminal = reviewDocument(externalUri, shared = true)
        if (terminal != null) onTorrentConsumed(externalUri)
    }

    val scrollState = listScroll ?: rememberScrollState()
    LaunchedEffect(repairReview?.first) {
        // The source chooser is launched from a card deep in a long queue.
        // Return to the review at the top rather than hide a confirmation
        // elsewhere in the scrollable page.
        if (repairReview != null) scrollState.animateScrollTo(0)
    }
    val viewportHeightPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenHeightDp.dp.roundToPx()
    }
    val showBackToTop by remember(scrollState, viewportHeightPx, transfers.isNotEmpty()) {
        derivedStateOf {
            ReturnToTopPolicy.local(
                scrollPx = scrollState.value,
                maxScrollPx = scrollState.maxValue,
                viewportHeightPx = viewportHeightPx,
                hasTransfers = displayedTransfers.isNotEmpty()
            )
        }
    }
    val transfersRequester = remember { BringIntoViewRequester() }
    val optionsRequester = remember { BringIntoViewRequester() }
    // Local options search targets: existing panels on this screen.
    val speedRequester = remember { BringIntoViewRequester() }
    val dhtRequester = remember { BringIntoViewRequester() }
    val clipboardRequester = remember { BringIntoViewRequester() }
    val privacyRequester = remember { BringIntoViewRequester() }
    val restoreRequester = remember { BringIntoViewRequester() }
    val exportsRequester = remember { BringIntoViewRequester() }
    val namesRequester = remember { BringIntoViewRequester() }
    val originalFilesRequester = remember { BringIntoViewRequester() }
    val optionsSearchHolder = optionsSearchState ?: rememberSaveable { mutableStateOf("") }
    var localOptionsSearch by optionsSearchHolder
    val categoryHolder = optionsCategoryState ?: rememberSaveable { mutableStateOf<OptionsCategory?>(null) }
    var optionsCategory by categoryHolder
    val returnQueryHolder = optionsReturnQueryState ?: rememberSaveable { mutableStateOf("") }
    var optionsReturnQuery by returnQueryHolder
    val indexScrollHolder = optionsIndexScrollState ?: rememberSaveable { mutableStateOf(0) }
    var optionsIndexScroll by indexScrollHolder
    var optionsLaunchError by remember { mutableStateOf<String?>(null) }
    fun returnToOptionsIndex() {
        optionsLaunchError = null
        optionsCategory = null
        localOptionsSearch = optionsReturnQuery
        scope.launch {
            androidx.compose.runtime.withFrameNanos { }
            scrollState.scrollTo(optionsIndexScroll)
        }
    }
    fun openOptionsCategory(category: OptionsCategory) {
        optionsLaunchError = null
        optionsIndexScroll = scrollState.value
        optionsReturnQuery = localOptionsSearch
        localOptionsSearch = ""
        optionsCategory = category
        scope.launch { scrollState.scrollTo(0) }
    }
    androidx.activity.compose.BackHandler(pane == LocalPane.OPTIONS && optionsCategory != null) {
        returnToOptionsIndex()
    }
    var showBatchTools by rememberSaveable { mutableStateOf(false) }
    // Back from a local tool returns keyboard focus to the button that opened it.
    val seedingFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val createFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val zipFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val seedingReturnIntoView = remember { BringIntoViewRequester() }
    val createReturnIntoView = remember { BringIntoViewRequester() }
    val zipReturnIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(returnFocusTool, pane) {
        val target = when (returnFocusTool) {
            "SEEDING" -> seedingFocus
            "CREATE" -> createFocus
            "ARCHIVES" -> zipFocus
            else -> null
        } ?: return@LaunchedEffect
        // Those tool entry buttons are mounted on Options, not Transfers.
        // A return to a different pane must not steal keyboard focus later.
        if (pane != LocalPane.OPTIONS) {
            onReturnFocusConsumed()
            return@LaunchedEffect
        }
        // Ensure the entry button is laid out and visible before keyboard focus
        // is restored. ZIP review may be far below the fold even when the
        // Options scroll state was retained. A focus request on a detached or
        // offscreen target can otherwise fail silently.
        val intoView = when (returnFocusTool) {
            "SEEDING" -> seedingReturnIntoView
            "CREATE" -> createReturnIntoView
            "ARCHIVES" -> zipReturnIntoView
            else -> null
        }
        repeat(18) {
            androidx.compose.runtime.withFrameNanos { }
            if (intoView != null) runCatching { intoView.bringIntoView() }
            if (runCatching { target.requestFocus() }.getOrDefault(false)) {
                onReturnFocusConsumed()
                return@LaunchedEffect
            }
        }
        // Leave the in-process request pending if the options button never
        // became focusable. A future explicit pane mount can try again.
    }
    val addRequester = remember { BringIntoViewRequester() }
    var addNavigationRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(pane, addExpanded, addNavigationRevision) {
        if (pane == LocalPane.ADD) addRequester.bringIntoView()
    }
    // The footer occupies measured space and never overlays a transfer action.
    Column(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f)) {
    Column(
        Modifier.fillMaxSize().verticalScroll(scrollState)
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // A destination must only display its own content. No Back breadcrumb,
        // Add disclosure, or settings stack precedes the first transfer row.
        val firstRunVisible = onConnections != null && showLocalFirstRunWelcome(
            hasConfirmedLocalInventory, inventoryStale, transfers.isEmpty(),
            !showFirstRunWelcome, transferSearch, addExpanded,
            transferFilter == LocalQueueFilter.ALL,
            restoreNotice == null,
            initialTorrentUri == null && initialMagnet.isNullOrBlank() && pending == null)
        if (pane == LocalPane.TRANSFERS) {
            // An unconfigured, genuinely empty first launch needs no environment scope buttons.
            if (!firstRunVisible) FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EnvironmentBadge("This device", server = false,
                    environment = io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL,
                    modifier = Modifier.align(Alignment.CenterVertically))
                if (onCombined != null) OutlinedButton(onClick = onCombined,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("All clients") }
            }
            // This screen owns Stop/Remove outcomes. A successful native
            // deletion removes its row, but must not remove the user's
            // independently verified confirmation with it.
            message?.let { LocalOutcomeNotice(it, needsAttention = problemOutcome == it) }
        }
        if (pane == LocalPane.ADD) {
            val magnetCheck = remember(magnet) { MagnetDraftCheck.inspect(magnet) }
            val isLink = remember(magnet) { TorrentLinkCheck.inspect(magnet) !is TorrentLinkCheck.NotALink }
            val sourceEnabled = !busy && localAddAttemptSession?.attemptedTicket == null
            val add = pending
            // Files to download, chosen before Add (multi-file .torrent only); read off the UI thread.
            var addFiles by remember(add) { mutableStateOf<List<DeclaredAddFile>?>(null) }
            var skippedFiles by remember(add) { mutableStateOf<Set<Int>>(emptySet()) }
            // Reviewed Low/High per wanted file, for exactly this reviewed .torrent.
            var fileLevels by remember(add) {
                mutableStateOf<Map<Int, io.github.gamesbyian.chunks.rpc.ClientFilePriority>>(emptyMap())
            }
            var fileChoiceOpen by remember(add) { mutableStateOf(false) }
            LaunchedEffect(add) {
                val reviewedAdd = add?.takeIf { it.preview.fileCount >= 2 } ?: return@LaunchedEffect
                addFiles = withContext(Dispatchers.Default) { AddFileChoices.declared(reviewedAdd.bytes.copyOf()) }
            }
            val declaredFiles = addFiles
            var finishInOrderAfterAdd by remember(add?.preview?.infoHashV1) { mutableStateOf(finishInOrderDefault) }
            // Null until the saved default and the provider's delete support are read on IO;
            // an Add confirmed before then keeps the original untouched.
            val originalPolicy = rememberUniversalAddOriginalPolicy(add?.originalDocument, add)
            val originalHandling = originalPolicy?.handling
            val originalDeletable = originalPolicy?.deletable == true
            val seedingPreview = remember(add?.preview?.infoHashV1) {
                add?.preview?.let { localSeedingPreview(context, it) }
            }
            val canAddTorrent = LocalAddAvailability.torrentReady(
                hasReviewedTorrent = add != null, hasDestination = destination != null, busy = busy,
                reviewedMetainfoCurrent = localReviewedMetainfoIsCurrent(magnet, sourceReading),
                aliasValid = validUniversalAddAlias(addAliasState?.value.orEmpty()),
                inventoryReady = addInventoryReady, placeSaving = placeSaving,
                hasVerifiedPlace = proposedAddDestination != null,
                previousAttemptUnresolved = localAddAttemptSession?.attemptedTicket != null,
                originalDocumentDeleting = originalAddDocument.deleting)
            val addMagnet: () -> Unit = addMagnet@{
                if (sourceReadJob != null) return@addMagnet
                // Submitting closes the keyboard, so the form's collapse is not
                // racing an IME animation (see the Connect transition fix).
                addKeyboard?.hide()
                addFocus.clearFocus()
                val selectedDraft = addFolderDraft
                val selectedPlace = place
                val checkedMagnet = magnetCheck as? MagnetDraftCheck.Valid ?: return@addMagnet
                val frozenAlias = runCatching {
                    normalizedUniversalAddAlias(addAliasState?.value.orEmpty())
                }.getOrElse {
                    noteProblem("That display name contains unsupported characters or exceeds 160 characters. Nothing was added.")
                    return@addMagnet
                }
                if (LocalUniversalAddAdapter.alreadyPresentInFreshInventory(
                        checkedMagnet.infoHash, transfers, hasConfirmedLocalInventory && !inventoryStale)) {
                    noteProblem("This exact torrent already exists on this device. No second Add was sent. Open its transfer card instead.")
                    return@addMagnet
                }
                val selectedMagnet = checkedMagnet.normalized
                val autoStart = startAfterAdd
                val allowArtwork = automaticArtwork
                val orderLater = magnetFinishInOrder
                val inventoryReadyAtClick = addInventoryReady
                performLocalMutation(retainAddAfterNavigation = true) localMagnet@{
                    val selected = selectedPlace ?: run {
                        noteProblem("Choose a verified local download location before Add. Nothing was sent.")
                        return@localMagnet
                    }
                    val session = localAddAttemptSession
                    val frozenIntent = if (session == null) null else try {
                        LocalUniversalAddAdapter.magnetIntent(checkedMagnet, selected,
                            selectedDraft, autoStart, allowArtwork)
                    } catch (_: IllegalArgumentException) {
                        noteProblem("This local Add source or subfolder is invalid. Nothing was sent.")
                        return@localMagnet
                    }
                    // The one shared Add lifecycle (as rTorrent and the protocol clients):
                    // Place/artwork checks before the boundary are known no-writes; once the
                    // native call is entered, any failure keeps the attempted-write fence.
                    val nativeAdd: suspend (io.github.gamesbyian.chunks.model.UniversalAddWriteBoundary,
                        java.util.concurrent.atomic.AtomicReference<LocalAddResult?>) -> LocalAddResult =
                        { boundary, returned -> withContext(Dispatchers.IO) {
                            val preflight = prepareLocalAddPlaceAndArtwork(
                                root = LocalDownloadPlaces.materialize(selected),
                                subfolder = selectedDraft, allowArtwork = allowArtwork,
                                persistExactExclusion = {
                                    val key = localAddedKey(checkedMagnet.infoHash)
                                    key != null && mediaController?.prepareForAdd(key, false) == true
                                })
                            when (preflight) {
                                LocalAddArtworkPreflight.InvalidPlace -> LocalAddResult.Refused(
                                    "The download location or subfolder is unavailable, nonempty or unsafe. Nothing was added.")
                                LocalAddArtworkPreflight.PreferenceNotSaved -> LocalAddResult.Refused(
                                    "The artwork exclusion could not be saved. Nothing was added. Try again or change Artwork & metadata.")
                                is LocalAddArtworkPreflight.Ready -> {
                                    val engine = LocalEngineHolder.get(context)
                                    // Only after Place AND exclusion are ready can
                                    // the frozen ticket cross the native write fence.
                                    boundary.enter()
                                    engine.addMagnet(selectedMagnet, preflight.folder,
                                        autoStartAfterMetadata = autoStart).also(returned::set)
                                }
                            }
                        } }
                    var wroteNative = false
                    var ticket: io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Ticket? = null
                    try {
                        val result: LocalAddResult = if (session != null && frozenIntent != null) {
                            // The ONE shared submit step (as rTorrent and the protocol clients),
                            // awaited inside the native lane that already holds the native gate.
                            val submitted = UniversalAddSubmission.admitAndRun(localAttemptController,
                                session.coordinator, frozenIntent, localAddCapabilities(),
                                localAddAvailability(inventoryReadyAtClick), acquireMutationGate = { true },
                                submittedAtEpochMillis = System.currentTimeMillis(),
                                freeze = { minted ->
                                    session.bindPlace(minted, selected)
                                    LocalNativeAddExecutor(session, selected, { it }, nativeAdd)
                                },
                                // App-owned read-only follow-up: Place/Start verification
                                // continues after leaving Add or Activity recreation.
                                followUp = LocalNativeAddObserver(session))
                            val run = when (submitted) {
                                is UniversalAddSubmission.RunResult.Refused -> {
                                    noteLocalAdmissionRefused(submitted.decision); return@localMagnet
                                }
                                is UniversalAddSubmission.RunResult.ClientBusy -> {
                                    noteProblem("Another local Add is still settling. Nothing was sent.")
                                    return@localMagnet
                                }
                                is UniversalAddSubmission.RunResult.Ran -> { ticket = submitted.ticket; submitted.run }
                            }
                            when (run) {
                                is io.github.gamesbyian.chunks.model.UniversalAddRun.Completed -> {
                                    wroteNative = run.writeMayHaveHappened; run.result
                                }
                                is io.github.gamesbyian.chunks.model.UniversalAddRun.Failed -> {
                                    inventoryFrame = inventoryFrame.copy(stale = true)
                                    noteProblem(if (run.writeMayHaveHappened)
                                        "Magnet Add may have reached the native engine. Wait for a fresh exact transfer list; no retry occurred."
                                        else "The magnet could not be prepared for Add. Nothing was sent.")
                                    return@localMagnet
                                }
                            }
                        } else LocalNativeAddExecutor.unfenced(nativeAdd).let { (r, entered) -> wroteNative = entered; r }
                        val enteredNative = java.util.concurrent.atomic.AtomicBoolean(wroteNative)
                        if (orderLater && result is LocalAddResult.FetchingMetadata) withContext(Dispatchers.IO) {
                            finishInOrderStore.requestAfterMetadata(result.status.infoHash)
                        }
                        message = when (result) {
                            is LocalAddResult.Refused -> if (enteredNative.get())
                                "The local engine did not confirm this magnet Add after its native write boundary. " +
                                    "Check the exact local transfer list; no retry occurred."
                                else result.reason
                            is LocalAddResult.FetchingMetadata ->
                                if (autoStart) "Magnet added. Discovering metadata; payload Start must still be verified."
                                else "Magnet added in metadata-only mode. Use Start after the metadata is ready."
                            is LocalAddResult.AddedStopped -> null
                        }
                        if (result is LocalAddResult.Refused) problemOutcome = message
                        // A returned native status with this ticket's exact
                        // generation token already proves new job registration.
                        // Announce that fact once for Combined navigation, while
                        // Start and future payload remain separately unverified.
                        val registeredHash = when (result) {
                            is LocalAddResult.FetchingMetadata -> result.status.infoHash
                            is LocalAddResult.AddedStopped -> result.status.infoHash
                            is LocalAddResult.Refused -> null
                        }
                        if (registeredHash != null && (session == null ||
                            (ticket != null && session.announceNativeRegistrationOnce(ticket)))) {
                            val verifiedAlias = exactNewAddAlias(
                                frozenAlias, checkedMagnet.infoHash, registeredHash)
                            if (verifiedAlias != null) {
                                if (aliasStore.saveVerifiedNewAddAlias(
                                        TransferCollectionStore.LOCAL_ENGINE_SCOPE,
                                        registeredHash, verifiedAlias)) {
                                    aliasRevision++
                                    addAliasState?.let { aliasDraft ->
                                        if (aliasDraft.value == frozenAlias) aliasDraft.value = ""
                                    }
                                } else message = message.orEmpty() +
                                    " Magnet registered but its Chunks display name was not saved."
                            }
                            rememberAdded(registeredHash, message)
                        }
                        if (result !is LocalAddResult.Refused) {
                            automaticArtwork = true
                            if (autoStart) {
                                try { LocalEngineService.start(context) } catch (_: Exception) {
                                    noteProblem("Magnet admitted, but Android did not start the background service. Keep Chunks open.")
                                }
                            }
                            magnet = ""
                            addFolderDraft = ""
                            currentAddPlaceId = null
                            addExpanded = false
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        // Only post-Add bookkeeping can fail here: the lifecycle already
                        // settled the ticket from what the native engine returned.
                        inventoryFrame = inventoryFrame.copy(stale = true)
                        noteProblem("Magnet Add may have reached the native engine. Wait for a fresh exact transfer list; no retry occurred.")
                    }
                }
            }
            val addTorrent: () -> Unit = confirm@{
                val add = add ?: return@confirm
                if (!localReviewedMetainfoIsCurrent(magnet, sourceReading) || pending !== add) return@confirm
                // Freeze on the explicit Add click before native admission or
                // any suspension. Editing a different draft cannot redirect a
                // previously verified job's Chunks-only list name.
                val frozenAlias = runCatching {
                    normalizedUniversalAddAlias(addAliasState?.value.orEmpty())
                }.getOrElse {
                    noteProblem("That display name contains unsupported characters or exceeds 160 characters. Nothing was added.")
                    return@confirm
                }
                if (LocalUniversalAddAdapter.alreadyPresentInFreshInventory(
                        add.preview.infoHashV1, transfers, hasConfirmedLocalInventory && !inventoryStale)) {
                    noteProblem("This exact torrent already exists on this device. No second Add was sent. Open its transfer card instead.")
                    return@confirm
                }
                val selectedDraft = addFolderDraft
                val selectedPlace = place
                val autoStart = startAfterAdd
                val allowArtwork = automaticArtwork
                // Frozen at the click: native index -> reviewed path of each raised/lowered file.
                val frozenLevels = addFiles.orEmpty()
                    .filter { it.index !in skippedFiles && fileLevels[it.index] != null }
                    .associate { it.index to (it.path to fileLevels.getValue(it.index)) }
                // Finish in Order manages every file's priority itself: only without the
                // person's own Low/High choices.
                val orderAfterAdd = finishInOrderAfterAdd && frozenLevels.isEmpty()
                val inventoryReadyAtClick = addInventoryReady
                // Frozen at the click: only this exact reviewed offer may be renamed.
                val reviewedRecovery = addRecovery?.takeIf { useRecovery && it.first == add.preview.infoHashV1 }?.second
                var recoveryNote: String? = null
                var orderNote: String? = null
                var verifyAfterAdd: String? = null
                // Frozen at the click: native index -> reviewed path of each file to skip.
                val frozenSkips = addFiles.orEmpty().filter { it.index in skippedFiles }.associate { it.index to it.path }
                var skipNote: String? = null
                var priorityNote: String? = null
                var skipConfirmed = true
                if (addFiles != null && frozenSkips.size >= addFiles.orEmpty().size && frozenSkips.isNotEmpty()) {
                    noteProblem("Choose at least one file to download. Nothing was added.")
                    return@confirm
                }
                // Frozen with the Add click: only the reviewed user document, never a URL.
                val frozenOriginal = add.originalDocument
                val frozenHandling = if (frozenOriginal != null) originalHandling ?: OriginalTorrentHandling.KEEP
                    else OriginalTorrentHandling.KEEP
                val frozenDeletable = frozenOriginal != null && originalDeletable
                originalAddDocument.note = null
                var submittedBytes: ByteArray? = null
                performLocalMutation(retainAddAfterNavigation = true) localDocument@{
                    val selected = selectedPlace ?: run {
                        noteProblem("Choose a verified local download location before Add. Nothing was sent.")
                        return@localDocument
                    }
                    val session = localAddAttemptSession
                    val frozenIntent = if (session == null) null else try {
                        // Read-only validation can parse up to 2 MiB of metainfo.
                        // Keep it off the UI thread; authorization remains on
                        // the UI lane after one immutable byte snapshot is ready.
                        withContext(Dispatchers.Default) {
                            LocalUniversalAddAdapter.torrentIntent(add, selected,
                                selectedDraft, autoStart, allowArtwork)
                        }
                    } catch (_: IllegalArgumentException) {
                        noteProblem("This .torrent source or subfolder is invalid. Nothing was sent.")
                        return@localDocument
                    }
                    // Set once by the shared submit step's freeze, before the native call.
                    var admitted: io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Ticket? = null
                    // The one shared Add lifecycle (as rTorrent and the protocol clients):
                    // Place/artwork/partial-file checks before the boundary are known
                    // no-writes; after the native call, any failure keeps the fence.
                    val nativeAdd: suspend (io.github.gamesbyian.chunks.model.UniversalAddWriteBoundary,
                        java.util.concurrent.atomic.AtomicReference<LocalAddResult?>) ->
                        Pair<LocalAddResult, io.github.gamesbyian.chunks.engine.LocalCommandResult?> =
                        { boundary, returned -> withContext(Dispatchers.IO) {
                            val preflight = prepareLocalAddPlaceAndArtwork(
                                root = LocalDownloadPlaces.materialize(selected),
                                subfolder = selectedDraft, allowArtwork = allowArtwork,
                                persistExactExclusion = {
                                    val key = localAddedKey(add.preview.infoHashV1)
                                    key != null && mediaController?.prepareForAdd(key, false) == true
                                })
                            when (preflight) {
                                LocalAddArtworkPreflight.InvalidPlace -> LocalAddResult.Refused(
                                    "The download location or subfolder is unavailable, nonempty or unsafe. Nothing was added.") to null
                                LocalAddArtworkPreflight.PreferenceNotSaved -> LocalAddResult.Refused(
                                    "The artwork exclusion could not be saved. Nothing was added. Try again or change Artwork & metadata.") to null
                                is LocalAddArtworkPreflight.Ready -> {
                                    val engine = LocalEngineHolder.get(context)
                                    // The shared ticket freezes the exact reviewed
                                    // bytes: never execute with the mutable screen
                                    // holder after authorization and a suspend gap.
                                    val authorizedBytes = admitted?.intent?.source?.metainfoForSubmission()
                                        ?: add.bytes
                                    // Explicitly approved foreign partial files are renamed
                                    // (never replaced) before the checked Add verifies pieces.
                                    val adoption = io.github.gamesbyian.chunks.engine.ForeignPartialAddPlan
                                        .adoptReviewed(authorizedBytes, preflight.folder, reviewedRecovery)
                                    if (adoption is io.github.gamesbyian.chunks.engine.ForeignPartialAddPlan.Adoption.Refused)
                                        return@withContext LocalAddResult.Refused(
                                            "Partial files were not reused: " + adoption.problem +
                                                ". Nothing was added. Review the folder and try again.") to null
                                    boundary.enter()
                                    submittedBytes = authorizedBytes
                                    // The ONE native Local Add sequence (shared with the
                                    // shared-port LocalTorrentClient): checked stopped Add →
                                    // reviewed skips matched to the job's own index and path →
                                    // Finish in Order while stopped → Start only if confirmed,
                                    // else "Stopped after verification" of existing files.
                                    val execution = LocalAddExecution.torrent(
                                        engine, authorizedBytes, preflight.folder,
                                        skipsRequested = frozenSkips.isNotEmpty(),
                                        reviewedSkips = { files -> AddFileChoices.reviewedSkips(files, frozenSkips) },
                                        startNow = autoStart,
                                        reviewedPriorities = frozenLevels.takeIf { it.isNotEmpty() }?.let { levels ->
                                            { files ->
                                                AddFileChoices.reviewedSkips(files, levels.mapValues { it.value.first })
                                                    ?.associate { file -> file.index to
                                                        LocalTorrentClient.nativePriority(levels.getValue(file.index).second) }
                                            }
                                        },
                                        beforeStart = { hash ->
                                            if (orderAfterAdd) orderNote = enableFinishInOrderAfterAdd(context, engine, hash)
                                        },
                                        onAdded = returned::set)
                                    val added = execution.added
                                    if (adoption is io.github.gamesbyian.chunks.engine.ForeignPartialAddPlan.Adoption.Renamed) {
                                        val count = adoption.receipt.renames.size
                                        recoveryNote = if (added is LocalAddResult.Refused) {
                                            if (io.github.gamesbyian.chunks.engine.ForeignPartialAddPlan.undo(preflight.folder, adoption))
                                                "The partial files were given back their original names."
                                            else "Some partial files keep the torrent's file names; nothing was deleted."
                                        } else "Reusing " + count + (if (count == 1) " partial file" else " partial files") +
                                            " from another app. Chunks checks every piece and downloads only what is missing."
                                    }
                                    if (frozenSkips.isNotEmpty() && added is LocalAddResult.AddedStopped) {
                                        skipConfirmed = execution.skipsConfirmed
                                        skipNote = AddFileChoices.outcomeNote(execution.skipOutcome, frozenSkips.size, autoStart)
                                    }
                                    if (frozenLevels.isNotEmpty() && added is LocalAddResult.AddedStopped) {
                                        if (!AddFileChoices.confirmed(execution.priorityOutcome)) skipConfirmed = false
                                        priorityNote = AddFileChoices.priorityNote(
                                            execution.priorityOutcome, frozenLevels.size, autoStart)
                                    }
                                    if (execution.verifyingExisting) verifyAfterAdd = added.let {
                                        (it as LocalAddResult.AddedStopped).status.infoHash
                                    }
                                    added to execution.start
                                }
                            }
                        } }
                    var wroteNative = false
                    var ticket: io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Ticket? = null
                    try {
                        val result = if (session != null && frozenIntent != null) {
                            val submitted = UniversalAddSubmission.admitAndRun(localAttemptController,
                                session.coordinator, frozenIntent, localAddCapabilities(),
                                localAddAvailability(inventoryReadyAtClick), acquireMutationGate = { true },
                                submittedAtEpochMillis = System.currentTimeMillis(),
                                freeze = { minted ->
                                    session.bindPlace(minted, selected)
                                    admitted = minted
                                    LocalNativeAddExecutor(session, selected, { it.first }, nativeAdd)
                                },
                                followUp = LocalNativeAddObserver(session))
                            val run = when (submitted) {
                                is UniversalAddSubmission.RunResult.Refused -> {
                                    noteLocalAdmissionRefused(submitted.decision); return@localDocument
                                }
                                is UniversalAddSubmission.RunResult.ClientBusy -> {
                                    noteProblem("Another local Add is still settling. Nothing was sent.")
                                    return@localDocument
                                }
                                is UniversalAddSubmission.RunResult.Ran -> { ticket = submitted.ticket; submitted.run }
                            }
                            when (run) {
                                is io.github.gamesbyian.chunks.model.UniversalAddRun.Completed -> {
                                    wroteNative = run.writeMayHaveHappened; run.result
                                }
                                is io.github.gamesbyian.chunks.model.UniversalAddRun.Failed -> {
                                    inventoryFrame = inventoryFrame.copy(stale = true)
                                    noteProblem(if (run.writeMayHaveHappened)
                                        "Torrent Add may have reached the native engine. Check the next fresh exact transfer list; no retry occurred."
                                        else "The .torrent could not be prepared for Add. Nothing was sent.")
                                    return@localDocument
                                }
                            }
                        } else LocalNativeAddExecutor.unfenced(nativeAdd).let { (r, entered) -> wroteNative = entered; r }
                        val enteredNative = java.util.concurrent.atomic.AtomicBoolean(wroteNative)
                        val (added, started) = result
                        message = when (added) {
                            is LocalAddResult.Refused -> if (enteredNative.get())
                                "The native local engine did not confirm this torrent Add after entering its write boundary. " +
                                    "Check the exact local transfer list; no retry occurred."
                                else added.reason
                            is LocalAddResult.FetchingMetadata -> "Metadata is still loading."
                            is LocalAddResult.AddedStopped -> when (started?.outcome) {
                                LocalCommandOutcome.VERIFIED -> {
                                    val serviceOk = try {
                                        LocalEngineService.start(context)
                                        true
                                    } catch (_: Exception) { false }
                                    if (serviceOk) "Added and started. Check peer availability and downloaded bytes in the transfer list."
                                    else "Added and started, but Android refused background service. Keep Chunks open."
                                }
                                LocalCommandOutcome.REFUSED ->
                                    "Added stopped, but Start was refused because the destination contains unreviewed files. Inspect the storage warning and choose a safe destination."
                                LocalCommandOutcome.UNCERTAIN -> {
                                    inventoryFrame = inventoryFrame.copy(stale = true)
                                    "Added, but Start was not confirmed. Check the next local transfer list before trying again."
                                }
                                LocalCommandOutcome.NOT_FOUND ->
                                    "Added but could not locate the torrent for Start. Inspect the list before retrying."
                                null -> if (verifyAfterAdd != null)
                                    "Added stopped. Checking the files already in this folder; it stops by itself " +
                                        "when the check ends and downloads nothing until you press Start."
                                else "Added stopped. Start manually from its transfer card."
                            }
                        }
                        recoveryNote?.let { note -> message = listOfNotNull(message, note).joinToString(" ") }
                        orderNote?.let { note -> message = listOfNotNull(message, note).joinToString(" ") }
                        skipNote?.let { note -> message = listOfNotNull(message, note).joinToString(" ") }
                        priorityNote?.let { note -> message = listOfNotNull(message, note).joinToString(" ") }
                        if (added is LocalAddResult.Refused || (added is LocalAddResult.AddedStopped &&
                                started != null && started.outcome != LocalCommandOutcome.VERIFIED) ||
                            (added is LocalAddResult.AddedStopped && !skipConfirmed))
                            problemOutcome = message
                        if (added is LocalAddResult.AddedStopped && (session == null ||
                            (ticket != null && session.announceNativeRegistrationOnce(ticket)))) {
                            val verifiedAlias = exactNewAddAlias(frozenAlias,
                                add.preview.infoHashV1, added.status.infoHash)
                            if (verifiedAlias != null) {
                                if (aliasStore.saveVerifiedNewAddAlias(
                                        TransferCollectionStore.LOCAL_ENGINE_SCOPE,
                                        added.status.infoHash, verifiedAlias)) {
                                    aliasRevision++
                                    addAliasState?.let { aliasDraft ->
                                        if (aliasDraft.value == frozenAlias) aliasDraft.value = ""
                                    }
                                } else message = message.orEmpty() +
                                    " The transfer was added but its Chunks display name was not saved."
                            }
                            // Source file handling only after THIS ticket's positively read-back
                            // new native registration; a refused, duplicate or uncertain Add keeps it.
                            val original = frozenOriginal
                            val verifiedBytes = submittedBytes
                            if (original != null && verifiedBytes != null && session != null && ticket != null &&
                                frozenHandling != OriginalTorrentHandling.KEEP &&
                                added.status.infoHash.equals(add.preview.infoHashV1, ignoreCase = true)) {
                                val retired = OriginalSourceRetirement.afterAdd(context,
                                    RemoteAddSession.FrozenOriginalHandling(original, frozenHandling,
                                        frozenDeletable, verifiedBytes),
                                    io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL.value,
                                    added.status.infoHash)
                                originalAddDocument.deletion = retired.deletion
                                originalAddDocument.note = retired.note
                            }
                            rememberAdded(added.status.infoHash, message)
                        }
                        if (added !is LocalAddResult.Refused) {
                            automaticArtwork = true
                            pending = null
                            addFolderDraft = ""
                            currentAddPlaceId = null
                            addExpanded = false
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        // Only post-Add bookkeeping can fail here: the lifecycle already
                        // settled the ticket from what the native engine returned.
                        inventoryFrame = inventoryFrame.copy(stale = true)
                        noteProblem("Torrent Add may have reached the native engine. Check the next fresh exact transfer list; no retry occurred.")
                    }
                }
            }
            val hasMagnetDraft = magnet.isNotEmpty() && !isLink
            // Expose the actual admission prerequisite, not raw source, path or engine detail.
            val magnetUnavailable = LocalAddAvailability.magnetUnavailableReason(
                busy = busy, sourceReading = sourceReading, inventoryReady = addInventoryReady,
                settingsSaving = dhtSaving || placeSaving, hasVerifiedPlace = proposedAddDestination != null,
                magnetValid = magnetCheck is MagnetDraftCheck.Valid,
                aliasValid = validUniversalAddAlias(addAliasState?.value.orEmpty()),
                previousAttemptUnresolved = localAddAttemptSession?.attemptedTicket != null)
            val disabledReason = when {
                localAddAttemptSession?.attemptedTicket != null -> "Finish the previous Add before adding another."
                isLink -> "Get the .torrent from this link first (Get torrent from link above)."
                else -> "Add a magnet or torrent link, or open a .torrent, to continue."
            }
            UniversalAddForm(
                capabilities = UniversalAddClientCapabilities.LOCAL,
                source = {
                    // The one shared source-first intake (as rTorrent and the protocol clients).
                    UniversalAddSourceSection(
                        text = magnet,
                        onTextChange = { selectTextSource(it.trim()) },
                        enabled = sourceEnabled,
                        onOpenTorrent = { picker.launch(arrayOf("*/*")) },
                        readingTorrent = sourceReading,
                        linkFetching = linkFetching,
                        // Read-only source validation does not require a local Place.
                        // Actual Add still requires the existing fresh inventory/Place gates.
                        // An existing verified review remains selected until the replacement fetch
                        // succeeds and validates; refusal must never require discarding it first.
                        onFetchLink = fetch@{ link ->
                            if (!sourceEnabled || sourceReading) return@fetch
                            initialTorrentUri?.let(onTorrentConsumed)
                            linkFetching = true
                            scope.launch {
                                val read = beginSourceRead()
                                try {
                                    androidx.compose.runtime.snapshotFlow { busy }.first { !it }
                                    val result = withContext(Dispatchers.IO) { fetchTorrentDocument(link) }
                                    if (sourceReadJob !== read) return@launch
                                    when (result) {
                                        is TorrentLinkResult.Fetched -> {
                                            // The same reviewed .torrent Add as the picker and Android share.
                                            pending = PendingLocalAdd(result.preview, result.bytes)
                                            magnet = ""
                                            clipboardFilled = null
                                            message = "Torrent from " + result.display +
                                                " is ready to review below. Nothing has been added."
                                        }
                                        is TorrentLinkResult.Refused -> noteProblem(result.message)
                                    }
                                } finally {
                                    if (sourceReadJob === read) sourceReadJob = null
                                    linkFetching = false
                                }
                            }
                        },
                        onPaste = { selectTextSource(it) },
                        onNothingToPaste = { message = UniversalAddSourceText.NOTHING_TO_PASTE },
                        modifier = Modifier.bringIntoViewRequester(addRequester)
                    ) {
                        if (clipboardFilled != null && magnet == clipboardFilled) ClipboardPrefillNote(
                            onClear = { magnet = ""; clipboardFilled = null },
                            enabled = !busy && !sourceReading && localAddAttemptSession?.attemptedTicket == null)
                    }
                    if (add != null && magnet.isNotBlank()) {
                        Text("A newer torrent source is entered. Resolve or clear it before adding the previously selected .torrent.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (add != null) {
                        val selection = if (magnet.isBlank()) "Selected .torrent" else "Previously selected .torrent"
                        Text("$selection: ${add.preview.name}", fontWeight = FontWeight.Bold)
                    }
                    if (add != null && canReviewOnConnectedServer &&
                        onPrepareRemoteTorrent != null) {
                        ReviewedTorrentTargetChooser(
                            verifiedTorrentBytes = add.bytes,
                            localPlaceAvailable = destination != null,
                            enabled = !busy && !sourceReading && localAddAttemptSession?.attemptedTicket == null,
                            onPrepareServerReview = prepare@{ original ->
                                if (sourceReadJob != null || pending !== add) return@prepare
                                // The parent only enables this action for an authenticated,
                                // independently privacy-gated remote target.
                                if (onPrepareRemoteTorrent(original, add)) pending = null
                                else message = "The current Add review changed or is still in progress. Review again."
                            }
                        )
                    }
                },
                destination = { addDestinationPicker?.invoke() },
                saveTo = {
                    LocalDownloadPlacePicker(
                        resolution = addPlaceResolution,
                        enabled = !busy && !inventoryStale && !placeSaving &&
                            localAddAttemptSession?.attemptedTicket == null,
                        currentAdd = true,
                        defaultResolution = placeResolution,
                        onChoose = { choice -> currentAddPlaceId = LocalAddPlacePolicy.identity(choice.place) },
                        onUseDefault = { currentAddPlaceId = null },
                        onSaveDefault = { choice ->
                            placeSaving = true
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    try {
                                        // Recheck the exact root, including removable-volume identity.
                                        LocalDownloadPlaces.choices(context).singleOrNull {
                                            LocalAddPlacePolicy.identity(it.place) == LocalAddPlacePolicy.identity(choice.place) && it.verified
                                        }?.let { LocalDownloadPlaces.remember(context, it) } == true
                                    } catch (_: Exception) { false }
                                }
                                if (saved) {
                                    placeCheck++
                                    message = "Local default saved for future Adds. Existing transfers keep their files where they are."
                                } else noteProblem("That download location could not be saved. Check local download locations before trying again.")
                                placeSaving = false
                            }
                        },
                        onUseAutomatic = {
                            placeSaving = true
                            scope.launch {
                                val forgotten = withContext(Dispatchers.IO) {
                                    try { LocalDownloadPlaces.forget(context) } catch (_: Exception) { false }
                                }
                                if (forgotten) {
                                    placeCheck++
                                    message = "New local downloads use the automatic location. Existing transfers keep their files where they are."
                                } else noteProblem("The saved download location could not be cleared. Check local download locations before trying again.")
                                placeSaving = false
                            }
                        },
                        onRequestStoragePermission = {
                            storagePermission.launch(LocalDownloadPlaces.ANDROID_10_STORAGE_PERMISSIONS)
                        }
                    )
                    OutlinedTextField(
                        value = addFolderDraft,
                        onValueChange = { addFolderDraft = it },
                        label = { Text("Optional local download subfolder") },
                        placeholder = { Text("Leave blank for Chunks default") },
                        singleLine = true,
                        // A folder name is typed exactly: no autocorrect or auto-capitalisation.
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        // Only the typed name can be wrong; an unresolved download location is
                        // reported separately ("Checking where this device can save…").
                        isError = addFolderNameInvalid,
                        enabled = !busy && !inventoryStale && localAddAttemptSession?.attemptedTicket == null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Optional. Leave blank to save directly in the download location above. " +
                        "A nonempty folder is refused; nothing is created until Add is confirmed.",
                        style = MaterialTheme.typography.bodySmall)
                    if (addFolderNameInvalid)
                        Text("Use one plain folder name, without slashes, dot-prefixed names, " +
                            "control characters or path traversal.",
                            color = MaterialTheme.colorScheme.error)
                    else if (proposedAddDestination != null)
                        Text("Planned local Place: " + proposedAddDestination.absolutePath,
                            style = MaterialTheme.typography.bodySmall)
                    if (placeResolution != null && destination == null)
                        Text("Local device storage for Chunks is unavailable right now.",
                            color = MaterialTheme.colorScheme.error)
                },
                start = UniversalAddToggle(startAfterAdd, sourceEnabled && !inventoryStale) { startAfterAdd = it },
                files = { capabilities ->
                    if (capabilities.fileChoices && magnet.isBlank() && add != null && destination != null && declaredFiles != null && declaredFiles.size >= 2)
                        AddFileChoice(declaredFiles, skippedFiles, fileChoiceOpen,
                            enabled = !busy && !inventoryStale && localAddAttemptSession?.attemptedTicket == null,
                            onExpanded = { fileChoiceOpen = it },
                            localOnlyNote = canReviewOnConnectedServer && onPrepareRemoteTorrent != null,
                            // This device stores every level.
                            priorityLevels = listOf(io.github.gamesbyian.chunks.rpc.ClientFilePriority.HIGH,
                                io.github.gamesbyian.chunks.rpc.ClientFilePriority.LOW).filter { it in capabilities.priorityLevels },
                            priorities = fileLevels.filterKeys { it !in skippedFiles },
                            onPriorityChange = { index, level ->
                                fileLevels = if (level == null) fileLevels - index else fileLevels + (index to level)
                            },
                            onSkippedChange = { skippedFiles = it; fileLevels = fileLevels - it })
                },
                artwork = UniversalAddToggle(automaticArtwork, sourceEnabled) { automaticArtwork = it },
                artworkExpandedState = addArtworkExpandedState,
                originalHandling = originalHandling?.takeIf {
                    magnet.isBlank() && add?.originalDocument != null && destination != null
                },
                onBack = onLeaveAdd,
                options = {
                    if (magnetCheck is MagnetDraftCheck.Valid) Text(
                        "Seeding: your device Seeding rules choose a policy when this magnet's metadata " +
                            "arrives (its trackers and private flag are known then); otherwise the local device default applies.",
                        style = MaterialTheme.typography.bodySmall)
                    if (magnet.isNotEmpty() && !isLink) LocalMagnetDiscoveryPrompt(
                        magnet = magnet,
                        dhtEnabled = dht,
                        applying = dhtSaving,
                        blocked = busy || inventoryStale,
                        onEnable = {
                            if (onOpenOptions != null) {
                                localOptionsSearch = "dht"
                                onOpenOptions()
                            } else requestDhtSetting(true)
                        },
                        opensOptions = onOpenOptions != null
                    )
                    if (magnet.isNotEmpty() && destination != null) {
                        FinishInOrderAddChoice(
                            "Finish in Order once the file list arrives (episodes or numbered files)",
                            checked = magnetFinishInOrder,
                            enabled = !busy && !inventoryStale && localAddAttemptSession?.attemptedTicket == null,
                            onChecked = { magnetFinishInOrder = it },
                            defaultOn = finishInOrderDefault,
                            onDefault = { on -> if (finishInOrderStore.setDefaultForNewAdds(on)) finishInOrderDefault = on }
                        )
                        Text("Saves into the download location shown above. Magnets first discover metadata via peers/trackers; if Start after Add is selected, payload downloading begins after metadata arrives. Without peers, downloading cannot begin.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (add != null && magnet.isBlank() && destination != null && add.preview.fileCount >= 2) {
                        FinishInOrderAddChoice(
                            "Finish in Order: prioritise the first episode or numbered file, then the next " +
                                "(set up before downloading starts; change or turn off from Files)",
                            checked = finishInOrderAfterAdd,
                            enabled = !busy && !inventoryStale && localAddAttemptSession?.attemptedTicket == null,
                            onChecked = { finishInOrderAfterAdd = it },
                            defaultOn = finishInOrderDefault,
                            onDefault = { on -> if (finishInOrderStore.setDefaultForNewAdds(on)) finishInOrderDefault = on }
                        )
                    }
                    if (add != null && destination != null && magnet.isBlank()) LocalAddReview(
                        add.preview, proposedAddDestination ?: destination,
                        placeTitle = place?.title,
                        busy = !canAddTorrent,
                        startAfterAdd = startAfterAdd,
                        cancelEnabled = !busy && localAddAttemptSession?.attemptedTicket == null,
                        storagePreflight = addStorage?.takeIf { it.first == add.preview.infoHashV1 }?.second,
                        seeding = seedingPreview,
                        recovery = addRecovery?.takeIf { it.first == add.preview.infoHashV1 }?.second,
                        useRecovery = useRecovery,
                        onUseRecovery = { useRecovery = it },
                        onCancel = cancel@{
                            if (busy || (pending !== add && sourceReadJob == null)) return@cancel
                            cancelSourceRead()
                            pending = null
                            initialTorrentUri?.let(onTorrentConsumed)
                            currentAddPlaceId = null
                            addFolderDraft = ""
                        })
                },
                submit = when {
                    hasMagnetDraft -> UniversalAddAction(magnetUnavailable == null,
                        onSubmit = addMagnet, unavailableReason = magnetUnavailable)
                    add != null && magnet.isBlank() -> UniversalAddAction(canAddTorrent, onSubmit = addTorrent)
                    else -> UniversalAddAction(false, onSubmit = {}, unavailableReason = disabledReason)
                },
                guidance = {
                    if (!hasMagnetDraft && (add == null || magnet.isNotBlank()))
                        Text(disabledReason, style = MaterialTheme.typography.bodySmall)
                },
                status = {
                    localAddAttemptSession?.attemptedTicket?.let { ticket ->
                        val phase = localAddAttemptSession.coordinator.phase
                        // The same client-neutral receipt vocabulary is used by rTorrent.
                        // A local registration token is positive new-Add evidence; a matching
                        // hash seen after an uncertain write is not.
                        val display = UniversalAddDisplayProjector.from(
                            phase = phase,
                            clientLabel = "Local",
                            submitting = busy,
                            registrationFromThisSubmission = localAddAttemptSession.registrationFromThisSubmission
                        )
                        if (display != null) UniversalAddReceiptCard(
                            receipt = display,
                            needsAttention = display.stage in setOf(
                                io.github.gamesbyian.chunks.model.UniversalAddDisplayStage.UNCONFIRMED,
                                io.github.gamesbyian.chunks.model.UniversalAddDisplayStage.START_UNVERIFIED
                            )
                        )
                        if (phase == io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.VERIFIED &&
                            onViewAddTransfer != null) {
                            UniversalAddContinuationAction(ChunksSemantics.ADD_VIEW_TRANSFER, "View transfer") {
                                onViewAddTransfer(ticket.intent.source.expectedInfoHash)
                            }
                        }
                        // The same read-only Check status as the protocol clients: one exact
                        // native list read of this ticket, never a second Add.
                        val localEnvironment = io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL
                        if (!busy && phase in io.github.gamesbyian.chunks.model.UniversalAddLifecycle.UNRESOLVED) {
                            val checkingNow = localAttemptController.isChecking(localEnvironment)
                            OutlinedButton(onClick = {
                                localAttemptController.recheck(localAddAttemptSession.coordinator,
                                    LocalNativeAddObserver(localAddAttemptSession)) { readback ->
                                    if (readback == null) noteProblem(
                                        "The local transfer list could not be read. Nothing was sent; check again later.")
                                    else if (readback == io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Readback.NOT_OBSERVED)
                                        noteProblem("Local does not list this transfer yet. Nothing was resent.")
                                }
                            }, enabled = !checkingNow,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag(ChunksSemantics.ADD_CHECK_STATUS)) {
                                Text(if (checkingNow) "Checking Local…" else "Check status")
                            }
                        }
                        if (phase == io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.VERIFIED ||
                            phase == io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.SESSION_CONFIRMED ||
                            phase == io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED) {
                            if (phase == io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED)
                                Text("Acknowledging does not confirm payload Start or repeat Add. Inspect the existing transfer before starting it separately.",
                                    style = MaterialTheme.typography.bodySmall)
                            UniversalAddNextAction(phase, enabled = !busy && !originalAddDocument.deleting) {
                                val done = if (phase ==
                                    io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED)
                                    localAddAttemptSession.acknowledgeObservedJob()
                                else localAddAttemptSession.finishReviewedAttempt()
                                if (done) {
                                    pending = null
                                    magnet = ""
                                    addFolderDraft = ""
                                    currentAddPlaceId = null
                                    addExpanded = true
                                    message = if (phase ==
                                        io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED)
                                        "Existing local job acknowledged with Start unverified. No retry was sent; review a new source explicitly."
                                    else "Previous local Add review closed. Choose your next source explicitly."
                                }
                            }
                        }
                    }
                    // Outcomes include successes ("Added and started…") as well as refusals,
                    // so present them as a neutral, politely announced status rather than
                    // colouring every result as an error. The wording carries the outcome.
                    message?.let { text ->
                        // Only an Add outcome whose exact hash is in the observed
                        // inventory can offer to show it; refusals never do.
                        val newHash = addedOutcome?.takeIf { it.first == text }?.second
                            ?.takeIf { hash -> transfers.any { it.infoHash.equals(hash, ignoreCase = true) } }
                        LocalOutcomeNotice(text,
                            needsAttention = problemOutcome == text,
                            actionLabel = if (newHash != null && onViewAddTransfer == null) "Show new transfer" else null,
                            onAction = {
                                if (newHash != null) {
                                    // View only: search for the exact hash and show all states.
                                    transferSearch = newHash
                                    chooseLocalFilter(LocalQueueFilter.ALL)
                                    scope.launch { transfersRequester.bringIntoView() }
                                }
                            })
                    }
                    restoreNotice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (inventoryStale) {
                        Text("Local torrent status unavailable. Showing the last confirmed transfers. " +
                            "Start, Stop, Remove, file selection, new Adds and library publication are disabled " +
                            "until a fresh engine status read succeeds.",
                            color = MaterialTheme.colorScheme.error)
                    }

                    OriginalAddDocumentStatus(originalAddDocument, addAttemptScope ?: scope)
                }
            )
            // Keep the general engine explanation below the user's Add action.
            LocalEngineAbout()
            repairReview?.let { prepared ->
                val (hash, source) = prepared
                val observed = transfers.singleOrNull { it.infoHash == hash }
                val eligible = observed?.stopped == true && observed.metadataKnown &&
                    observed.trackers?.total == 0 && !inventoryStale && !busy &&
                    !repairSelectionBusy
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Review missing-tracker repair",
                            style = MaterialTheme.typography.titleMedium)
                        Text("Original: " + source.preview.name + " · " +
                            source.preview.trackerCount + " usable tracker(s)")
                        Text("Matching exact infohash: " + hash,
                            style = MaterialTheme.typography.bodySmall)
                        Text("Only the missing tracker list in this stopped local torrent " +
                            "will be restored, then saved to its existing resume record. " +
                            "No downloaded files, destination, file priorities, or running " +
                            "state are changed. Tracker addresses may contain private " +
                            "passkeys and can disclose your IP if you later Start. " +
                            "Repair never starts a torrent or makes an rTorrent request.",
                            style = MaterialTheme.typography.bodySmall)
                        if (!eligible) {
                            Text("The local queue changed, became stale, or the transfer " +
                                "already has trackers or is running. The repair cannot proceed.",
                                color = MaterialTheme.colorScheme.error)
                        }
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    // One confirmed native operation. The engine repeats
                                    // all identity/stop/empty-tracker checks atomically.
                                    repairReview = null
                                    performLocalMutation {
                                        try {
                                            val result = withContext(Dispatchers.IO) {
                                                LocalEngineHolder.current()
                                                    ?.repairMissingTrackers(hash, source.bytes)
                                                    ?: LocalTrackerRepairOutcome.NOT_FOUND
                                            }
                                            message = when (result) {
                                                LocalTrackerRepairOutcome.VERIFIED ->
                                                    "Missing trackers restored and verified in the " +
                                                    "saved local session. The torrent is still stopped; " +
                                                    "press Start separately when ready."
                                                LocalTrackerRepairOutcome.REFUSED ->
                                                    "Tracker repair refused: the original or current " +
                                                    "stopped transfer did not satisfy the safety checks."
                                                LocalTrackerRepairOutcome.NOT_FOUND ->
                                                    "That torrent is no longer in the local device engine."
                                                LocalTrackerRepairOutcome.UNCERTAIN -> {
                                                    inventoryFrame = inventoryFrame.copy(stale = true)
                                                    "Tracker repair or resume persistence was not verified. " +
                                                    "Refresh the local device list before making another change; " +
                                                    "do not blindly retry."
                                                }
                                            }
                                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) {
                                            inventoryFrame = inventoryFrame.copy(stale = true)
                                            noteProblem("Tracker repair was not confirmed. Check a fresh " +
                                                "local status before any other change.")
                                        }
                                    }
                                },
                                enabled = eligible, modifier = Modifier.weight(1f)
                                    .heightIn(min = 48.dp)
                            ) { Text("Confirm repair") }
                            OutlinedButton(
                                onClick = { repairReview = null },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) { Text("Cancel") }
                        }
                    }
                }
            }

        snapshotRestoreDraft?.let { draft ->
            val eligible = hasConfirmedLocalInventory && !inventoryStale && !busy && !sourceReading &&
                pending == null && place != null &&
                draft.frozenHashes == transfers.map { it.infoHash } &&
                LocalScreenReader.revision.snapshot() == draft.frozenGeneration
            LocalSnapshotStoppedRestoreReview(
                draft = draft,
                selectedPlace = place?.title ?: "No verified local download location",
                eligible = eligible,
                onCancel = { if (snapshotRestoreDraft === draft) snapshotRestoreDraft = null },
                onConfirm = {
                    val selected = place
                    if (selected == null || !eligible || sourceReadJob != null || snapshotRestoreDraft !== draft) {
                        noteProblem("The destination or transfer list changed. Reopen the snapshot.")
                    } else {
                        snapshotRestoreDraft = null
                        performLocalMutation {
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    // Checking an interrupted restore must not
                                    // lazily create directories or write-probe
                                    // the destination. Only the explicit Add
                                    // journey may materialize a Place.
                                    val folder = (if (draft.recoveryOnly) {
                                        selected.root.takeIf { it.isDirectory &&
                                            !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                                    } else LocalDownloadPlaces.materialize(selected))
                                        ?: return@withContext SnapshotRestoreOnceV1.Outcome.REFUSED
                                    val targetIdentity = LocalSnapshotRestoreFingerprint.of(
                                        "chunks-local-v1:" + context.filesDir.canonicalPath)
                                    val placeIdentity = LocalSnapshotRestoreFingerprint.of(
                                        "chunks-place-v1:" + folder.canonicalPath)
                                    val store = AndroidSnapshotRestoreJournalStore(context)
                                    val itemArchive = SnapshotRestoreOnceV1.itemArchiveFingerprint(
                                        draft.archiveFingerprint, draft.original.infoHashV1)
                                    val hashes = listOf(draft.original.infoHashV1)
                                    // A recovery check must NEVER create a
                                    // missing new REVIEW journal or re-enter Add.
                                    val journal = if (draft.recoveryOnly)
                                        SnapshotRestoreJournalV1.openExisting(
                                            store, itemArchive, targetIdentity, hashes)
                                    else SnapshotRestoreJournalV1.open(
                                        store, itemArchive, targetIdentity, hashes)
                                    val target = SnapshotRestoreOnceV1.localEngine(
                                        LocalEngineHolder.get(context))
                                    if (draft.recoveryOnly) SnapshotRestoreOnceV1.reconcilePending(
                                        journal, draft.original.infoHashV1, folder,
                                        placeIdentity, target)
                                    else SnapshotRestoreOnceV1.execute(
                                        journal = journal,
                                        reviewed = draft.original,
                                        chosenPlace = folder,
                                        verifiedPlaceFingerprint = placeIdentity,
                                        frozenHashes = draft.frozenHashes,
                                        userApproved = true,
                                        target = target
                                    )
                                } catch (cancel: kotlinx.coroutines.CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED
                                }
                            }
                            message = if (draft.recoveryOnly) when (result) {
                                SnapshotRestoreOnceV1.Outcome.VERIFIED_PRESENT_STOPPED ->
                                    "The previous stopped restore is confirmed at the selected " +
                                    "local destination. No Add, Start or payload transfer occurred."
                                SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED ->
                                    "No matching pending restore journal was found for this " +
                                    "snapshot and local destination. No transfer was changed."
                                SnapshotRestoreOnceV1.Outcome.REFUSED ->
                                    "That local folder is unavailable. Choose the previously " +
                                    "reviewed destination before checking again."
                                else ->
                                    "The previous restore remains unconfirmed. The transfer " +
                                    "must be present, stopped and at the reviewed destination. " +
                                    "No Add or Start was repeated."
                            } else when (result) {
                                SnapshotRestoreOnceV1.Outcome.VERIFIED_PRESENT_STOPPED ->
                                    "Snapshot definition restored on this device, confirmed stopped. " +
                                    "No downloaded data was moved. Press Start separately."
                                SnapshotRestoreOnceV1.Outcome.REFUSED ->
                                    "Restore refused: verify the current local download Place and " +
                                    "original torrent before another review."
                                SnapshotRestoreOnceV1.Outcome.STALE_TARGET ->
                                    "Local inventory changed. Nothing was submitted; compare again."
                                SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED ->
                                    "Restore journal is already pending, invalid, or could not be " +
                                    "durably committed. No new Add was authorized. Review the " +
                                    "existing transfer list; never blindly repeat an uncertain Add."
                                SnapshotRestoreOnceV1.Outcome.UNCERTAIN ->
                                    "Restore Add may have reached the local device engine, but independent " +
                                    "stopped-state confirmation failed. Check a fresh transfer list " +
                                    "and journal status before any explicit recovery action."
                            }
                        }
                    }
                }
            )
        }

        snapshotBatchDraft?.let { draft ->
            val eligible = hasConfirmedLocalInventory && !inventoryStale && !busy &&
                !sourceReading && sourceReadJob == null && pending == null &&
                place != null && draft.frozenHashes == transfers.map { it.infoHash } &&
                LocalScreenReader.revision.snapshot() == draft.frozenGeneration
            LocalSnapshotBatchStoppedRestoreReview(
                draft = draft,
                selectedPlace = place?.title ?: "No verified local download location",
                eligible = eligible,
                onCancel = {
                    if (snapshotBatchDraft === draft) snapshotBatchDraft = null
                },
                onConfirm = {
                    val selected = place
                    if (selected == null || !eligible ||
                        snapshotBatchDraft !== draft || sourceReadJob != null) {
                        noteProblem("The local destination or transfer list changed. " +
                            "Reopen the snapshot and review the batch again.")
                    } else {
                        snapshotBatchDraft = null
                        performLocalMutation {
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    val folder = LocalDownloadPlaces.materialize(selected)
                                        ?: return@withContext SnapshotLocalBatchRestoreV1.Result(
                                            draft.originals.size, 0, null,
                                            SnapshotRestoreOnceV1.Outcome.REFUSED)
                                    val targetIdentity = LocalSnapshotRestoreFingerprint.of(
                                        "chunks-local-v1:" + context.filesDir.canonicalPath)
                                    val placeIdentity = LocalSnapshotRestoreFingerprint.of(
                                        "chunks-place-v1:" + folder.canonicalPath)
                                    val restoreJob = kotlinx.coroutines.currentCoroutineContext()[
                                        kotlinx.coroutines.Job]
                                    SnapshotLocalBatchRestoreV1.execute(
                                        reviewed = draft.originals,
                                        archiveFingerprint = draft.archiveFingerprint,
                                        targetFingerprint = targetIdentity,
                                        chosenPlace = folder,
                                        verifiedPlaceFingerprint = placeIdentity,
                                        frozenHashes = draft.frozenHashes,
                                        userApproved = true,
                                        store = AndroidSnapshotRestoreJournalStore(context),
                                        target = SnapshotRestoreOnceV1.localEngine(
                                            LocalEngineHolder.get(context)),
                                        mayContinue = { restoreJob?.isActive != false }
                                    )
                                } catch (cancel: kotlinx.coroutines.CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    SnapshotLocalBatchRestoreV1.Result(
                                        draft.originals.size, 0, null,
                                        SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED)
                                }
                            }
                            message = if (result.complete) {
                                "All ${result.verified} snapshot definitions were verified " +
                                    "on this device at the selected folder, stopped. No " +
                                    "payload was moved. Press Start separately."
                            } else {
                                val why = when (result.stop) {
                                    SnapshotRestoreOnceV1.Outcome.REFUSED ->
                                        "the selected folder or source was refused"
                                    SnapshotRestoreOnceV1.Outcome.STALE_TARGET ->
                                        "the Local inventory changed"
                                    SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED ->
                                        "the durable restore journal blocked another Add"
                                    SnapshotRestoreOnceV1.Outcome.UNCERTAIN ->
                                        "the last Add could not be independently confirmed"
                                    else -> "the next restore could not be verified"
                                }
                                val consequence = when (result.stop) {
                                    SnapshotRestoreOnceV1.Outcome.UNCERTAIN ->
                                        "The last Add may have reached the engine but is not confirmed. "
                                    SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED ->
                                        "An earlier pending Add may need read-only recovery. "
                                    else -> "The blocked item was not submitted by this batch. "
                                }
                                val blocked = result.stoppedAtInfoHash?.take(12)?.let {
                                    " Affected infohash: " + it + "."
                                }.orEmpty()
                                "Batch stopped: ${result.verified} of ${result.total} " +
                                    "verified stopped. Chunks halted because " + why +
                                    "." + blocked + " " + consequence +
                                    "No later Add was attempted. Refresh and review " +
                                    "before taking another action."
                            }
                        }
                    }
                }
            )
        }

        snapshotBatchRecoveryDraft?.let { draft ->
            val eligible = hasConfirmedLocalInventory && !inventoryStale && !busy &&
                !sourceReading && pending == null && place != null &&
                draft.frozenHashes == transfers.map { it.infoHash } &&
                LocalScreenReader.revision.snapshot() == draft.frozenGeneration
            LocalSnapshotBatchRecoveryReview(
                draft = draft,
                selectedPlace = place?.title ?: "No previously reviewed local destination",
                eligible = eligible,
                onCancel = {
                    if (snapshotBatchRecoveryDraft === draft)
                        snapshotBatchRecoveryDraft = null
                },
                onConfirm = {
                    val selected = place
                    if (!eligible || selected == null ||
                        sourceReadJob != null || snapshotBatchRecoveryDraft !== draft) {
                        noteProblem("The Local inventory or selected destination changed. " +
                            "Refresh the snapshot comparison before checking again.")
                    } else {
                        snapshotBatchRecoveryDraft = null
                        scope.launch {
                            val read = beginSourceRead()
                            try {
                                val report = withContext(Dispatchers.IO) {
                                    val folder = selected.root.takeIf {
                                        it.isDirectory &&
                                            !java.nio.file.Files.isSymbolicLink(it.toPath())
                                    }
                                    if (folder == null) {
                                        SnapshotLocalBatchRecoveryV1.Report(
                                            emptyList(),
                                            SnapshotLocalBatchRecoveryV1.Blocker.DESTINATION_UNAVAILABLE)
                                    } else {
                                        try {
                                            val targetIdentity = LocalSnapshotRestoreFingerprint.of(
                                                "chunks-local-v1:" + context.filesDir.canonicalPath)
                                            val placeIdentity = LocalSnapshotRestoreFingerprint.of(
                                                "chunks-place-v1:" + folder.canonicalPath)
                                            SnapshotLocalBatchRecoveryV1.inspect(
                                                hashes = draft.hashes,
                                                archiveFingerprint = draft.archiveFingerprint,
                                                targetFingerprint = targetIdentity,
                                                chosenPlace = folder,
                                                verifiedPlaceFingerprint = placeIdentity,
                                                frozenTargetHashes = draft.frozenHashes,
                                                store = AndroidSnapshotRestoreJournalStore(context),
                                                target = SnapshotRestoreOnceV1.localEngine(
                                                    LocalEngineHolder.get(context)),
                                                mayContinue = { read.isActive }
                                            )
                                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) {
                                            SnapshotLocalBatchRecoveryV1.Report(
                                                emptyList(),
                                                SnapshotLocalBatchRecoveryV1.Blocker.INVALID_REVIEW)
                                        }
                                    }
                                }
                                if (sourceReadJob !== read) return@launch
                                message = if (!report.completed) {
                                    val explanation = when (report.blocker) {
                                        SnapshotLocalBatchRecoveryV1.Blocker.DESTINATION_UNAVAILABLE ->
                                            "the original local folder is unavailable"
                                        SnapshotLocalBatchRecoveryV1.Blocker.TARGET_CHANGED ->
                                            "the Local inventory changed during the check"
                                        SnapshotLocalBatchRecoveryV1.Blocker.CANCELLED ->
                                            "the check was interrupted"
                                        else -> "the saved archive or review identity did not match"
                                    }
                                    "Previous restores could not be confirmed because " +
                                        explanation + ". Nothing was added or retried. " +
                                        "Refresh the snapshot comparison and check again."
                                } else {
                                    val unresolvedHash = report.items.firstOrNull {
                                        it.state in setOf(
                                            SnapshotLocalBatchRecoveryV1.State.UNRESOLVED,
                                            SnapshotLocalBatchRecoveryV1.State.JOURNAL_UNAVAILABLE,
                                            SnapshotLocalBatchRecoveryV1.State.DIFFERENT_REVIEWED_PLACE)
                                    }?.infoHashV1?.take(12)
                                    "Previous stopped restore check: " +
                                        "${report.verifiedCount} confirmed at this folder; " +
                                        "${report.unresolvedCount} unresolved or unavailable journals; " +
                                        "${report.reviewOnlyCount} review-only records." +
                                        (unresolvedHash?.let {
                                            " First unresolved infohash: " + it + "."
                                        } ?: "") +
                                        " No torrent Add, Start or Stop was sent. " +
                                        "Unresolved attempts require separate review; " +
                                        "none was retried."
                                }
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                if (sourceReadJob === read)
                                    noteProblem("The previous batch check could not complete. " +
                                        "Nothing was added or retried.")
                            } finally {
                                if (sourceReadJob === read) sourceReadJob = null
                            }
                        }
                    }
                }
            )
        }

        } // Add presentation
        if (pane == LocalPane.TRANSFERS) {
        moveServer?.let { server ->
            val digest = TransferMoveStore.scopeDigest(server.aliasScope)
            val pending = remember(transfers, moveRevision, digest) {
                moveStore.all(TransferMove.Direction.LOCAL_TO_SERVER).filter { record ->
                    record.stage == TransferMove.Stage.TARGET_VERIFIED && record.serverScopeDigest == digest &&
                        transfers.any { it.infoHash.equals(record.infoHash, ignoreCase = true) &&
                            TransferMove.sourceIdentityMatches(record, it.name, it.nativeReviewToken) }
                }
            }
            BatchMoveRemovalPanel(
                pending = pending, serverScopeDigest = digest,
                serverLabel = TransferMoveStore.hostLabel(server.aliasScope),
                busy = busy || inventoryStale,
                observeTarget = { hash -> server.observe(hash) },
                removeSource = { hash ->
                    withContext(Dispatchers.IO) {
                        val original = pending.singleOrNull {
                            it.infoHash.equals(hash, ignoreCase = true)
                        } ?: error("Original Move journal missing")
                        val engine = LocalEngineHolder.current() ?: error("Local engine unavailable")
                        val live = engine.status(hash) ?: error("Original local job absent")
                        if (!TransferMove.sourceIdentityMatches(
                                original, live.name, live.nativeReviewToken))
                            error("The original local generation was replaced")
                        if (!engine.removeKeepingDataIfMatching(
                                hash, original.name, live.savePath,
                                requireNotNull(original.sourceReviewToken)))
                            error("The original local job could not be removed safely")
                    }
                },
                observeSource = { hash -> TransferMoveObservations.local(hash) },
                save = { record -> moveStore.save(record) },
                forget = { record -> moveStore.remove(record) },
                onFinished = { text -> message = text; moveRevision++ }
            )
            OrphanMoveRecoveryPanel(
                direction = TransferMove.Direction.LOCAL_TO_SERVER,
                serverAliasScope = server.aliasScope,
                visibleSourceHashes = transfers.map { it.infoHash },
                enabled = !busy && !inventoryStale && hasConfirmedLocalInventory,
                revision = moveRevision,
                observeSource = { hash -> TransferMoveObservations.local(hash) },
                onChanged = {
                    message = "Only the resolved local Move reminder was cleared. " +
                        "Torrent jobs and downloaded files were not changed."
                    moveRevision++
                }
            )
        }
        // Batch selection acts on the list itself, so it sits with the list (as on
        // rTorrent) and near the outcome/confirmation area above it. With no
        // local transfers there is nothing to select, so it is not shown.
        if (showBatchTools && transfers.isNotEmpty()) LocalBatchCollectionPanel(
            transfers = transfers,
            shown = displayedTransfers,
            inventoryStale = inventoryStale || busy,
            store = collectionStore,
            onMembershipChanged = {
                registeredNames = nameRegistry.names(TransferCollectionStore.LOCAL_ENGINE_SCOPE)
                collectionRevision++
            },
            copyServerLabel = serverCopy?.hostLabel,
            onBatchCopyToServer = serverCopy?.let { link ->
                { ids ->
                    val items = transfers.filter { it.infoHash in ids }
                        .map { io.github.gamesbyian.chunks.model.BatchRemoteCopy.Item(it.infoHash, it.name) }
                    performLocalMutation {
                        val problem = io.github.gamesbyian.chunks.model.BatchRemoteCopy.validate(items)
                        message = if (problem != null) problem else try {
                            val report = io.github.gamesbyian.chunks.model.BatchRemoteCopy.run(items,
                                exportTorrent = { hash ->
                                    withContext(Dispatchers.IO) {
                                        LocalEngineHolder.current()?.exportTorrentFile(hash)
                                    }
                                },
                                addStopped = link.addStoppedAtDefault)
                            report.summary() + report.results
                                .filter { it.second != io.github.gamesbyian.chunks.model.BatchRemoteCopy.Result.ADDED }
                                .take(10).joinToString("") { (item, result) ->
                                    "\n• " + item.name.take(80) + ": " + result.label
                                }
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            "The batch copy stopped unexpectedly. Check the remote list before " +
                                "copying again; nothing is retried automatically."
                        }
                    }
                }
            },
            onBatchMoveToServer = if (serverCopy == null || moveServer == null) null else { ids ->
                val link = serverCopy
                val server = moveServer
                val items = transfers.filter { it.infoHash in ids }
                    .map { io.github.gamesbyian.chunks.model.BatchRemoteCopy.Item(it.infoHash, it.name) }
                performLocalMutation {
                    val problem = io.github.gamesbyian.chunks.model.BatchRemoteCopy.validate(items)
                    message = if (problem != null) problem else try {
                        val report = io.github.gamesbyian.chunks.model.BatchRemoteCopy.run(items,
                            exportTorrent = { hash ->
                                withContext(Dispatchers.IO) { LocalEngineHolder.current()?.exportTorrentFile(hash) }
                            },
                            addStopped = link.addStoppedAtDefault)
                        // Only destination jobs verified by readback (or already present) start a Move.
                        val verified = report.results.filter {
                            it.second == io.github.gamesbyian.chunks.model.BatchRemoteCopy.Result.ADDED ||
                                it.second == io.github.gamesbyian.chunks.model.BatchRemoteCopy.Result.ALREADY_ON_REMOTE_CLIENT
                        }
                        var aliasCopied = 0
                        var aliasFailed = 0
                        var missingReadback = 0
                        val journaled = withContext(Dispatchers.IO) {
                            var confirmed = 0
                            for ((item, result) in verified) {
                                // The Add adapter's classification does not by
                                // itself prove the exact remote account still
                                // owns this job. Read back independently before
                                // persisting any source-retirement authority.
                                val observed = try {
                                    server.observe(item.infoHash)
                                } catch (cancel: kotlinx.coroutines.CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    TransferMove.Observation.Unknown
                                }
                                if (!io.github.gamesbyian.chunks.model.BatchRemoteCopy
                                        .observedDestinationForMove(result, observed)) {
                                    missingReadback++
                                    continue
                                }
                                val original = transfers.singleOrNull {
                                    it.infoHash.equals(item.infoHash, ignoreCase = true) &&
                                        it.name == item.name && it.nativeReviewToken.isNotBlank()
                                } ?: continue
                                val saved = moveStore.save(TransferMove.start(
                                    TransferMove.Direction.LOCAL_TO_SERVER,
                                    item.infoHash, item.name, TransferMoveStore.scopeDigest(server.aliasScope),
                                    TransferMoveStore.hostLabel(server.aliasScope),
                                    sourceReviewToken = original.nativeReviewToken
                                ).copy(stage = TransferMove.Stage.TARGET_VERIFIED))
                                if (!saved) continue
                                confirmed++
                                when (aliasStore.carryVerifiedMoveAlias(
                                    TransferCollectionStore.LOCAL_ENGINE_SCOPE,
                                    server.aliasScope, item.infoHash)) {
                                    TransferAliasStore.MoveAliasCarry.COPIED -> aliasCopied++
                                    TransferAliasStore.MoveAliasCarry.FAILED -> aliasFailed++
                                    else -> Unit
                                }
                            }
                            confirmed
                        }
                        moveRevision++
                        if (aliasCopied > 0) aliasRevision++
                        report.summary() + " $journaled ready to finish moving: review removal of the " +
                            "original local jobs below." +
                            (if (aliasCopied > 0) " $aliasCopied display aliases carried to the client view." else "") +
                            (if (aliasFailed > 0) " $aliasFailed display aliases could not be carried; edit them on the client view." else "") +
                            (if (missingReadback > 0) " $missingReadback destination jobs could not be independently confirmed on this exact remote client. Their original local jobs were kept; check the destination before a new Move review." else "")
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        "The batch move stopped before removal. Check the remote list; nothing was removed " +
                            "and nothing is retried automatically."
                    }
                }
            },
            onBatchLifecycle = { ids, start ->
                val targets = transfers.filter { it.infoHash in ids }
                    .map { LocalBatchLifecycle.Target(it.infoHash, it.stopped) }
                performLocalMutation {
                    val (report, unconfirmedStops) = withContext(Dispatchers.IO) {
                        val engine = LocalEngineHolder.get(context)
                        val confirmedStops = ArrayList<String>()
                        val result = try {
                            LocalBatchLifecycle.run(targets, start) { hash, wanted ->
                                val outcome = if (wanted) engine.resume(hash).outcome
                                    else engine.pause(hash).outcome
                                if (!wanted && outcome == LocalCommandOutcome.VERIFIED)
                                    confirmedStops += hash
                                outcome
                            }
                        } catch (_: IllegalArgumentException) { null }
                        // A verified Stop is not a committed post-Stop resume checkpoint.
                        // Share one deadline across the whole selection, including a
                        // partially completed batch. Never replay any Stop on failure.
                        val budget = io.github.gamesbyian.chunks.model.LocalStoppedCheckpointBudget()
                        val unconfirmed = if (start) 0 else confirmedStops.count { hash ->
                            !budget.confirm(hash, engine::saveResumeDataFor)
                        }
                        result to unconfirmed
                    }
                    message = if (report == null) "This selection cannot be changed as a batch. Nothing changed."
                    else LocalBatchLifecycle.describe(report) +
                        io.github.gamesbyian.chunks.model.LocalStoppedCheckpointBudget.warning(unconfirmedStops)
                    if (start && (report?.verified ?: 0) > 0) {
                        try { LocalEngineService.start(context) } catch (_: Exception) {
                            message += " Android refused the background service; keep Chunks open."
                        }
                    }
                }
            }
        )
        // Search, batch and filter/sort controls have no targets on genuine first use.
        // Retain them whenever inventory is unknown, filtered, stale or populated.
        if (!firstRunVisible) {
        // Compact controls immediately precede the inventory. All batch tools
        // stay collapsed until explicitly requested.
        ClearableTransferSearchField(
            value = transferSearch,
            onValueChange = { transferSearch = it },
            label = "Search transfers on this device"
        )
        OutlinedButton(onClick = { showBatchTools = !showBatchTools },
            modifier = Modifier.heightIn(min = 48.dp)
                .semantics { stateDescription = if (showBatchTools) "Expanded" else "Collapsed" }) {
            Text(if (showBatchTools) "Hide batch tools" else "Batch tools")
        }
        OutlinedButton(
            onClick = { showListOptions = !showListOptions },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = if (showListOptions) "Expanded" else "Collapsed" }
        ) {
            Text((if (showListOptions) "Hide" else "Choose") + " filters & sort · " +
                transferFilter.label + " · " + transferOrder.label + " · " +
                transferOrder.directionLabel(transferDirection))
        }
        if (showListOptions) {
        // Wrapping chips keeps the final filter/sort option reachable by
        // vertical scroll, keyboard and TalkBack at 320dp and 200% type.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LocalQueueFilter.entries.forEach { choice ->
                FilterChip(
                    selected = transferFilter == choice,
                    onClick = { chooseLocalFilter(choice) },
                    label = { Text(choice.label) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
        Text("Sort by", style = MaterialTheme.typography.labelLarge)
        // Wrapping chips keeps the final filter/sort option reachable by
        // vertical scroll, keyboard and TalkBack at 320dp and 200% type.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LocalQueueOrder.entries.forEach { choice ->
                FilterChip(
                    selected = transferOrder == choice,
                    // A new key starts in its natural direction; reselecting keeps the current one.
                    onClick = { if (transferOrder != choice)
                        chooseTransferSort(choice, choice.naturalDirection) },
                    label = { Text(choice.label) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
        TransferSortDirectionButton(
            sortName = transferOrder.label,
            directionLabel = transferOrder.directionLabel(transferDirection),
            onReverse = { chooseTransferSort(transferOrder,
                TransferSortOrdering.reversed(transferDirection)) }
        )
        }
        } // !firstRunVisible: end transfer search, batch, filter and sort tools
        val addedHidden = remember(lastAddedHash, lastAddedView, transfers, displayedTransfers,
            transferSearch, transferFilter) {
            lastAddedView == transferSearch + "\u0000" + transferFilter.name &&
                LocalAddedVisibility.hiddenByView(lastAddedHash, transfers.map { it.infoHash },
                    displayedTransfers.map { it.infoHash })
        }
        if (addedHidden) {
            LocalAddedHiddenNotice(onShowAll = {
                transferSearch = ""
                chooseLocalFilter(LocalQueueFilter.ALL)
            })
        }
        // An empty local says so once, instead of "Showing 0 of 0" above it.
        if (transfers.isNotEmpty())
            Text("Showing ${displayedTransfers.size} of ${transfers.size} transfers on this device",
                style = MaterialTheme.typography.bodySmall)
        if (transfers.isEmpty()) {
            // The welcome introduction follows the header directly, without empty-list tools.
            if (firstRunVisible) NoTransfersWelcome(
                onAdd = { if (onOpenAdd != null) onOpenAdd.invoke()
                    else { addExpanded = true; addNavigationRevision++ } },
                onConnections = onConnections!!
            )
            when {
                !hasConfirmedLocalInventory -> Text("Loading transfers on this device…", style = MaterialTheme.typography.bodySmall)
                inventoryStale -> Text("The transfer list on this device is not current.", style = MaterialTheme.typography.bodySmall)
                !firstRunVisible -> Text("No transfers on this device yet.", style = MaterialTheme.typography.bodySmall)
            }
        } else if (displayedTransfers.isEmpty()) {
            Text("No transfers match this search or filter.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { transferSearch = ""; chooseLocalFilter(LocalQueueFilter.ALL) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text("Clear search and filter") }
        } else {
            // Only 20 heavy cards are composed at a time. Search, filtering and
            // sorting run against an immutable observed frame, never native JNI.
            LocalTransferPageControls(
                page = transferSlice,
                onPrevious = { transferPage = (transferSlice.pageIndex - 1).coerceAtLeast(0) },
                onNext = { transferPage = transferSlice.pageIndex + 1 }
            )
        }
        transferSlice.items.forEach { transfer ->
            val displayedAlias = remember(transfer.infoHash, aliasRevision) {
                aliasStore.get(TransferCollectionStore.LOCAL_ENGINE_SCOPE, transfer.infoHash)
            }
            val prepareRemoteHandoff: (() -> Unit)? = onPrepareRemote?.let { send ->
                    {
                        scope.launch {
                            // Prefer the complete .torrent (identity + trackers):
                            // rTorrent cannot find peers for a bare hash unless
                            // DHT happens to be usable on that server.
                            val document = onPrepareRemoteTorrent?.let {
                                withContext(Dispatchers.IO) {
                                    try {
                                        LocalEngineHolder.current()?.exportTorrentFile(transfer.infoHash)
                                            ?.takeIf { bytes ->
                                                TorrentMetainfoReader.preview(bytes).infoHashV1
                                                    .equals(transfer.infoHash, ignoreCase = true)
                                            }
                                    } catch (_: Exception) { null }
                                }
                            }
                            val prepared = TorrentIdentityHandoff.prepare(transfer.infoHash, transfer.name)
                            when {
                                document != null -> {
                                    if (!onPrepareRemoteTorrent(document, null))
                                        message = "Finish the current Add review before preparing another source."
                                }
                                prepared != null -> send(prepared)
                                else -> message = "This torrent has no supported v1 identity to hand off."
                            }
                        }
                    }
                }
            LocalTransferCard(transfer, busy || inventoryStale, dhtEnabled = dht,
                stale = inventoryStale,
                // Same-list relations; opening one filters this list to that exact local job.
                similar = {
                    val source = remember(transfers, inventoryStale) { localSimilarSource(transfers, inventoryStale) }
                    source.transfers.firstOrNull { it.key.infoHash == transfer.infoHash.lowercase() }?.let { self ->
                        SimilarTransfersHost(self, listOf(source),
                            environmentLabel = { "This local" },
                            environmentColor = { ChunksColors.Cyan },
                            onOpen = { key -> transferSearch = key.infoHash })
                    }
                },
                onRepairTrackers = if (transfer.stopped && transfer.metadataKnown &&
                    transfer.trackers?.total == 0) ({
                    repairReview = null
                    repairSelectionHash = transfer.infoHash
                    message = null
                    trackerRepairPicker.launch(arrayOf("*/*"))
                }) else null,
                observedGrowth = observedDeltas[transfer.infoHash].takeIf {
                    !inventoryStale && !busy
                },
                seeding = seedingAssignments.get(transfer.infoHash),
                alias = displayedAlias,
                onSaveAlias = { proposed ->
                    val saved = try {
                        aliasStore.save(TransferCollectionStore.LOCAL_ENGINE_SCOPE,
                            transfer.infoHash, proposed)
                    } catch (_: Exception) { false }
                    if (saved) aliasRevision++
                    saved
                },
                onToggle = {
                    if (transfer.stopped && Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    performLocalMutation {
                        try {
                            val (outcome, stoppedCheckpoint) = withContext(Dispatchers.IO) {
                                val engine = LocalEngineHolder.get(context)
                                if (transfer.stopped) {
                                    engine.resume(transfer.infoHash) to null
                                } else {
                                    val stopped = engine.pause(transfer.infoHash)
                                    val saved = if (stopped.outcome == LocalCommandOutcome.VERIFIED)
                                        engine.saveResumeDataFor(transfer.infoHash) else null
                                    stopped to saved
                                }
                            }
                            var serviceProblem: String? = null
                            if (transfer.stopped && outcome.outcome in setOf(
                                    LocalCommandOutcome.VERIFIED, LocalCommandOutcome.UNCERTAIN)) {
                                try { LocalEngineService.start(context) } catch (_: Exception) {
                                    serviceProblem = "Android did not allow background running; keep Chunks open."
                                }
                            }
                            message = when (outcome.outcome) {
                                LocalCommandOutcome.VERIFIED ->
                                    if (stoppedCheckpoint == false)
                                        "Stopped on this device, but its recovery checkpoint could not be confirmed. " +
                                            "Keep Chunks open for the next save; downloaded files remain untouched."
                                    else serviceProblem
                                LocalCommandOutcome.REFUSED ->
                                    "Start was refused: unreviewed destination files may be overwritten. Inspect the transfer's storage warning; no Start was issued."
                                LocalCommandOutcome.UNCERTAIN -> {
                                    inventoryFrame = inventoryFrame.copy(stale = true)
                                    "The engine has not confirmed the change yet. Wait for fresh status before trying again."
                                }
                                LocalCommandOutcome.NOT_FOUND -> "This transfer is no longer in the local device engine."
                            }
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            // The command may have reached libtorrent before the read or JNI call failed.
                            // Do not invite another write until a successful inventory read.
                            inventoryFrame = inventoryFrame.copy(stale = true)
                            noteProblem("Start/Stop was not confirmed. Wait for fresh local torrent status before trying again.")
                        }
                    }
                },
                files = files.takeIf { filesFor == transfer.infoHash },
                filesShown = filesFor == transfer.infoHash,
                onShowFiles = {
                    files = null
                    filesFor = if (filesFor == transfer.infoHash) null else transfer.infoHash
                },
                onFileWanted = { index, wanted ->
                    performLocalMutation {
                        try {
                            val outcome = withContext(Dispatchers.IO) {
                                LocalEngineHolder.current()?.setFileWanted(transfer.infoHash, index, wanted)
                                    ?: LocalCommandOutcome.NOT_FOUND
                            }
                            if (outcome != LocalCommandOutcome.VERIFIED) {
                                inventoryFrame = inventoryFrame.copy(stale = true)
                            }
                            message = localFileWantedFeedback(outcome)
                            files = withContext(Dispatchers.IO) { LocalEngineHolder.current()?.files(transfer.infoHash) }
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            inventoryFrame = inventoryFrame.copy(stale = true)
                            files = null
                            noteProblem("File selection or readback failed. Wait for fresh status before trying again.")
                        }
                    }
                },
                finishInOrder = { shownFiles ->
                    FinishInOrderPanel(transfer.infoHash, shownFiles, busy || inventoryStale,
                        onPrioritiesChanged = {
                            scope.launch {
                                files = try {
                                    withContext(Dispatchers.IO) {
                                        LocalEngineHolder.current()?.files(transfer.infoHash)
                                    }
                                } catch (cancel: kotlinx.coroutines.CancellationException) {
                                    throw cancel
                                } catch (_: Exception) { null }
                            }
                        })
                },
                // The one-time focus would fight a rolling policy, so it is
                // only offered while Finish in Order is off for this transfer.
                onFocusEpisodes = if (finishInOrderStore.get(transfer.infoHash) != null) null
                    else { reviewedPlan ->
                    performLocalMutation {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                LocalEngineHolder.current()?.focusEpisodesOnce(
                                    transfer.infoHash, reviewedPlan)
                                    ?: LocalFileBatchOutcome.NOT_FOUND
                            }
                            if (result !in setOf(LocalFileBatchOutcome.VERIFIED,
                                    LocalFileBatchOutcome.UNCHANGED)) {
                                inventoryFrame = inventoryFrame.copy(stale = true)
                            }
                            message = localEpisodeFocusFeedback(result)
                            files = withContext(Dispatchers.IO) {
                                LocalEngineHolder.current()?.files(transfer.infoHash)
                            }
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            inventoryFrame = inventoryFrame.copy(stale = true)
                            files = null
                            noteProblem("Episode-priority change is uncertain. Refresh first.")
                        }
                    }
                },
                onFilePriority = { reviewed, newPriority ->
                    performLocalMutation {
                        try {
                            val outcome = withContext(Dispatchers.IO) {
                                LocalEngineHolder.current()?.setFilePriority(
                                    transfer.infoHash, reviewed, newPriority)
                                    ?: LocalFileBatchOutcome.NOT_FOUND
                            }
                            if (outcome !in setOf(LocalFileBatchOutcome.VERIFIED,
                                    LocalFileBatchOutcome.UNCHANGED)) {
                                inventoryFrame = inventoryFrame.copy(stale = true)
                            }
                            message = localFilePriorityFeedback(outcome)
                            files = withContext(Dispatchers.IO) {
                                LocalEngineHolder.current()?.files(transfer.infoHash)
                            }
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            inventoryFrame = inventoryFrame.copy(stale = true)
                            files = null
                            noteProblem("File priority outcome is uncertain. Refresh before another change.")
                        }
                    }
                },
                onBatchFileWanted = { reviewed, want ->
                    performLocalMutation {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                LocalEngineHolder.current()?.setFilesWantedBatch(
                                    transfer.infoHash, reviewed, want)
                                    ?: LocalFileBatchOutcome.NOT_FOUND
                            }
                            if (result !in setOf(LocalFileBatchOutcome.VERIFIED,
                                    LocalFileBatchOutcome.UNCHANGED)) {
                                inventoryFrame = inventoryFrame.copy(stale = true)
                            }
                            message = localFileBatchFeedback(result)
                            files = withContext(Dispatchers.IO) {
                                LocalEngineHolder.current()?.files(transfer.infoHash)
                            }
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            inventoryFrame = inventoryFrame.copy(stale = true)
                            files = null
                            noteProblem("Native batch file selection was not confirmed. " +
                                "Wait for fresh status before trying again.")
                        }
                    }
                },
                onPrepareRemote = prepareRemoteHandoff,
                move = {
                    TransferMoveControl(
                        direction = TransferMove.Direction.LOCAL_TO_SERVER,
                        infoHash = transfer.infoHash, name = transfer.name,
                        sourceReviewToken = transfer.nativeReviewToken,
                        serverAliasScope = moveServer?.aliasScope?.takeIf { prepareRemoteHandoff != null },
                        busy = busy || inventoryStale,
                        ports = TransferMovePorts(
                            observeTarget = moveServer?.let { server -> { server.observe(transfer.infoHash) } },
                            observeSource = { TransferMoveObservations.local(transfer.infoHash) },
                            removeSource = {
                                withContext(Dispatchers.IO) {
                                    val confirmed = LocalEngineHolder.current()?.removeKeepingDataIfMatching(
                                        transfer.infoHash, transfer.name, transfer.savePath,
                                        transfer.nativeReviewToken
                                    )
                                    if (confirmed != true)
                                        error("The reviewed original local session no longer matches")
                                }
                            },
                            targetUnavailableReason = "Connect to the remote account to check it."
                        ),
                        onStartMove = { prepareRemoteHandoff?.invoke() },
                        revision = moveRevision
                    )
                },
                onRemoveData = if (transfer.metadataKnown && transfer.infoHash.length == 40 &&
                    !inventoryStale) { {
                    performLocalMutation {
                        message = "Requesting removal of the reviewed transfer and its data..."
                        val receipt = try {
                            withContext(Dispatchers.IO) {
                                LocalEngineHolder.current()?.removeWithDataIfMatching(
                                    transfer.infoHash, transfer.name, transfer.savePath,
                                    transfer.nativeReviewToken)
                                    ?: LocalDeleteDataReceipt(LocalDeleteDataDispatch.NOT_FOUND)
                            }
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            LocalDeleteDataReceipt(LocalDeleteDataDispatch.UNCERTAIN)
                        }
                        val deletion = withContext(Dispatchers.IO) { awaitDeleteReadback(receipt) }
                        message = localDeleteDataFeedback(receipt, deletion)
                        inventoryFrame = inventoryFrame.copy(stale = true)
                    }
                } } else null,
                onRemove = {
                    performLocalMutation {
                        try {
                            // The native call can remove its handle but fail its final
                            // persistence commit. Observe this exact hash without sending
                            // another mutation, and keep the two facts distinct.
                            val (removed, stillPresent) = withContext(Dispatchers.IO) {
                                val engine = LocalEngineHolder.current()
                                // A native or persistence exception can happen after
                                // the session already removed this handle. Still perform
                                // the read-only exact-hash observation; never replay Remove.
                                val acknowledged = try {
                                    engine?.removeKeepingDataIfMatching(
                                        transfer.infoHash, transfer.name, transfer.savePath,
                                        transfer.nativeReviewToken
                                    )
                                } catch (cancel: kotlinx.coroutines.CancellationException) {
                                    throw cancel
                                } catch (_: Exception) { null }
                                val observed = try {
                                    engine?.let { it.status(transfer.infoHash) != null }
                                } catch (cancel: kotlinx.coroutines.CancellationException) {
                                    throw cancel
                                } catch (_: Exception) { null }
                                acknowledged to observed
                            }
                            message = localRemoveReadbackFeedback(removed, stillPresent)
                            if (removed != true || stillPresent != false)
                                inventoryFrame = inventoryFrame.copy(stale = true)
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            // Session removal may have succeeded before the exception.
                            inventoryFrame = inventoryFrame.copy(stale = true)
                            noteProblem("Session removal was not confirmed. Wait for fresh status before trying again. Downloaded files were not requested for deletion.")
                        }
                    }
                })
            TransferCollectionEditor(
                infoHash = transfer.infoHash,
                displayName = transfer.name,
                scope = TransferCollectionStore.LOCAL_ENGINE_SCOPE,
                store = collectionStore,
                availableCollections = knownLocalCollections,
                onMembershipChanged = {
                    collectionStore.forTransfer(TransferCollectionStore.LOCAL_ENGINE_SCOPE, transfer.infoHash)
                        .forEach { nameRegistry.create(TransferCollectionStore.LOCAL_ENGINE_SCOPE, it) }
                    registeredNames = nameRegistry.names(TransferCollectionStore.LOCAL_ENGINE_SCOPE)
                    collectionRevision++
                }
            )
            if (!inventoryStale && transfer.metadataKnown && transfer.progressPpm == 1_000_000)
                LocalLibraryCopyPanel(transfer)
        }
        } // Transfers presentation
        if (pane == LocalPane.OPTIONS) {
        // App-wide settings live behind a real Options destination.
        if (optionsCategory != null) Text(optionsCategory?.title.orEmpty(), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp)
                .bringIntoViewRequester(optionsRequester)
                .semantics { heading() })
        if (optionsCategory != null) OutlinedButton(onClick = ::returnToOptionsIndex,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("All options") }
        ClearableTransferSearchField(value = localOptionsSearch, onValueChange = { localOptionsSearch = it },
            // The Options root names itself once (MainActivity's section pane title);
            // a second "Options" pane here made TalkBack/tests see two panes.
            label = "Search options", modifier = Modifier.bringIntoViewRequester(optionsRequester))
        optionsLaunchError?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        }
        if (optionsCategory == null || localOptionsSearch.isNotBlank()) {
            OptionsCategory.entries.filter { localOptionsSearch.isBlank() || it.matches(localOptionsSearch) }
                .forEach { category ->
                    OptionsCategoryCard(category) {
                        when (category) {
                            OptionsCategory.CONNECTIONS -> onConnections?.invoke()
                            OptionsCategory.ARTWORK -> onMediaSettings?.invoke()
                            else -> openOptionsCategory(category)
                        }
                    }
                }
        }
        val privacyControlsHosted = io.github.gamesbyian.chunks.privacy.LocalPrivacyControls.current != null
        if (localOptionsSearch.isNotBlank()) {
            // Only entries whose screen or panel exists here; a result never changes a value.
            val results = LocalOptionsShortcut.search(localOptionsSearch).filter {
                when (it) {
                    LocalOptionsShortcut.SEEDING_RULES -> onSeedingRules != null
                    LocalOptionsShortcut.MEDIA -> onMediaSettings != null
                    LocalOptionsShortcut.CREATE_TORRENT -> onCreateTorrent != null
                    LocalOptionsShortcut.ZIP_REVIEW -> onZipReview != null
                    LocalOptionsShortcut.PRIVACY -> privacyControlsHosted
                    LocalOptionsShortcut.CONNECTIONS -> onConnections != null
                    LocalOptionsShortcut.SERVER_PLACES -> onRemotePlaces != null
                    LocalOptionsShortcut.SERVER_ORGANIZE -> onRemoteOrganize != null
                    LocalOptionsShortcut.SERVER_NAMES -> onRemoteNames != null
                    LocalOptionsShortcut.SERVER_BACKUP -> onRemoteSnapshot != null
                    else -> true
                }
            }
            if (results.isEmpty() && OptionsCategory.entries.none { it.matches(localOptionsSearch) })
                Text("No matching option. Clear search to see all settings and tools.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            results.forEach { shortcut ->
                OutlinedButton(onClick = {
                    val external = shortcut in setOf(LocalOptionsShortcut.CONNECTIONS,
                        LocalOptionsShortcut.SEEDING_RULES, LocalOptionsShortcut.MEDIA,
                        LocalOptionsShortcut.CREATE_TORRENT, LocalOptionsShortcut.ZIP_REVIEW,
                        LocalOptionsShortcut.DOWNLOAD_LOCATION, LocalOptionsShortcut.SERVER_PLACES,
                        LocalOptionsShortcut.SERVER_ORGANIZE, LocalOptionsShortcut.SERVER_NAMES,
                        LocalOptionsShortcut.SERVER_BACKUP)
                    if (!external || shortcut in setOf(LocalOptionsShortcut.SEEDING_RULES,
                            LocalOptionsShortcut.CREATE_TORRENT, LocalOptionsShortcut.ZIP_REVIEW))
                        openOptionsCategory(shortcut.category)
                    when (shortcut) {
                        LocalOptionsShortcut.SEEDING_RULES -> onSeedingRules?.invoke()
                        LocalOptionsShortcut.MEDIA -> onMediaSettings?.invoke()
                        LocalOptionsShortcut.CONNECTIONS -> onConnections?.invoke()
                        LocalOptionsShortcut.SERVER_PLACES -> onRemotePlaces?.invoke()
                        LocalOptionsShortcut.SERVER_ORGANIZE -> onRemoteOrganize?.invoke()
                        LocalOptionsShortcut.SERVER_NAMES -> onRemoteNames?.invoke()
                        LocalOptionsShortcut.SERVER_BACKUP -> onRemoteSnapshot?.invoke()
                        LocalOptionsShortcut.CREATE_TORRENT -> onCreateTorrent?.invoke()
                        LocalOptionsShortcut.ZIP_REVIEW -> onZipReview?.invoke()
                        LocalOptionsShortcut.DHT -> scope.launch { androidx.compose.runtime.withFrameNanos { }; dhtRequester.bringIntoView() }
                        LocalOptionsShortcut.CLIPBOARD_PREFILL -> scope.launch {
                            androidx.compose.runtime.withFrameNanos { }; clipboardRequester.bringIntoView()
                        }
                        LocalOptionsShortcut.DOWNLOAD_LOCATION -> {
                            // These controls still live with the existing Add workflow.
                            // Navigating cannot authorize or submit a transfer.
                            if (onOpenAdd != null) onOpenAdd.invoke()
                            else { addExpanded = true; addNavigationRevision++ }
                        }
                        LocalOptionsShortcut.SPEED_LIMITS -> scope.launch { androidx.compose.runtime.withFrameNanos { }; speedRequester.bringIntoView() }
                        LocalOptionsShortcut.PRIVACY -> scope.launch { androidx.compose.runtime.withFrameNanos { }; privacyRequester.bringIntoView() }
                        LocalOptionsShortcut.SNAPSHOT_RESTORE -> scope.launch { androidx.compose.runtime.withFrameNanos { }; restoreRequester.bringIntoView() }
                        LocalOptionsShortcut.EXPORTS -> { showLocalTools = true; scope.launch { androidx.compose.runtime.withFrameNanos { }; exportsRequester.bringIntoView() } }
                        LocalOptionsShortcut.COLLECTION_NAMES -> scope.launch { androidx.compose.runtime.withFrameNanos { }; namesRequester.bringIntoView() }
                        LocalOptionsShortcut.ORIGINAL_TORRENT_FILES -> scope.launch { androidx.compose.runtime.withFrameNanos { }; originalFilesRequester.bringIntoView() }
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(shortcut.title, fontWeight = FontWeight.Bold)
                        Text(shortcut.where, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        // Search is a filtered destination view: unrelated settings and their
        // duplicate headings must not remain below matching shortcut cards.
        // Clearing search restores the existing controls without saving new state.
        // Keep long-lived, process-memory-only review state (notably snapshot
        // comparisons) composed while filtering. Only the presentation and
        // accessibility tree are hidden; searching never destroys a draft.
        Column(
            modifier = Modifier.fillMaxWidth().then(if (localOptionsSearch.isBlank() && optionsCategory != null) Modifier
                else Modifier.height(0.dp).clipToBounds().focusProperties { canFocus = false }
                    .clearAndSetSemantics { })
        ) {
        OptionsSettingsGroup(optionsCategory == OptionsCategory.DOWNLOADS) {
        if (remoteEnvironmentLabel != null && onRemotePlaces != null &&
            onRemoteOrganize != null && onRemoteNames != null && onRemoteSnapshot != null) {
            // The local Options route is independently privacy-gated from
            // remote profiles. Do not speak the server host/username here.
            Text("Connected remote client tools", style = MaterialTheme.typography.titleMedium)
            Text("These tools operate only on the currently authenticated remote client. Opening a tool does not submit an Add or change any torrent.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onRemotePlaces,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Remote folders & destinations")
            }
            OutlinedButton(onClick = onRemoteOrganize,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Remote collections")
            }
        }
        }
        OptionsSettingsGroup(optionsCategory == OptionsCategory.APPEARANCE) {
        if (remoteEnvironmentLabel != null && onRemoteNames != null) {
            OutlinedButton(onClick = onRemoteNames,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Remote transfer names")
            }
        }
        }
        OptionsSettingsGroup(optionsCategory == OptionsCategory.ABOUT) {
        if (remoteEnvironmentLabel != null && onRemoteSnapshot != null) {
            OutlinedButton(onClick = onRemoteSnapshot,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Remote inventory backup")
            }
        }
        } // server tools remain scoped to the authenticated account
        OptionsSettingsGroup(optionsCategory == OptionsCategory.SEEDING) {
        if (onSeedingRules != null) OutlinedButton(onClick = onSeedingRules,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).bringIntoViewRequester(seedingReturnIntoView).focusRequester(seedingFocus)) {
            Text("Seeding rules…")
        }
        }
        OptionsSettingsGroup(optionsCategory == OptionsCategory.PRIVACY) {
        io.github.gamesbyian.chunks.privacy.LocalPrivacyControls.current?.let { controls ->
            Column(Modifier.fillMaxWidth().bringIntoViewRequester(privacyRequester), verticalArrangement = Arrangement.spacedBy(12.dp)) { controls() }
        }
        // Settings first, then occasional tools: the person looking for a
        // speed limit does not have to scroll past snapshot and export panels.
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .bringIntoViewRequester(dhtRequester)
            .toggleable(value = dht, enabled = !dhtSaving, role = Role.Switch,
                onValueChange = ::requestDhtSetting), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Find peers without trackers (DHT)")
                Text("Needed for most magnet links. Off by default: when on, this device's IP address is shared with the public DHT network.",
                    style = MaterialTheme.typography.bodySmall)
                SettingScopeNote(LOCAL_ENVIRONMENT_LABEL)
                if (dhtSaving) Text("Applying DHT setting…", style = MaterialTheme.typography.bodySmall)
                dhtError?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error) }
            }
            Switch(checked = dht, enabled = !dhtSaving, onCheckedChange = null)
        }
        // Clipboard suggestions can only fill an empty foreground Add draft.
        // The durable preference is configured here in Options, not on each Add.
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(clipboardRequester)) {
            clipboardPrefillOn?.let { current ->
                ClipboardPrefillToggle(checked = current,
                    enabled = !clipboardPrefillSaving, onChange = { requested ->
                        if (!clipboardPrefillSaving) {
                            clipboardPrefillSaving = true
                            clipboardPrefillError = null
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    runCatching { ClipboardMagnetPrefill.setEnabled(context, requested) }
                                        .getOrDefault(false)
                                }
                                if (saved) clipboardPrefillOn = requested
                                else clipboardPrefillError =
                                    "Clipboard preference could not be saved. Your setting is unchanged."
                                clipboardPrefillSaving = false
                            }
                        }
                    })
                Text("Only when Add opens in the foreground with an empty source. " +
                    "A suggestion remains a draft and cannot start a transfer.",
                    style = MaterialTheme.typography.bodySmall)
            }
            clipboardPrefillError?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        } // privacy and network
        OptionsSettingsGroup(optionsCategory == OptionsCategory.SEEDING) {
            Column(Modifier.fillMaxWidth().bringIntoViewRequester(speedRequester), verticalArrangement = Arrangement.spacedBy(12.dp)) { LocalSpeedLimits() }
        }
        OptionsSettingsGroup(optionsCategory == OptionsCategory.DOWNLOADS) {
            if (onOpenAdd != null) OutlinedButton(onClick = onOpenAdd,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Download location and Save to…") }
            if (onSmartRouting != null) OutlinedButton(onClick = onSmartRouting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("chunks.options.smart_routing")) { Text("Smart Routing…") }
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(restoreRequester), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LocalSnapshotRestorePreviewPanel(
            transfers = transfers,
            inventoryConfirmed = hasConfirmedLocalInventory,
            inventoryStale = inventoryStale,
            busyEngine = busy || sourceReading,
            mutationGeneration = LocalScreenReader.revision.snapshot(),
            onApplyAlias = { entry, generation, report ->
                if (inventoryStale || !hasConfirmedLocalInventory || busy || sourceReading ||
                    !LocalScreenReader.revision.isCurrent(generation)) {
                    report("The local inventory changed. Compare the snapshot again.")
                } else performLocalMutation {
                    val outcome = withContext(Dispatchers.IO) {
                        val observed = runCatching {
                            LocalEngineHolder.current()?.status(entry.infoHashV1)
                        }.getOrNull()
                        applyLocalSnapshotAlias(aliasStore, entry, observed)
                    }
                    aliasRevision++
                    report(outcome)
                }
            },
            readPriorityReview = { entry ->
                withContext(Dispatchers.IO) {
                    val hash = entry.infoHashV1
                    val engine = LocalEngineHolder.current()
                    if (engine == null || finishInOrderStore.get(hash) != null ||
                        hash in finishInOrderStore.afterMetadataRequests()) null
                    else LocalSnapshotPriorityRestore.plan(entry, engine.readStoppedFilePriorities(hash))
                }
            },
            onApplyPriorities = { review, generation, report ->
                if (inventoryStale || !hasConfirmedLocalInventory || busy || sourceReading ||
                    !LocalScreenReader.revision.isCurrent(generation)) {
                    report("The Local inventory changed. Compare the snapshot again.")
                } else performLocalMutation {
                    val outcome = withContext(Dispatchers.IO) {
                        FinishInOrderController.exclusive {
                            val hash = review.target.infoHash
                            val target = LocalEngineHolder.current()
                            if (target == null || finishInOrderStore.get(hash) != null ||
                                hash in finishInOrderStore.afterMetadataRequests()) {
                                LocalFileBatchOutcome.STALE_REVIEW
                            } else runCatching {
                                target.applyStoppedFilePriorities(review.target, review.desiredByIndex)
                            }.getOrDefault(LocalFileBatchOutcome.UNCERTAIN)
                        }
                    }
                    report(when (outcome) {
                        LocalFileBatchOutcome.VERIFIED ->
                            "File priorities verified and saved. The Local transfer is stopped."
                        LocalFileBatchOutcome.UNCHANGED ->
                            "The stopped Local transfer already has these file priorities."
                        LocalFileBatchOutcome.STALE_REVIEW, LocalFileBatchOutcome.NOT_FOUND ->
                            "The stopped transfer or its file choices changed. No priorities were submitted."
                        LocalFileBatchOutcome.UNCERTAIN ->
                            "Could not verify saved priorities. Check the Local transfer’s current files before another review."
                    })
                }
            },
            onPrepareAdd = { originalBytes ->
                check(!busy && sourceReadJob == null) { "Another source review is in progress" }
                initialTorrentUri?.let(onTorrentConsumed)
                // This callback changes only the local Add DRAFT, not the
                // engine, destination folder, existing transfers or data.
                try {
                    val bytes = originalBytes.copyOf()
                    pending = PendingLocalAdd(TorrentMetainfoReader.preview(bytes), bytes)
                    snapshotRestoreDraft = null
                    snapshotBatchDraft = null
                    snapshotBatchRecoveryDraft = null
                    addStorage = null
                    message = "Original snapshot .torrent is ready for manual local Add review. " +
                        "Check the destination and Start choice before confirming; no Add occurred."
                    if (onOpenAdd != null) onOpenAdd.invoke()
                    else scope.launch { scrollState.animateScrollTo(0) }
                } catch (_: Exception) {
                    noteProblem("Snapshot definition could not populate Add review." +
                        if (pending != null) " Your previous .torrent remains selected." else "")
                }
            },
            onReviewBatchRecovery = { verified, entries, frozen, generation ->
                check(!busy && sourceReadJob == null) {
                    "Another source review is in progress"
                }
                initialTorrentUri?.let(onTorrentConsumed)
                val request = ++snapshotPreparationRevision
                scope.launch {
                    val read = beginSourceRead()
                    try {
                        androidx.compose.runtime.snapshotFlow { busy }.first { !it }
                        val draft = withContext(Dispatchers.IO) {
                            require(entries.size in 2..512)
                            val originalHashes = entries.map { it.infoHashV1 }.sorted()
                            require(originalHashes.size == originalHashes.toSet().size)
                            require(originalHashes.all { hash ->
                                verified.manifest.items.any {
                                    it.infoHashV1 == hash &&
                                        it.recovery == SnapshotManifestV1.Recovery.METAINFO
                                }
                            })
                            LocalSnapshotBatchRecoveryDraft(
                                originalHashes,
                                SnapshotManifestV1.definitionArchiveFingerprint(verified),
                                frozen.toList(), generation)
                        }
                        if (sourceReadJob !== read) return@launch
                        if (request == snapshotPreparationRevision &&
                            LocalScreenReader.revision.snapshot() == generation &&
                            !LocalScreenReader.revision.blocksMutations()) {
                            snapshotRestoreDraft = null
                            snapshotBatchDraft = null
                            snapshotBatchRecoveryDraft = draft
                            pending = null
                            message = "Choose the original Local folder to check prior " +
                                "stopped restores. Existing journals are read; " +
                                "no transfer will be added."
                            val showReview = onShowLocalSnapshotReview ?: onOpenAdd
                            if (showReview != null) showReview.invoke()
                            else scrollState.animateScrollTo(0)
                        } else {
                            noteProblem("The Local inventory changed before the previous " +
                                "group check. Reopen the snapshot.")
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        if (sourceReadJob === read)
                            noteProblem("Cannot prepare the previous batch check. " +
                                "Refresh the verified snapshot comparison.")
                    } finally {
                        if (sourceReadJob === read) sourceReadJob = null
                    }
                }
            },
            onReviewBatchRestore = { verified, entries, frozen, generation ->
                check(!busy && sourceReadJob == null) { "Another source review is in progress" }
                initialTorrentUri?.let(onTorrentConsumed)
                val request = ++snapshotPreparationRevision
                scope.launch {
                    val read = beginSourceRead()
                    try {
                        androidx.compose.runtime.snapshotFlow { busy }.first { !it }
                        val draft = withContext(Dispatchers.IO) {
                            // Keep a bounded verified snapshot from exhausting
                            // the device before any local native Add. Every
                            // definition is prepared before the batch review.
                            require(entries.size in 2..512)
                            require(entries.sumOf {
                                verified.metainfoByHash[it.infoHashV1]?.size?.toLong() ?: 0L
                            } <= SnapshotLocalBatchRestoreV1.MAX_TOTAL_METAINFO_BYTES)
                            val originals = entries.map { entry ->
                                SnapshotVerifiedTorrentDocumentV1.prepare(
                                    verified, entry.infoHashV1)
                            }.sortedBy { it.infoHashV1 }
                            require(originals.map { it.infoHashV1 }.toSet().size == originals.size)
                            LocalSnapshotBatchStoppedRestoreDraft(
                                originals, SnapshotManifestV1.definitionArchiveFingerprint(verified),
                                frozen.toList(), generation)
                        }
                        if (sourceReadJob !== read) return@launch
                        if (request == snapshotPreparationRevision &&
                            LocalScreenReader.revision.snapshot() == generation &&
                            !LocalScreenReader.revision.blocksMutations()) {
                            snapshotRestoreDraft = null
                            snapshotBatchRecoveryDraft = null
                            snapshotBatchDraft = draft
                            pending = null
                            message = "Review the shared destination and confirm each stopped " +
                                "restore as one batch. No transfer has been added."
                            val showReview = onShowLocalSnapshotReview ?: onOpenAdd
                            if (showReview != null) showReview.invoke()
                            else scrollState.animateScrollTo(0)
                        } else {
                            noteProblem("The local inventory changed before batch review. Reopen the snapshot.")
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        if (sourceReadJob === read)
                            noteProblem("The batch source could not be prepared. Check its " +
                                "torrent definitions and the 24 MiB batch size limit.")
                    } finally {
                        if (sourceReadJob === read) sourceReadJob = null
                    }
                }
            },
            onReviewOneRestore = { verified, entry, frozen, generation ->
                check(!busy && sourceReadJob == null) { "Another source review is in progress" }
                initialTorrentUri?.let(onTorrentConsumed)
                // Assemble a canonical source fingerprint off the Compose lane.
                // No journal mutation or engine Add happens at this stage.
                val request = ++snapshotPreparationRevision
                scope.launch {
                    val read = beginSourceRead()
                    try {
                        androidx.compose.runtime.snapshotFlow { busy }.first { !it }
                        val draft = withContext(Dispatchers.IO) {
                            val original = SnapshotVerifiedTorrentDocumentV1.prepare(
                                verified, entry.infoHashV1)
                            LocalSnapshotStoppedRestoreDraft(
                                original,
                                SnapshotManifestV1.definitionArchiveFingerprint(verified),
                                frozen.toList(), generation)
                        }
                        if (sourceReadJob !== read) return@launch
                        if (request == snapshotPreparationRevision &&
                            LocalScreenReader.revision.snapshot() == generation &&
                            !LocalScreenReader.revision.blocksMutations()) {
                            snapshotBatchDraft = null
                            snapshotBatchRecoveryDraft = null
                            snapshotRestoreDraft = draft
                            pending = null
                            message = "Review the new local destination and explicitly confirm " +
                                "the stopped restore. No torrent has been added."
                            // Restore review lives on the Add pane while the
                            // snapshot comparison lives in Options. Bring the
                            // review into view instead of leaving it invisible.
                            val showReview = onShowLocalSnapshotReview ?: onOpenAdd
                            if (showReview != null) showReview.invoke()
                            else scrollState.animateScrollTo(0)
                        } else {
                            noteProblem("The comparison changed before restore review. Reopen the snapshot.")
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        if (sourceReadJob === read)
                            noteProblem("The verified original snapshot definition could not be prepared.")
                    } finally {
                        if (sourceReadJob === read) sourceReadJob = null
                    }
                }
            },
            onReviewPreviousRestore = { verified, entry, frozen, generation ->
                check(!busy && sourceReadJob == null) { "Another source review is in progress" }
                initialTorrentUri?.let(onTorrentConsumed)
                // Assemble a canonical source fingerprint off the Compose lane.
                // No journal mutation or engine Add happens at this stage.
                val request = ++snapshotPreparationRevision
                scope.launch {
                    val read = beginSourceRead()
                    try {
                        androidx.compose.runtime.snapshotFlow { busy }.first { !it }
                        val draft = withContext(Dispatchers.IO) {
                            val original = SnapshotVerifiedTorrentDocumentV1.prepare(
                                verified, entry.infoHashV1)
                            LocalSnapshotStoppedRestoreDraft(
                                original,
                                SnapshotManifestV1.definitionArchiveFingerprint(verified),
                                frozen.toList(), generation, recoveryOnly = true)
                        }
                        if (sourceReadJob !== read) return@launch
                        if (request == snapshotPreparationRevision &&
                            LocalScreenReader.revision.snapshot() == generation &&
                            !LocalScreenReader.revision.blocksMutations()) {
                            snapshotBatchDraft = null
                            snapshotBatchRecoveryDraft = null
                            snapshotRestoreDraft = draft
                            pending = null
                            message = "Choose the original local destination to check the " +
                                "previous restore journal. This review never repeats Add."
                            // Restore review lives on the Add pane while the
                            // snapshot comparison lives in Options. Bring the
                            // review into view instead of leaving it invisible.
                            val showReview = onShowLocalSnapshotReview ?: onOpenAdd
                            if (showReview != null) showReview.invoke()
                            else scrollState.animateScrollTo(0)
                        } else {
                            noteProblem("The comparison changed before restore review. Reopen the snapshot.")
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        if (sourceReadJob === read)
                            noteProblem("The verified original snapshot definition could not be prepared.")
                    } finally {
                        if (sourceReadJob === read) sourceReadJob = null
                    }
                }
            }
        )
        } // restore anchor
        }
        OptionsSettingsGroup(optionsCategory == OptionsCategory.ABOUT) {
        if (onCreateTorrent != null) OutlinedButton(onClick = onCreateTorrent,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).bringIntoViewRequester(createReturnIntoView).focusRequester(createFocus)) {
            Text("Create a torrent…")
        }
        }
        OptionsSettingsGroup(optionsCategory == OptionsCategory.DOWNLOADS) {
        if (onZipReview != null) OutlinedButton(onClick = onZipReview,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).bringIntoViewRequester(zipReturnIntoView).focusRequester(zipFocus)) {
            Text("Review ZIP archives…")
        }
        }
        OptionsSettingsGroup(optionsCategory == OptionsCategory.ABOUT) {
        OutlinedButton(
            onClick = { showLocalTools = !showLocalTools },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = if (showLocalTools) "Expanded" else "Collapsed" }
        ) { Text(if (showLocalTools) "Hide local tools" else "More local tools") }
        if (showLocalTools) {
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(exportsRequester), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val snapshotEngine = LocalEngineHolder.current()
        LocalInventorySnapshotPanel(
            transfers = transfers,
            stale = inventoryStale,
            busyEngine = busy,
            settingsCapture = snapshotEngine?.let { engine ->
                SnapshotSettingsCapture(
                    readAlias = { hash -> aliasStore.get(TransferCollectionStore.LOCAL_ENGINE_SCOPE, hash) },
                    readFiles = { hash ->
                        engine.readFilePriorities(hash, SnapshotFilePriorities.MAX_FILES)?.map { row ->
                            SnapshotFilePriorities.Observation(
                                SnapshotFilePriorities.File(row.index, row.path, row.sizeBytes), row.priority)
                        }
                    }
                )
            },
            sourceGeneration = LocalScreenReader.revision.snapshot(),
            sourceStillCurrent = { generation ->
                LocalEngineHolder.current() === snapshotEngine &&
                    LocalScreenReader.revision.snapshot() == generation &&
                    !LocalScreenReader.revision.blocksMutations()
            }
        )
        LocalTransferHealthExportPanel(
            transfers = transfers,
            stale = inventoryStale || busy
        )
        BatchMagnetExportPanel(
            rows = displayedTransfers.map {
                io.github.gamesbyian.chunks.model.BatchMagnetHandoff.Row(
                    it.infoHash, it.name)
            },
            stale = inventoryStale || busy,
            sourceLabel = "local"
        )
        } // exports anchor
        }
        }
        OptionsSettingsGroup(optionsCategory == OptionsCategory.DOWNLOADS) {
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(namesRequester), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CollectionNameManager(
            scope = TransferCollectionStore.LOCAL_ENGINE_SCOPE,
            registry = nameRegistry,
            registeredNames = registeredNames,
            onNamesChanged = { registeredNames = it }
        )
        } // names anchor
        var defaultHandling by remember { mutableStateOf<OriginalTorrentHandling?>(null) }
        LaunchedEffect(optionsCategory) {
            if (optionsCategory == OptionsCategory.DOWNLOADS) defaultHandling =
                withContext(Dispatchers.IO) { OriginalTorrentHandlingStore(context).default }
        }
        defaultHandling?.let { shown ->
            Text("Add & files", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.bringIntoViewRequester(originalFilesRequester)
                    .semantics { heading() }
                    .testTag(ChunksSemantics.OPTIONS_ADD_AND_FILES))
            OriginalTorrentHandlingChoice("Original .torrent file after a verified Add",
                selected = shown, enabled = true,
                onSelect = { choice ->
                    scope.launch {
                        if (withContext(Dispatchers.IO) { OriginalTorrentHandlingStore(context).setDefault(choice) })
                            defaultHandling = choice
                        else message = "The default for original .torrent files could not be saved."
                    }
                })
        }
        Text("Applies to every .torrent file you open or share from another app. Only the original " +
            ".torrent document is affected, never downloaded files. Delete always asks again first.",
            style = MaterialTheme.typography.bodySmall)
        LocalZipInterruptedExtractions()
        }
        OptionsSettingsGroup(optionsCategory == OptionsCategory.NOTIFICATIONS) {
            val notificationsEnabled = remember(resumeTick, optionsCategory) {
                androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
            Text(if (notificationsEnabled) "Android allows notifications from Chunks."
                else "Notifications are disabled in Android settings.")
            Text("Android controls notification permissions and the Downloads on this device channel.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                try {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                } catch (_: android.content.ActivityNotFoundException) {
                    optionsLaunchError = "Android notification settings could not be opened."
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open Android notification settings") }
        }
        OptionsSettingsGroup(optionsCategory == OptionsCategory.APPEARANCE) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = showEnvironmentColours,
                    enabled = !savingEnvironmentColours, role = Role.Switch,
                    onValueChange = { requested ->
                        savingEnvironmentColours = true
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                EnvironmentColourStore(context).setShowEnvironmentColours(requested)
                            }
                            if (saved) {
                                showEnvironmentColours = requested
                                colourPreferenceNotice = null
                            } else colourPreferenceNotice = "Could not save Show client colours."
                            savingEnvironmentColours = false
                        }
                    }), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Show client colours", modifier = Modifier.weight(1f))
                Switch(checked = showEnvironmentColours, onCheckedChange = null)
            }
            colourPreferenceNotice?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            EnvironmentColourPreference(
                io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL, "Local")
            Text("Each remote connection's colour is configured in Connections. Names and status remain visible when colours are off.",
                style = MaterialTheme.typography.bodySmall)
            if (onMediaSettings != null) OutlinedButton(onClick = onMediaSettings,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Artwork size and display…") }
            Text("Chunks follows Android's text size.",
                style = MaterialTheme.typography.bodySmall)
        }
        OptionsSettingsGroup(optionsCategory == OptionsCategory.ABOUT) {
            val appVersion = remember(context) {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            }
            Text("Chunks Torrent Client & Remote", style = MaterialTheme.typography.titleMedium)
            Text("Version $appVersion", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/gamesbyian/Chunks"))) }
                catch (_: android.content.ActivityNotFoundException) { optionsLaunchError = "No browser is available to open Chunks help." }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Help and project information") }
        }
        } // full Options controls retained but visually hidden while searching
        } // Options presentation
    }
    ReturnToTopControl(
        visible = showBackToTop,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 22.dp),
        onClick = {
            scope.launch {
                if (ReturnToTopPolicy.animationsEnabled(context)) scrollState.animateScrollTo(0)
                else scrollState.scrollTo(0)
            }
        }
    )
    } // scroll viewport
    if (showNavigationFooter) AuthenticatedTransfersFooter(
        // Standalone local tests retain their footer; ChunksApp owns the global one.
        optionsOpen = null,
        environmentLabel = "this device",
        onTransfers = { scope.launch { transfersRequester.bringIntoView() } },
        onAdd = {
            addExpanded = true
            // Repeated taps also bring the real form into view above the footer.
            addNavigationRevision++
        },
        onOptions = { scope.launch { optionsRequester.bringIntoView() } },
        onConnections = onConnections
    )
    } // measured footer + viewport
}
