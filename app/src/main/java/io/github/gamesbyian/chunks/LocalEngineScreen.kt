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
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalDiskIoMode
import io.github.gamesbyian.chunks.engine.LocalRateLimits
import io.github.gamesbyian.chunks.engine.LocalRestoreDiscovery
import io.github.gamesbyian.chunks.engine.LocalTrackerRepairOutcome
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.Units
import io.github.gamesbyian.chunks.model.LocalInventoryFrame
import io.github.gamesbyian.chunks.snapshots.LocalInventorySnapshotPanel
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
import io.github.gamesbyian.chunks.snapshots.LocalSnapshotStoppedRestoreDraft
import io.github.gamesbyian.chunks.snapshots.LocalSnapshotStoppedRestoreReview
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

/** The connected rTorrent account as seen by phone-side Move Transfer. */
internal class LocalMoveServer(
    val aliasScope: String,
    val observe: suspend (String) -> TransferMove.Observation
)

internal object LocalEngineHolder {
    private var engine: LocalTorrentEngine? = null

    /** The running engine, if the user has added anything; never starts networking. */
    @Synchronized
    fun current(): LocalTorrentEngine? = engine

    /**
     * Starts the engine (and its listening socket) on first explicit use, or to
     * restore transfers saved earlier. Restored transfers always come back stopped.
     */
    @Synchronized
    fun get(context: Context): LocalTorrentEngine {
        engine?.let { return it }
        // The upstream portable backend avoids the reproduced Android 15 FUSE
        // NodeTracker abort during memory-mapped writes. Paths and file policy stay unchanged.
        val candidate = LocalTorrentEngine(
            LocalEngineConfig(stateDirectory = stateDirectory(context), diskIoMode = LocalDiskIoMode.POSIX)
        )
        val verified = LocalEngineStartupSettings.open(
            candidate = candidate,
            savedDht = dhtPreferred(context),
            savedLimits = rateLimitsPreferred(context),
            start = { it.start() },
            setDht = { running, desired -> running.setDhtEnabled(desired) },
            setLimits = { running, desired -> running.setRateLimits(desired) },
            close = { it.close() }
        )
        // Never expose a partially started engine as the live singleton.
        engine = verified
        return verified
    }

    private fun prefs(context: Context) = context.getSharedPreferences("local-engine", Context.MODE_PRIVATE)
    fun dhtPreferred(context: Context) = prefs(context).getBoolean("dht", false)

    /**
     * No optimistic DHT setting: do not claim/persist a change that the running
     * engine could not read back. A commit failure is also unconfirmed.
     * This never starts an engine merely to toggle a future preference.
     */
    @Synchronized
    fun setDhtPreferred(context: Context, enabled: Boolean): Boolean {
        val running = engine
        return LocalDhtPreferenceTransaction.commitIfApplied(
            requested = enabled,
            applyToRunningEngine = running?.let { { it.setDhtEnabled(enabled) } },
            savePreference = { value -> prefs(context).edit().putBoolean("dht", value).commit() }
        )
    }

    fun rateLimitsPreferred(context: Context) = prefs(context).let {
        LocalRateLimits(it.getInt("download-limit", 0).coerceAtLeast(0), it.getInt("upload-limit", 0).coerceAtLeast(0))
    }

    /**
     * Save a future-start preference only if no engine exists; otherwise demand
     * an exact native readback before the durable commit. Failed/uncertain
     * application must not overwrite the previous saved speed limits.
     */
    @Synchronized
    fun setRateLimitsPreferred(context: Context, limits: LocalRateLimits): Boolean {
        val running = engine
        return LocalRateLimitPreferenceTransaction.commitIfApplied(
            requested = limits,
            applyToRunningEngine = running?.let { { it.setRateLimits(limits) } },
            savePreference = { value ->
                prefs(context).edit()
                    .putInt("download-limit", value.downloadBytesPerSecond)
                    .putInt("upload-limit", value.uploadBytesPerSecond)
                    .commit()
            }
        )
    }

    /** Start only if an earlier session left transfers to restore. */
    @Synchronized
    fun restoreIfSaved(context: Context): LocalTorrentEngine? = engine
        ?: if (LocalRestoreDiscovery.shouldAttemptRestore(stateDirectory(context))) get(context) else null

    private fun stateDirectory(context: Context) = File(context.filesDir, "local-engine")

    /** The only destination offered in this slice: a real POSIX folder Chunks owns. */
    fun appDownloads(context: Context): File? =
        context.getExternalFilesDir("Downloads")?.apply { mkdirs() }?.takeIf { it.isDirectory }
}

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
    val revision get() = PhoneMutationFence.revision
    val pump = LocalScreenObservationPump<LocalScreenReadout>(
        java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "chunks-local-screen-status").apply { isDaemon = true }
        }
    )
}

/**
 * Process-memory-only reviewed phone .torrent. Never Bundle/save metainfo:
 * tracker tokens may be embedded. The digest covers the WHOLE source, not
 * only the v1 infohash, so a changed same-host tracker URL cannot silently
 * acquire the user's authorization for the previously reviewed document.
 */
internal class PendingLocalAdd(val preview: TorrentMetainfoPreview, val bytes: ByteArray) {
    private val reviewedSourceSha256 =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

    fun stillMatchesReviewedSource(candidate: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(reviewedSourceSha256,
            java.security.MessageDigest.getInstance("SHA-256").digest(candidate))
}

/** Presentation destinations share the same phone engine and Add coordinator. */
internal enum class PhonePane { TRANSFERS, ADD, OPTIONS }

@Composable
internal fun LocalEngineScreen(
    onBack: (() -> Unit)? = null,
    pane: PhonePane = PhonePane.TRANSFERS,
    showNavigationFooter: Boolean = true,
    /** Opens Smart Seeding Rules; null hides the entry. */
    onSeedingRules: (() -> Unit)? = null,
    /** Options → Artwork & media info (app-wide presentation preferences). */
    onMediaSettings: (() -> Unit)? = null,
    /** App-wide phone tools, reachable from Phone options without leaving via the server entry. */
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
    /** Reports each Add the phone engine acknowledged and read back, by its exact hash. */
    onAcknowledgedAdd: (String) -> Unit = {},
    /** The Phone options tool just closed ("SEEDING", "CREATE", "ARCHIVES"); its button regains focus. */
    returnFocusTool: String? = null,
    onReturnFocusConsumed: () -> Unit = {},
    onPrepareRemote: ((String) -> Unit)? = null,
    /** Receives a verified exported .torrent for the server's Add review. */
    onPrepareRemoteTorrent: ((ByteArray) -> Unit)? = null,
    /**
     * Navigation-only state owned by the caller so that leaving for All
     * environments, Seeding rules or a server and returning keeps the list
     * position and search. Never carries selections or confirmations.
     */
    listScroll: androidx.compose.foundation.ScrollState? = null,
    searchState: androidx.compose.runtime.MutableState<String>? = null,
    optionsSearchState: androidx.compose.runtime.MutableState<String>? = null,
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
    /** Reviewed .torrent bytes outlive phone screen navigation, never Activity/process recreation. */
    pendingAddState: androidx.compose.runtime.MutableState<PendingLocalAdd?>? = null,
    /** App-owned process-only attempt/reconciliation authority, injected by Agent 5. */
    phoneAddAttemptSession: PhoneAddAttemptSession? = null,
    /** Read-only HTTPS metadata intake, injectable for source-replacement regression tests. */
    fetchTorrentDocument: (TorrentLinkCheck.Fetchable) -> TorrentLinkResult = { TorrentLinkFetcher().fetch(it) }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    val addInventoryReady = PhoneUniversalAddAdapter.inventoryReadyForReview(
        hasConfirmedLocalInventory, inventoryStale)
    var transferPage by remember { mutableStateOf(0) }
    val transferSearchHolder = searchState ?: remember { mutableStateOf("") }
    var transferSearch by transferSearchHolder
    // View-only disclosure; no native engine work is started by these flags.
    var showPhoneTools by rememberSaveable { mutableStateOf(false) }
    var showListOptions by rememberSaveable { mutableStateOf(false) }
    val localFilterStore = remember(context) { LocalTransferFilterStore(context) }
    var transferFilter by remember(localFilterStore) { mutableStateOf(localFilterStore.load()) }
    fun chooseLocalFilter(choice: LocalQueueFilter) {
        transferFilter = choice
        localFilterStore.save(choice)
    }
    // Exact hash of the latest acknowledged phone Add, for "hidden by view" only.
    var lastAddedHash by rememberSaveable { mutableStateOf<String?>(null) }
    // The search/filter in effect when that Add happened. Any later deliberate
    // view change retires the notice, so an old Add is never called "new".
    var lastAddedView by rememberSaveable { mutableStateOf<String?>(null) }
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
        sortStore.get(TransferListSortStore.PHONE, LocalQueueOrder.entries.toTypedArray())
    }
    var transferOrder by remember { mutableStateOf(savedSort?.first ?: LocalQueueOrder.NAME) }
    var transferDirection by remember {
        mutableStateOf(savedSort?.second ?: transferOrder.naturalDirection)
    }
    fun chooseTransferSort(order: LocalQueueOrder, direction: UnifiedSortDirection) {
        transferOrder = order
        transferDirection = direction
        sortStore.set(TransferListSortStore.PHONE, order, direction)
    }
    // Confirmed media titles are searchable too, keyed by the exact phone job.
    val mediaTitles by io.github.gamesbyian.chunks.media.rememberMediaSearchTitles()
    val displayedTransfers = remember(transfers, transferSearch, transferFilter,
        transferOrder, transferDirection, aliasRevision, mediaTitles) {
        LocalQueueCatalog.visible(transfers, transferSearch, transferFilter, transferOrder,
            transferDirection,
            displayName = { transfer -> aliasStore.get(TransferCollectionStore.LOCAL_ENGINE_SCOPE,
                transfer.infoHash) ?: transfer.name },
            mediaTitle = { transfer -> phoneTransferKey(transfer.infoHash)
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
    val phoneReviewHolder = pendingAddState ?: remember { mutableStateOf<PendingLocalAdd?>(null) }
    var pending by phoneReviewHolder
    var snapshotRestoreDraft by remember { mutableStateOf<LocalSnapshotStoppedRestoreDraft?>(null) }
    var snapshotPreparationRevision by remember { mutableIntStateOf(0) }
    var repairSelectionHash by remember { mutableStateOf<String?>(null) }
    var repairSelectionBusy by remember { mutableStateOf(false) }
    var repairReview by remember {
        mutableStateOf<Pair<String, SharedTorrentDocument.Reviewed>?>(null)
    }
    var addStorage by remember {
        mutableStateOf<Pair<String, LocalAddStoragePreflight>?>(null)
    }
    var message by remember { mutableStateOf<String?>(null) }
    // The latest outcome that needs attention (refused, uncertain, failed).
    // Tone is chosen where the outcome is known, never guessed from text.
    var problemOutcome by remember { mutableStateOf<String?>(null) }
    fun noteProblem(text: String) {
        message = text
        problemOutcome = text
    }
    var busy by remember { mutableStateOf(false) }
    // One cancellable source-read owner, shared by picker, Android handoff and
    // HTTPS intake. Cancellation is read-only; it never consumes a queued URI.
    var sourceReadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val sourceReading = sourceReadJob != null
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
            pending = PendingLocalAdd(reviewed.preview, reviewed.bytes)
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
    fun performLocalMutation(operation: suspend () -> Unit) {
        if (busy || inventoryFrame.stale || !LocalScreenReader.revision.beginMutation()) {
            message = "A prior native action needs a fresh confirmed transfer list before another change."
            return
        }
        // A new native mutation invalidates cross-command growth attribution.
        progressHistory.clear()
        observedDeltas = emptyMap()
        inventoryFrame = inventoryFrame.copy(stale = true)
        busy = true
        scope.launch {
            try { operation() } finally {
                LocalScreenReader.revision.settledMutation()
                inventoryFrame = inventoryFrame.copy(stale = true)
                busy = false
            }
        }
    }
    val addKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val addFocus = androidx.compose.ui.platform.LocalFocusManager.current
    val magnetHolder = magnetDraftState ?: remember { mutableStateOf("") }
    var magnet by magnetHolder
    fun selectTextSource(value: String) {
        cancelSourceRead()
        initialTorrentUri?.let(onTorrentConsumed)
        magnet = value
    }
    // A kept draft reopens its form on return, rather than hiding it.
    var addExpanded by rememberSaveable { mutableStateOf(magnet.isNotEmpty()) }
    var finishInOrderDefault by remember { mutableStateOf(finishInOrderStore.defaultForNewAdds()) }
    var magnetFinishInOrder by remember(magnet) { mutableStateOf(finishInOrderDefault) }
    LaunchedEffect(pending?.preview?.infoHashV1) {
        if (pending != null) addExpanded = true
    }
    LaunchedEffect(initialMagnet, busy, phoneAddAttemptSession?.attemptedTicket) {
        if (busy || phoneAddAttemptSession?.attemptedTicket != null) return@LaunchedEffect
        if (initialMagnet != null && initialMagnet.startsWith("magnet:?")) {
            selectTextSource(initialMagnet)
            addExpanded = true
            onPrefillConsumed()
        }
    }
    // Foreground clipboard prefill: read once each time the Add form opens.
    var clipboardPrefillOn by remember { mutableStateOf<Boolean?>(null) }
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
        phoneAddAttemptSession?.attemptedTicket) {
        // The dedicated Add destination renders the form even before the
        // legacy inline disclosure is expanded. Clipboard intake is still
        // focus-gated, one-shot and never replaces a draft or pending review.
        if ((pane != PhonePane.ADD && !addExpanded) ||
            clipboardPrefillOn != true || !windowFocused ||
            busy || phoneAddAttemptSession?.attemptedTicket != null) return@LaunchedEffect
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
                    "Chunks could not check phone download locations.")
            }
        }
    }
    val addPlaceResolution = placeResolution?.let { LocalAddPlacePolicy.resolve(it, currentAddPlaceId) }
    val place = addPlaceResolution?.selected
    val destination = place?.root
    // A completed native observation after the frozen ticket, rather than a
    // matching row in an old filter, resolves its exact phone Add identity.
    LaunchedEffect(lastAcceptedInventoryAt, inventoryFrame, busy) {
        // An inventory sample may finish while a native Add is still inside
        // its suspend boundary. Do not let an early matching hash classify
        // the ticket before noteNativeResult has captured this Add's actual
        // generation receipt. Re-run after the mutation settles.
        if (busy) return@LaunchedEffect
        val session = phoneAddAttemptSession ?: return@LaunchedEffect
        val ticket = session.attemptedTicket ?: return@LaunchedEffect
        if (!hasConfirmedLocalInventory || inventoryStale ||
            lastAcceptedInventoryAt < ticket.submittedAtEpochMillis) return@LaunchedEffect
        val before = session.coordinator.phase
        val result = session.reconcileInventory(transfers, lastAcceptedInventoryAt)
        if (session.coordinator.phase != before) when (result) {
            io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Readback.VERIFIED -> {
                message = "The new phone transfer was verified in a fresh native list. Review the next Add when ready."
                // A positive native registration may already have been
                // announced to Combined. Independent readback upgrades only
                // the reviewed Place/Start outcome, never sends a second notice.
                if (session.announceVerifiedRegistrationOnce(ticket))
                    rememberAdded(ticket.intent.source.expectedInfoHash, message)
            }
            io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Readback.EXACT_SESSION_CONFIRMED ->
                noteProblem("This torrent exists on the phone at the reviewed location, but its Add origin is uncertain. No retry occurred.")
            io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Readback.OBSERVED_UNVERIFIED -> {
                if (session.coordinator.phase ==
                    io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED)
                    noteProblem("The phone job exists at the reviewed location, but the requested payload Start is unverified. No retry occurred.")
                else noteProblem("A matching phone torrent was observed, but its Place, Start or new-Add identity is unverified. No retry occurred.")
            }
            else -> Unit
        }
    }
    val proposedAddDestination = remember(destination, addFolderDraft) {
        LocalOwnedAddFolder.proposed(destination, addFolderDraft)
    }
    // This is invoked *only* inside performLocalMutation after the existing
    // phone mutation gate has been acquired, before any IO/native call.
    // Null sessions are used only by standalone UI test harnesses until the
    // app owner injects its one process-only phone coordinator.
    fun authorizePhoneTicket(
        source: io.github.gamesbyian.chunks.model.UniversalAddIntent,
        selectedPlace: LocalDownloadPlace,
        inventoryReadyAtClick: Boolean
    ): io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Ticket? {
        val session = phoneAddAttemptSession ?: return null
        val decision = PhoneUniversalAddAdapter.review(session.coordinator, source,
            inventoryConfirmed = inventoryReadyAtClick, inventoryStale = !inventoryReadyAtClick)
        if (!decision.accepted || !session.coordinator.authorizeByUser()) {
            noteProblem("This phone Add cannot be authorized: " + decision.reason + ". Nothing was sent.")
            return null
        }
        val ticket = session.coordinator.beginSubmission(System.currentTimeMillis())
        if (ticket == null) {
            noteProblem("An earlier phone Add is still being checked. No second Add was sent.")
            return null
        }
        session.bindPlace(ticket, selectedPlace)
        return ticket
    }
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
                    noteProblem("This .torrent has a different infohash. The existing phone transfer was untouched.")
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

    LaunchedEffect(initialTorrentUri, busy, phoneAddAttemptSession?.attemptedTicket) {
        val externalUri = initialTorrentUri ?: return@LaunchedEffect
        // Keep an incoming share queued while an earlier write is unresolved.
        if (phoneAddAttemptSession?.attemptedTicket != null) return@LaunchedEffect
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
    // Phone options search targets: existing panels on this screen.
    val speedRequester = remember { BringIntoViewRequester() }
    val dhtRequester = remember { BringIntoViewRequester() }
    val privacyRequester = remember { BringIntoViewRequester() }
    val restoreRequester = remember { BringIntoViewRequester() }
    val exportsRequester = remember { BringIntoViewRequester() }
    val namesRequester = remember { BringIntoViewRequester() }
    val optionsSearchHolder = optionsSearchState ?: rememberSaveable { mutableStateOf("") }
    var phoneOptionsSearch by optionsSearchHolder
    var showBatchTools by rememberSaveable { mutableStateOf(false) }
    // Back from a phone tool returns keyboard focus to the button that opened it.
    val seedingFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val createFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val zipFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(returnFocusTool) {
        val target = when (returnFocusTool) {
            "SEEDING" -> seedingFocus
            "CREATE" -> createFocus
            "ARCHIVES" -> zipFocus
            else -> null
        } ?: return@LaunchedEffect
        // Wait one frame so the restored list is laid out before focusing.
        androidx.compose.runtime.withFrameNanos { }
        try { target.requestFocus() } catch (_: IllegalStateException) { }
        onReturnFocusConsumed()
    }
    val addRequester = remember { BringIntoViewRequester() }
    var addNavigationRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(pane, addExpanded, addNavigationRevision) {
        if (pane == PhonePane.ADD) addRequester.bringIntoView()
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
        val firstRunVisible = onConnections != null && showPhoneFirstRunWelcome(
            hasConfirmedLocalInventory, inventoryStale, transfers.isEmpty(),
            !showFirstRunWelcome, transferSearch, addExpanded)
        if (pane == PhonePane.TRANSFERS) {
            FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EnvironmentBadge("This device", server = false,
                    environment = io.github.gamesbyian.chunks.model.EnvironmentSnapshots.PHONE,
                    modifier = Modifier.align(Alignment.CenterVertically))
                if (onCombined != null) OutlinedButton(onClick = onCombined,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("All environments") }
            }
            // This screen owns Stop/Remove outcomes. A successful native
            // deletion removes its row, but must not remove the user's
            // independently verified confirmation with it.
            message?.let { PhoneOutcomeNotice(it, needsAttention = problemOutcome == it) }
        }
        if (pane == PhonePane.ADD) {
        Text("Add on this device", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        phoneAddAttemptSession?.attemptedTicket?.let {
            val phase = phoneAddAttemptSession.coordinator.phase
            Text("Previous phone Add: " + when (phase) {
                io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.VERIFIED ->
                    "new transfer verified from this Add."
                io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.SESSION_CONFIRMED ->
                    "transfer exists, but Add origin is uncertain."
                io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED ->
                    "transfer exists at the reviewed Place; requested Start remains unverified."
                else -> "waiting for exact phone transfer verification; no automatic retry."
            }, style = MaterialTheme.typography.bodyMedium)
            if (phase == io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.VERIFIED ||
                phase == io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.SESSION_CONFIRMED ||
                phase == io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED) {
                if (phase == io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED)
                    Text("Acknowledging does not confirm payload Start or repeat Add. Inspect the existing transfer before starting it separately.",
                        style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    val done = if (phase ==
                        io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED)
                        phoneAddAttemptSession.acknowledgeObservedJob()
                    else phoneAddAttemptSession.finishReviewedAttempt()
                    if (done) {
                        pending = null
                        magnet = ""
                        addFolderDraft = ""
                        currentAddPlaceId = null
                        addExpanded = true
                        message = if (phase ==
                            io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED)
                            "Existing phone job acknowledged with Start unverified. No retry was sent; review a new source explicitly."
                        else "Previous phone Add review closed. Choose your next source explicitly."
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(when (phase) {
                        io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.VERIFIED ->
                            "Review another phone Add"
                        io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED ->
                            "Acknowledge existing job (Start unverified)"
                        else -> "Acknowledge existing job, then review another"
                    })
                }
            }
        }
        if (addExpanded || pane == PhonePane.ADD) {
        Text("Add a download on this device", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.bringIntoViewRequester(addRequester)
                .semantics { heading() })
        ActionTile("Choose .torrent file", ChunksColors.Green, onClick = { picker.launch(arrayOf("*/*")) },
            enabled = !busy && !sourceReading && phoneAddAttemptSession?.attemptedTicket == null,
            modifier = Modifier.fillMaxWidth(), icon = R.drawable.chunks_ic_add_torrent, stacked = false)
        val magnetCheck = remember(magnet) { MagnetDraftCheck.inspect(magnet) }
        // A web link is a different source: fetched only on request, then reviewed as a .torrent.
        val linkCheck = remember(magnet) { TorrentLinkCheck.inspect(magnet) }
        val isLink = linkCheck !is TorrentLinkCheck.NotALink
        OutlinedTextField(value = magnet, onValueChange = { selectTextSource(it.trim()) },
            enabled = !busy && phoneAddAttemptSession?.attemptedTicket == null,
            label = { Text("Or paste a magnet or torrent link") }, singleLine = true,
            isError = if (isLink) linkCheck is TorrentLinkCheck.Problem else magnetCheck is MagnetDraftCheck.Problem,
            // Autocorrect must never rewrite a magnet's hash or trackers.
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                autoCorrectEnabled = false,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            modifier = Modifier.fillMaxWidth())
        if (magnet.isEmpty()) PasteMagnetButton(
            enabled = !busy && phoneAddAttemptSession?.attemptedTicket == null,
            onPaste = { selectTextSource(it) },
            onNothingToPaste = { message = "The clipboard has no text to paste. Copy a magnet or torrent link first." })
        if (clipboardFilled != null && magnet == clipboardFilled) ClipboardPrefillNote(
            onClear = { magnet = ""; clipboardFilled = null },
            enabled = !busy && !sourceReading && phoneAddAttemptSession?.attemptedTicket == null)
        if (isLink) TorrentLinkPanel(linkCheck, fetching = linkFetching,
            // Read-only source validation does not require a phone Place.
            // Actual Add still requires the existing fresh inventory/Place gates.
            // An existing verified review remains selected until the replacement fetch
            // succeeds and validates; refusal must never require discarding it first.
            enabled = !busy && !sourceReading && phoneAddAttemptSession?.attemptedTicket == null, onFetch = { link ->
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
            })
        else MagnetDraftSummary(magnetCheck)
        if (magnetCheck is MagnetDraftCheck.Valid) Text(
            "Seeding: your phone Seeding rules choose a policy when this magnet's metadata " +
                "arrives (its trackers and private flag are known then); otherwise the phone default applies.",
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = startAfterAdd,
                enabled = !busy && !inventoryStale && phoneAddAttemptSession?.attemptedTicket == null,
                role = Role.Checkbox, onValueChange = { startAfterAdd = it }),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = startAfterAdd,
                enabled = !busy && !inventoryStale && phoneAddAttemptSession?.attemptedTicket == null,
                onCheckedChange = null)
            Text("Start downloading after Add (recommended)", modifier = Modifier.padding(start = 8.dp))
        }
        AddArtworkChoice(automaticArtwork,
            enabled = !busy && phoneAddAttemptSession?.attemptedTicket == null) { automaticArtwork = it }
        if (magnet.isNotEmpty() && !isLink) {
        Text("On this phone · Place: " + (proposedAddDestination?.absolutePath ?: "Choose a download location below"),
            style = MaterialTheme.typography.bodySmall)
            LocalMagnetDiscoveryPrompt(
                magnet = magnet,
                dhtEnabled = dht,
                applying = dhtSaving,
                blocked = busy || inventoryStale,
                onEnable = {
                    if (onOpenOptions != null) {
                        // Point to the existing DHT preference, not a new settings authority.
                        phoneOptionsSearch = "dht"
                        onOpenOptions()
                    } else requestDhtSetting(true)
                },
                opensOptions = onOpenOptions != null
            )
            ActionTile(if (startAfterAdd) "Add magnet and start" else "Add magnet (metadata only)",
                ChunksColors.Purple, icon = R.drawable.chunks_ic_magnet, stacked = false, onClick = {
                if (sourceReadJob != null) return@ActionTile
                // Submitting closes the keyboard, so the form's collapse is not
                // racing an IME animation (see the Connect transition fix).
                addKeyboard?.hide()
                addFocus.clearFocus()
                val selectedDraft = addFolderDraft
                val selectedPlace = place
                val checkedMagnet = magnetCheck as? MagnetDraftCheck.Valid ?: return@ActionTile
                if (PhoneUniversalAddAdapter.alreadyPresentInFreshInventory(
                        checkedMagnet.infoHash, transfers, hasConfirmedLocalInventory && !inventoryStale)) {
                    noteProblem("This exact torrent already exists on this phone. No second Add was sent. Open its transfer card instead.")
                    return@ActionTile
                }
                val selectedMagnet = checkedMagnet.normalized
                val autoStart = startAfterAdd
                val allowArtwork = automaticArtwork
                val orderLater = magnetFinishInOrder
                val inventoryReadyAtClick = addInventoryReady
                performLocalMutation phoneMagnet@{
                    val selected = selectedPlace ?: run {
                        noteProblem("Choose a verified phone download location before Add. Nothing was sent.")
                        return@phoneMagnet
                    }
                    val session = phoneAddAttemptSession
                    val ticket = if (session == null) null else try {
                        authorizePhoneTicket(
                            PhoneUniversalAddAdapter.magnetIntent(checkedMagnet, selected,
                                selectedDraft, autoStart, allowArtwork),
                            selected, inventoryReadyAtClick)
                    } catch (_: IllegalArgumentException) {
                        noteProblem("This phone Add source or subfolder is invalid. Nothing was sent.")
                        return@phoneMagnet
                    }
                    if (session != null && ticket == null) return@phoneMagnet
                    val enteredNative = java.util.concurrent.atomic.AtomicBoolean(false)
                    val nativeResult = java.util.concurrent.atomic.AtomicReference<LocalAddResult?>(null)
                    fun settleTicket() {
                        if (session == null || ticket == null) return
                        if (!enteredNative.get()) session.coordinator.refusedBeforeAnyWrite(ticket)
                        else nativeResult.get()?.let { session.noteNativeResult(ticket, it, selected) }
                            ?: session.coordinator.requireReadback(ticket)
                    }
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val key = phoneAddedKey(checkedMagnet.infoHash)
                            if (!allowArtwork && (key == null ||
                                mediaController?.prepareForAdd(key, false) != true)) {
                                return@withContext LocalAddResult.Refused(
                                    "The artwork exclusion could not be saved. Nothing was added. Try again or change Artwork & metadata.")
                            }
                            val chosen = LocalOwnedAddFolder.prepare(
                                LocalDownloadPlaces.materialize(selected), selectedDraft)
                            if (chosen == null) LocalAddResult.Refused(
                                "The download location or subfolder is unavailable, nonempty or unsafe. Nothing was added.")
                            else {
                                val engine = LocalEngineHolder.get(context)
                                // Even a Refused after entering libtorrent may have
                                // registered a job. Never disarm based on that enum.
                                enteredNative.set(true)
                                engine.addMagnet(selectedMagnet, chosen,
                                    autoStartAfterMetadata = autoStart).also(nativeResult::set)
                            }
                        }
                        settleTicket()
                        if (orderLater && result is LocalAddResult.FetchingMetadata) withContext(Dispatchers.IO) {
                            finishInOrderStore.requestAfterMetadata(result.status.infoHash)
                        }
                        message = when (result) {
                            is LocalAddResult.Refused -> if (enteredNative.get())
                                "The phone engine did not confirm this magnet Add after its native write boundary. " +
                                    "Check the exact phone transfer list; no retry occurred."
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
                            (ticket != null && session.announceNativeRegistrationOnce(ticket))))
                            rememberAdded(registeredHash, message)
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
                        settleTicket()
                        throw cancel
                    } catch (_: Exception) {
                        settleTicket()
                        inventoryFrame = inventoryFrame.copy(stale = true)
                        noteProblem("Magnet Add may have reached the native engine. Wait for a fresh exact transfer list; no retry occurred.")
                    }
                }
            }, enabled = !busy && !sourceReading && addInventoryReady && !dhtSaving && !placeSaving && proposedAddDestination != null &&
                magnetCheck is MagnetDraftCheck.Valid &&
                phoneAddAttemptSession?.attemptedTicket == null,
                modifier = Modifier.fillMaxWidth())
        }
        // Keep both intake methods discoverable before optional storage, seeding
        // and engine explanations. Existing Place and safety gates remain authoritative.
        PhoneEngineAbout()
        LocalDownloadPlacePicker(
            resolution = addPlaceResolution,
            enabled = !busy && !inventoryStale && !placeSaving &&
                phoneAddAttemptSession?.attemptedTicket == null,
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
                        message = "Phone default saved for future Adds. Existing transfers keep their files where they are."
                    } else noteProblem("That download location could not be saved. Check phone download locations before trying again.")
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
                        message = "New phone downloads use the automatic location. Existing transfers keep their files where they are."
                    } else noteProblem("The saved download location could not be cleared. Check phone download locations before trying again.")
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
            label = { Text("Optional phone download subfolder") },
            placeholder = { Text("Leave blank for Chunks default") },
            singleLine = true,
            // A folder name is typed exactly: no autocorrect or auto-capitalisation.
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            // Only the typed name can be wrong; an unresolved download location is
            // reported separately ("Checking where this phone can save…").
            isError = addFolderNameInvalid,
            enabled = !busy && !inventoryStale && phoneAddAttemptSession?.attemptedTicket == null,
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
        // An Add preference, kept with the other Add settings rather than
        // between the source field and the Start choice.
        clipboardPrefillOn?.let { on ->
            ClipboardPrefillToggle(checked = on, enabled = true, onChange = { next ->
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { ClipboardMagnetPrefill.setEnabled(context, next) }
                    if (saved) clipboardPrefillOn = next
                    else noteProblem("That clipboard choice could not be saved. It is unchanged.")
                }
            })
        }
        if (magnet.isNotEmpty() && destination != null) {
            FinishInOrderAddChoice(
                "Finish in Order once the file list arrives (episodes or numbered files)",
                checked = magnetFinishInOrder,
                enabled = !busy && !inventoryStale && phoneAddAttemptSession?.attemptedTicket == null,
                onChecked = { magnetFinishInOrder = it },
                defaultOn = finishInOrderDefault,
                onDefault = { on -> if (finishInOrderStore.setDefaultForNewAdds(on)) finishInOrderDefault = on }
            )
            Text("Saves into the download location shown above. Magnets first discover metadata via peers/trackers; if Start after Add is selected, payload downloading begins after metadata arrives. Without peers, downloading cannot begin.",
                style = MaterialTheme.typography.bodySmall)
        }
        }
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
                    Text("Only the missing tracker list in this stopped phone torrent " +
                        "will be restored, then saved to its existing resume record. " +
                        "No downloaded files, destination, file priorities, or running " +
                        "state are changed. Tracker addresses may contain private " +
                        "passkeys and can disclose your IP if you later Start. " +
                        "Repair never starts a torrent or makes an rTorrent request.",
                        style = MaterialTheme.typography.bodySmall)
                    if (!eligible) {
                        Text("The phone queue changed, became stale, or the transfer " +
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
                                                "saved phone session. The torrent is still stopped; " +
                                                "press Start separately when ready."
                                            LocalTrackerRepairOutcome.REFUSED ->
                                                "Tracker repair refused: the original or current " +
                                                "stopped transfer did not satisfy the safety checks."
                                            LocalTrackerRepairOutcome.NOT_FOUND ->
                                                "That torrent is no longer in the phone engine."
                                            LocalTrackerRepairOutcome.UNCERTAIN -> {
                                                inventoryFrame = inventoryFrame.copy(stale = true)
                                                "Tracker repair or resume persistence was not verified. " +
                                                "Refresh the phone list before making another change; " +
                                                "do not blindly retry."
                                            }
                                        }
                                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                                        throw cancel
                                    } catch (_: Exception) {
                                        inventoryFrame = inventoryFrame.copy(stale = true)
                                        noteProblem("Tracker repair was not confirmed. Check a fresh " +
                                            "phone status before any other change.")
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
        if (placeResolution != null && destination == null)
            Text("Phone storage for Chunks is unavailable right now.",
                color = MaterialTheme.colorScheme.error)
        // Outcomes include successes ("Added and started…") as well as refusals,
        // so present them as a neutral, politely announced status rather than
        // colouring every result as an error. The wording carries the outcome.
        message?.let { text ->
            // Only an Add outcome whose exact hash is in the observed
            // inventory can offer to show it; refusals never do.
            val newHash = addedOutcome?.takeIf { it.first == text }?.second
                ?.takeIf { hash -> transfers.any { it.infoHash.equals(hash, ignoreCase = true) } }
            PhoneOutcomeNotice(text,
                needsAttention = problemOutcome == text,
                actionLabel = if (newHash != null) "Show new transfer" else null,
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
            Text("Phone torrent status unavailable. Showing the last confirmed transfers. " +
                "Start, Stop, Remove, file selection, new Adds and library publication are disabled " +
                "until a fresh engine status read succeeds.",
                color = MaterialTheme.colorScheme.error)
        }

        val add = pending
        if (add != null && destination == null) {
            Text("Selected .torrent: " + add.preview.name, fontWeight = FontWeight.Bold)
        }
        if (add != null && canReviewOnConnectedServer &&
            onPrepareRemoteTorrent != null) {
            ReviewedTorrentTargetChooser(
                verifiedTorrentBytes = add.bytes,
                phonePlaceAvailable = destination != null,
                enabled = !busy && !sourceReading && phoneAddAttemptSession?.attemptedTicket == null,
                onPrepareServerReview = prepare@{ original ->
                    if (sourceReadJob != null || pending !== add) return@prepare
                    // The parent only enables this action for an authenticated,
                    // independently privacy-gated remote target.
                    onPrepareRemoteTorrent(original)
                    pending = null
                }
            )
        }
        var finishInOrderAfterAdd by remember(add?.preview?.infoHashV1) { mutableStateOf(finishInOrderDefault) }
        if (add != null && destination != null && add.preview.fileCount >= 2) {
            FinishInOrderAddChoice(
                "Finish in Order: prioritise the first episode or numbered file, then the next " +
                    "(set up before downloading starts; change or turn off from Files)",
                checked = finishInOrderAfterAdd,
                enabled = !busy && !inventoryStale && phoneAddAttemptSession?.attemptedTicket == null,
                onChecked = { finishInOrderAfterAdd = it },
                defaultOn = finishInOrderDefault,
                onDefault = { on -> if (finishInOrderStore.setDefaultForNewAdds(on)) finishInOrderDefault = on }
            )
        }
        val seedingPreview = remember(add?.preview?.infoHashV1) {
            add?.preview?.let { phoneSeedingPreview(context, it) }
        }
        if (add != null && destination != null) LocalAddReview(
            add.preview, proposedAddDestination ?: destination,
            placeTitle = place?.title,
            busy = busy || sourceReading || !addInventoryReady || placeSaving || proposedAddDestination == null ||
                phoneAddAttemptSession?.attemptedTicket != null,
            startAfterAdd = startAfterAdd,
            cancelEnabled = !busy && phoneAddAttemptSession?.attemptedTicket == null,
            storagePreflight = addStorage?.takeIf { it.first == add.preview.infoHashV1 }?.second,
            seeding = seedingPreview,
            onConfirm = confirm@{
                if (sourceReadJob != null || pending !== add) return@confirm
                if (PhoneUniversalAddAdapter.alreadyPresentInFreshInventory(
                        add.preview.infoHashV1, transfers, hasConfirmedLocalInventory && !inventoryStale)) {
                    noteProblem("This exact torrent already exists on this phone. No second Add was sent. Open its transfer card instead.")
                    return@confirm
                }
                val selectedDraft = addFolderDraft
                val selectedPlace = place
                val autoStart = startAfterAdd
                val allowArtwork = automaticArtwork
                val orderAfterAdd = finishInOrderAfterAdd
                val inventoryReadyAtClick = addInventoryReady
                var orderNote: String? = null
                performLocalMutation phoneDocument@{
                    val selected = selectedPlace ?: run {
                        noteProblem("Choose a verified phone download location before Add. Nothing was sent.")
                        return@phoneDocument
                    }
                    val session = phoneAddAttemptSession
                    val ticket = if (session == null) null else try {
                        // Read-only validation can parse up to 2 MiB of metainfo.
                        // Keep it off the UI thread; authorization remains on
                        // the UI lane after one immutable byte snapshot is ready.
                        val frozenIntent = withContext(Dispatchers.Default) {
                            PhoneUniversalAddAdapter.torrentIntent(add, selected,
                                selectedDraft, autoStart, allowArtwork)
                        }
                        authorizePhoneTicket(frozenIntent, selected, inventoryReadyAtClick)
                    } catch (_: IllegalArgumentException) {
                        noteProblem("This .torrent source or subfolder is invalid. Nothing was sent.")
                        return@phoneDocument
                    }
                    if (session != null && ticket == null) return@phoneDocument
                    val enteredNative = java.util.concurrent.atomic.AtomicBoolean(false)
                    val nativeResult = java.util.concurrent.atomic.AtomicReference<LocalAddResult?>(null)
                    fun settleTicket() {
                        if (session == null || ticket == null) return
                        if (!enteredNative.get()) session.coordinator.refusedBeforeAnyWrite(ticket)
                        else nativeResult.get()?.let { session.noteNativeResult(ticket, it, selected) }
                            ?: session.coordinator.requireReadback(ticket)
                    }
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val key = phoneAddedKey(add.preview.infoHashV1)
                            if (!allowArtwork && (key == null ||
                                mediaController?.prepareForAdd(key, false) != true)) {
                                return@withContext LocalAddResult.Refused(
                                    "The artwork exclusion could not be saved. Nothing was added. Try again or change Artwork & metadata.") to null
                            }
                            val chosen = LocalOwnedAddFolder.prepare(
                                LocalDownloadPlaces.materialize(selected), selectedDraft)
                            if (chosen == null)
                                LocalAddResult.Refused(
                                    "The download location or subfolder is unavailable, nonempty or unsafe. Nothing was added.") to null
                            else {
                                val engine = LocalEngineHolder.get(context)
                                enteredNative.set(true)
                                // The shared ticket freezes the exact reviewed
                                // bytes: never execute with the mutable screen
                                // holder after authorization and a suspend gap.
                                val authorizedBytes = ticket?.intent?.source?.metainfoForSubmission()
                                    ?: add.bytes
                                val added = engine.addStoppedChecked(authorizedBytes, chosen)
                                    .also(nativeResult::set)
                                // Configure file order while stopped, before any
                                // explicitly requested payload Start.
                                if (orderAfterAdd && added is LocalAddResult.AddedStopped) {
                                    orderNote = enableFinishInOrderAfterAdd(context, engine, added.status.infoHash)
                                }
                                if (autoStart && added is LocalAddResult.AddedStopped)
                                    added to engine.resume(added.status.infoHash)
                                else added to null
                            }
                        }
                        settleTicket()
                        val (added, started) = result
                        message = when (added) {
                            is LocalAddResult.Refused -> if (enteredNative.get())
                                "The native phone engine did not confirm this torrent Add after entering its write boundary. " +
                                    "Check the exact phone transfer list; no retry occurred."
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
                                    "Added, but Start was not confirmed. Check the next phone transfer list before trying again."
                                }
                                LocalCommandOutcome.NOT_FOUND ->
                                    "Added but could not locate the torrent for Start. Inspect the list before retrying."
                                null -> "Added stopped. Start manually from its transfer card."
                            }
                        }
                        orderNote?.let { note -> message = listOfNotNull(message, note).joinToString(" ") }
                        if (added is LocalAddResult.Refused || (added is LocalAddResult.AddedStopped &&
                                started != null && started.outcome != LocalCommandOutcome.VERIFIED))
                            problemOutcome = message
                        if (added is LocalAddResult.AddedStopped && (session == null ||
                            (ticket != null && session.announceNativeRegistrationOnce(ticket))))
                            rememberAdded(added.status.infoHash, message)
                        if (added !is LocalAddResult.Refused) {
                            automaticArtwork = true
                            pending = null
                            addFolderDraft = ""
                            currentAddPlaceId = null
                            addExpanded = false
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        settleTicket()
                        throw cancel
                    } catch (_: Exception) {
                        settleTicket()
                        inventoryFrame = inventoryFrame.copy(stale = true)
                        noteProblem("Torrent Add may have reached the native engine. Check the next fresh exact transfer list; no retry occurred.")
                    }
                }
            },
            onCancel = cancel@{
                if (busy || (pending !== add && sourceReadJob == null)) return@cancel
                cancelSourceRead()
                pending = null
                initialTorrentUri?.let(onTorrentConsumed)
                currentAddPlaceId = null
                addFolderDraft = ""
            })

        snapshotRestoreDraft?.let { draft ->
            val eligible = hasConfirmedLocalInventory && !inventoryStale && !busy && !sourceReading &&
                pending == null && place != null &&
                draft.frozenHashes == transfers.map { it.infoHash } &&
                LocalScreenReader.revision.snapshot() == draft.frozenGeneration
            LocalSnapshotStoppedRestoreReview(
                draft = draft,
                selectedPlace = place?.title ?: "No verified phone download location",
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
                                    val folder = LocalDownloadPlaces.materialize(selected)
                                        ?: return@withContext SnapshotRestoreOnceV1.Outcome.REFUSED
                                    val targetIdentity = LocalSnapshotRestoreFingerprint.of(
                                        "chunks-local-v1:" + context.filesDir.canonicalPath)
                                    val placeIdentity = LocalSnapshotRestoreFingerprint.of(
                                        "chunks-place-v1:" + folder.canonicalPath)
                                    val journal = SnapshotRestoreJournalV1.open(
                                        AndroidSnapshotRestoreJournalStore(context),
                                        SnapshotRestoreOnceV1.itemArchiveFingerprint(
                                            draft.archiveFingerprint, draft.original.infoHashV1),
                                        targetIdentity, listOf(draft.original.infoHashV1))
                                    SnapshotRestoreOnceV1.execute(
                                        journal = journal,
                                        reviewed = draft.original,
                                        chosenPlace = folder,
                                        verifiedPlaceFingerprint = placeIdentity,
                                        frozenHashes = draft.frozenHashes,
                                        userApproved = true,
                                        target = SnapshotRestoreOnceV1.localEngine(
                                            LocalEngineHolder.get(context))
                                    )
                                } catch (cancel: kotlinx.coroutines.CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED
                                }
                            }
                            message = when (result) {
                                SnapshotRestoreOnceV1.Outcome.VERIFIED_PRESENT_STOPPED ->
                                    "Snapshot definition restored on this phone, confirmed stopped. " +
                                    "No downloaded data was moved. Press Start separately."
                                SnapshotRestoreOnceV1.Outcome.REFUSED ->
                                    "Restore refused: verify the current phone download Place and " +
                                    "original torrent before another review."
                                SnapshotRestoreOnceV1.Outcome.STALE_TARGET ->
                                    "Phone inventory changed. Nothing was submitted; compare again."
                                SnapshotRestoreOnceV1.Outcome.JOURNAL_BLOCKED ->
                                    "Restore journal is already pending, invalid, or could not be " +
                                    "durably committed. No new Add was authorized. Review the " +
                                    "existing transfer list; never blindly repeat an uncertain Add."
                                SnapshotRestoreOnceV1.Outcome.UNCERTAIN ->
                                    "Restore Add may have reached the phone engine, but independent " +
                                    "stopped-state confirmation failed. Check a fresh transfer list " +
                                    "and journal status before any explicit recovery action."
                            }
                        }
                    }
                }
            )
        }

        } // Add presentation
        if (pane == PhonePane.TRANSFERS) {
        moveServer?.let { server ->
            val digest = TransferMoveStore.scopeDigest(server.aliasScope)
            val pending = remember(transfers, moveRevision, digest) {
                moveStore.all(TransferMove.Direction.PHONE_TO_SERVER).filter { record ->
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
                        val engine = LocalEngineHolder.current() ?: error("Phone engine unavailable")
                        val live = engine.status(hash) ?: error("Original phone job absent")
                        if (!TransferMove.sourceIdentityMatches(
                                original, live.name, live.nativeReviewToken))
                            error("The original phone generation was replaced")
                        if (!engine.removeKeepingDataIfMatching(
                                hash, original.name, live.savePath,
                                requireNotNull(original.sourceReviewToken)))
                            error("The original phone job could not be removed safely")
                    }
                },
                observeSource = { hash -> TransferMoveObservations.phone(hash) },
                save = { record -> moveStore.save(record) },
                forget = { record -> moveStore.remove(record) },
                onFinished = { text -> message = text; moveRevision++ }
            )
            OrphanMoveRecoveryPanel(
                direction = TransferMove.Direction.PHONE_TO_SERVER,
                serverAliasScope = server.aliasScope,
                visibleSourceHashes = transfers.map { it.infoHash },
                enabled = !busy && !inventoryStale && hasConfirmedLocalInventory,
                revision = moveRevision,
                observeSource = { hash -> TransferMoveObservations.phone(hash) },
                onChanged = {
                    message = "Only the resolved local Move reminder was cleared. " +
                        "Torrent jobs and downloaded files were not changed."
                    moveRevision++
                }
            )
        }
        // Batch selection acts on the list itself, so it sits with the list (as on
        // rTorrent) and near the outcome/confirmation area above it. With no
        // phone transfers there is nothing to select, so it is not shown.
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
                        .map { io.github.gamesbyian.chunks.model.BatchServerCopy.Item(it.infoHash, it.name) }
                    performLocalMutation {
                        val problem = io.github.gamesbyian.chunks.model.BatchServerCopy.validate(items)
                        message = if (problem != null) problem else try {
                            val report = io.github.gamesbyian.chunks.model.BatchServerCopy.run(items,
                                exportTorrent = { hash ->
                                    withContext(Dispatchers.IO) {
                                        LocalEngineHolder.current()?.exportTorrentFile(hash)
                                    }
                                },
                                addStopped = link.addStoppedAtDefault)
                            report.summary() + report.results
                                .filter { it.second != io.github.gamesbyian.chunks.model.BatchServerCopy.Result.ADDED }
                                .take(10).joinToString("") { (item, result) ->
                                    "\n• " + item.name.take(80) + ": " + result.label
                                }
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            "The batch copy stopped unexpectedly. Check the server list before " +
                                "copying again; nothing is retried automatically."
                        }
                    }
                }
            },
            onBatchMoveToServer = if (serverCopy == null || moveServer == null) null else { ids ->
                val link = serverCopy
                val server = moveServer
                val items = transfers.filter { it.infoHash in ids }
                    .map { io.github.gamesbyian.chunks.model.BatchServerCopy.Item(it.infoHash, it.name) }
                performLocalMutation {
                    val problem = io.github.gamesbyian.chunks.model.BatchServerCopy.validate(items)
                    message = if (problem != null) problem else try {
                        val report = io.github.gamesbyian.chunks.model.BatchServerCopy.run(items,
                            exportTorrent = { hash ->
                                withContext(Dispatchers.IO) { LocalEngineHolder.current()?.exportTorrentFile(hash) }
                            },
                            addStopped = link.addStoppedAtDefault)
                        // Only destination jobs verified by readback (or already present) start a Move.
                        val verified = report.results.filter {
                            it.second == io.github.gamesbyian.chunks.model.BatchServerCopy.Result.ADDED ||
                                it.second == io.github.gamesbyian.chunks.model.BatchServerCopy.Result.ALREADY_ON_SERVER
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
                                if (!io.github.gamesbyian.chunks.model.BatchServerCopy
                                        .observedDestinationForMove(result, observed)) {
                                    missingReadback++
                                    continue
                                }
                                val original = transfers.singleOrNull {
                                    it.infoHash.equals(item.infoHash, ignoreCase = true) &&
                                        it.name == item.name && it.nativeReviewToken.isNotBlank()
                                } ?: continue
                                val saved = moveStore.save(TransferMove.start(
                                    TransferMove.Direction.PHONE_TO_SERVER,
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
                            "original phone jobs below." +
                            (if (aliasCopied > 0) " $aliasCopied display aliases carried to the server view." else "") +
                            (if (aliasFailed > 0) " $aliasFailed display aliases could not be carried; edit them on the server view." else "") +
                            (if (missingReadback > 0) " $missingReadback destination jobs could not be independently confirmed on this exact server. Their original phone jobs were kept; check the destination before a new Move review." else "")
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        "The batch move stopped before removal. Check the server list; nothing was removed " +
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
        val addedHidden = remember(lastAddedHash, lastAddedView, transfers, displayedTransfers,
            transferSearch, transferFilter) {
            lastAddedView == transferSearch + "\u0000" + transferFilter.name &&
                PhoneAddedVisibility.hiddenByView(lastAddedHash, transfers.map { it.infoHash },
                    displayedTransfers.map { it.infoHash })
        }
        if (addedHidden) {
            PhoneAddedHiddenNotice(onShowAll = {
                transferSearch = ""
                chooseLocalFilter(LocalQueueFilter.ALL)
            })
        }
        // An empty phone says so once, instead of "Showing 0 of 0" above it.
        if (transfers.isNotEmpty())
            Text("Showing ${displayedTransfers.size} of ${transfers.size} transfers on this device",
                style = MaterialTheme.typography.bodySmall)
        if (transfers.isEmpty()) {
            // Search and filters precede welcome even for an empty inventory.
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
                                document != null -> onPrepareRemoteTorrent(document)
                                prepared != null -> send(prepared)
                                else -> message = "This torrent has no supported v1 identity to hand off."
                            }
                        }
                    }
                }
            LocalTransferCard(transfer, busy || inventoryStale, dhtEnabled = dht,
                stale = inventoryStale,
                // Same-list relations; opening one filters this list to that exact phone job.
                similar = {
                    val source = remember(transfers, inventoryStale) { phoneSimilarSource(transfers, inventoryStale) }
                    source.transfers.firstOrNull { it.key.infoHash == transfer.infoHash.lowercase() }?.let { self ->
                        SimilarTransfersHost(self, listOf(source),
                            environmentLabel = { "This phone" },
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
                                        "Stopped on this phone, but its recovery checkpoint could not be confirmed. " +
                                            "Keep Chunks open for the next save; downloaded files remain untouched."
                                    else serviceProblem
                                LocalCommandOutcome.REFUSED ->
                                    "Start was refused: unreviewed destination files may be overwritten. Inspect the transfer's storage warning; no Start was issued."
                                LocalCommandOutcome.UNCERTAIN -> {
                                    inventoryFrame = inventoryFrame.copy(stale = true)
                                    "The engine has not confirmed the change yet. Wait for fresh status before trying again."
                                }
                                LocalCommandOutcome.NOT_FOUND -> "This transfer is no longer in the phone engine."
                            }
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            // The command may have reached libtorrent before the read or JNI call failed.
                            // Do not invite another write until a successful inventory read.
                            inventoryFrame = inventoryFrame.copy(stale = true)
                            noteProblem("Start/Stop was not confirmed. Wait for fresh phone torrent status before trying again.")
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
                            message = when (outcome) {
                                LocalCommandOutcome.VERIFIED -> null
                                LocalCommandOutcome.REFUSED -> "File selection was refused. Inspect the transfer before changing files."
                                LocalCommandOutcome.NOT_FOUND -> "This transfer or file is no longer available."
                                LocalCommandOutcome.UNCERTAIN -> "File selection is unconfirmed. Wait for fresh status before trying again."
                            }
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
                            message = when (result) {
                                LocalFileBatchOutcome.VERIFIED ->
                                    "Episode-first priorities verified. The first unfinished " +
                                    "episode is High; later episodes receive background priority. " +
                                    "This is a one-time focus, not automatic completion scheduling."
                                LocalFileBatchOutcome.UNCHANGED ->
                                    "Episodes already have this focus. No priorities changed."
                                LocalFileBatchOutcome.STALE_REVIEW ->
                                    "Episode files or priorities changed. Refresh the file list " +
                                    "and review the order again."
                                LocalFileBatchOutcome.NOT_FOUND ->
                                    "Transfer is no longer available on the phone."
                                LocalFileBatchOutcome.UNCERTAIN ->
                                    "Some episode priorities may have changed. Refresh the exact " +
                                    "file list before taking another action; no automatic retry."
                            }
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
                            message = when (outcome) {
                                LocalFileBatchOutcome.VERIFIED ->
                                    "Native file download priority verified. No downloaded data was deleted."
                                LocalFileBatchOutcome.UNCHANGED ->
                                    "This file already has that priority. Nothing changed."
                                LocalFileBatchOutcome.STALE_REVIEW ->
                                    "File identity or priority changed during review. Refresh files first."
                                LocalFileBatchOutcome.NOT_FOUND ->
                                    "The target file or transfer was not found."
                                LocalFileBatchOutcome.UNCERTAIN ->
                                    "Native file priority change not confirmed. Refresh status before another change."
                            }
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
                            message = when (result) {
                                LocalFileBatchOutcome.VERIFIED ->
                                    "Page-level file selection verified. Previously downloaded bytes were kept."
                                LocalFileBatchOutcome.UNCHANGED ->
                                    "The selected files already have that priority. Nothing was changed."
                                LocalFileBatchOutcome.STALE_REVIEW ->
                                    "File selection changed since review. No batch write was submitted; refresh the file list."
                                LocalFileBatchOutcome.NOT_FOUND ->
                                    "Torrent metadata unavailable. No file batch was submitted."
                                LocalFileBatchOutcome.UNCERTAIN ->
                                    "Batch selection outcome uncertain: some priorities may have changed. " +
                                        "Wait for fresh file readback before considering another action."
                            }
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
                        direction = TransferMove.Direction.PHONE_TO_SERVER,
                        infoHash = transfer.infoHash, name = transfer.name,
                        sourceReviewToken = transfer.nativeReviewToken,
                        serverAliasScope = moveServer?.aliasScope?.takeIf { prepareRemoteHandoff != null },
                        busy = busy || inventoryStale,
                        ports = TransferMovePorts(
                            observeTarget = moveServer?.let { server -> { server.observe(transfer.infoHash) } },
                            observeSource = { TransferMoveObservations.phone(transfer.infoHash) },
                            removeSource = {
                                withContext(Dispatchers.IO) {
                                    val confirmed = LocalEngineHolder.current()?.removeKeepingDataIfMatching(
                                        transfer.infoHash, transfer.name, transfer.savePath,
                                        transfer.nativeReviewToken
                                    )
                                    if (confirmed != true)
                                        error("The reviewed original phone session no longer matches")
                                }
                            },
                            targetUnavailableReason = "Connect to the server account to check it."
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
                        val deletion = if (receipt.dispatch in setOf(
                                LocalDeleteDataDispatch.REQUESTED,
                                LocalDeleteDataDispatch.UNCERTAIN) &&
                            receipt.nativeCompletion != null) {
                            withContext(Dispatchers.IO) {
                                val deadline = System.nanoTime() + 15_000_000_000L
                                var value = receipt.payloadReadback()
                                while (value == LocalDeleteDataReadback.UNAVAILABLE &&
                                    System.nanoTime() < deadline) {
                                    Thread.sleep(150)
                                    value = receipt.payloadReadback()
                                }
                                value
                            }
                        } else LocalDeleteDataReadback.UNAVAILABLE
                        message = when (receipt.dispatch) {
                            LocalDeleteDataDispatch.REFUSED ->
                                "Deletion was not requested: " +
                                    (receipt.reason ?: "The transfer or its destination needs fresh review.")
                            LocalDeleteDataDispatch.NOT_FOUND ->
                                "Deletion was not requested: this transfer is no longer in the phone engine."
                            LocalDeleteDataDispatch.REQUESTED, LocalDeleteDataDispatch.UNCERTAIN ->
                                when (deletion) {
                                    LocalDeleteDataReadback.ABSENT ->
                                        "Verified: the reviewed transfer was removed, native deletion completed and all its exact payload files are absent. Unrelated folder contents were not requested for deletion."
                                    LocalDeleteDataReadback.STILL_PRESENT ->
                                        "Native deletion finished but at least one reviewed payload file remains. The operation is incomplete. Recheck the folder before any new action."
                                    LocalDeleteDataReadback.NATIVE_FAILED ->
                                        "Native deletion failed. No retry was sent; inspect the remaining files and transfer state before trying again."
                                    LocalDeleteDataReadback.UNAVAILABLE ->
                                        "Deletion outcome is still uncertain. The transfer may already be removed. No automatic retry will be sent; refresh the folder and engine state before acting."
                                }
                        }
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
        if (pane == PhonePane.OPTIONS) {
        // App-wide settings live behind a real Options destination.
        Text("Options · this device", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp)
                .bringIntoViewRequester(optionsRequester)
                .semantics { heading() })
        ClearableTransferSearchField(value = phoneOptionsSearch, onValueChange = { phoneOptionsSearch = it },
            label = "Search options")
        val privacyControlsHosted = io.github.gamesbyian.chunks.privacy.LocalPrivacyControls.current != null
        if (phoneOptionsSearch.isNotBlank()) {
            // Only entries whose screen or panel exists here; a result never changes a value.
            val results = PhoneOptionsShortcut.search(phoneOptionsSearch).filter {
                when (it) {
                    PhoneOptionsShortcut.SEEDING_RULES -> onSeedingRules != null
                    PhoneOptionsShortcut.MEDIA -> onMediaSettings != null
                    PhoneOptionsShortcut.CREATE_TORRENT -> onCreateTorrent != null
                    PhoneOptionsShortcut.ZIP_REVIEW -> onZipReview != null
                    PhoneOptionsShortcut.PRIVACY -> privacyControlsHosted
                    PhoneOptionsShortcut.CONNECTIONS -> onConnections != null
                    PhoneOptionsShortcut.SERVER_PLACES -> onRemotePlaces != null
                    PhoneOptionsShortcut.SERVER_ORGANIZE -> onRemoteOrganize != null
                    PhoneOptionsShortcut.SERVER_NAMES -> onRemoteNames != null
                    PhoneOptionsShortcut.SERVER_BACKUP -> onRemoteSnapshot != null
                    else -> true
                }
            }
            if (results.isEmpty()) Text("No matching option. Clear search to see all settings and tools.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            results.forEach { shortcut ->
                OutlinedButton(onClick = {
                    phoneOptionsSearch = ""
                    when (shortcut) {
                        PhoneOptionsShortcut.SEEDING_RULES -> onSeedingRules?.invoke()
                        PhoneOptionsShortcut.MEDIA -> onMediaSettings?.invoke()
                        PhoneOptionsShortcut.CONNECTIONS -> onConnections?.invoke()
                        PhoneOptionsShortcut.SERVER_PLACES -> onRemotePlaces?.invoke()
                        PhoneOptionsShortcut.SERVER_ORGANIZE -> onRemoteOrganize?.invoke()
                        PhoneOptionsShortcut.SERVER_NAMES -> onRemoteNames?.invoke()
                        PhoneOptionsShortcut.SERVER_BACKUP -> onRemoteSnapshot?.invoke()
                        PhoneOptionsShortcut.CREATE_TORRENT -> onCreateTorrent?.invoke()
                        PhoneOptionsShortcut.ZIP_REVIEW -> onZipReview?.invoke()
                        PhoneOptionsShortcut.DHT -> scope.launch { androidx.compose.runtime.withFrameNanos { }; dhtRequester.bringIntoView() }
                        PhoneOptionsShortcut.DOWNLOAD_LOCATION -> {
                            // These controls still live with the existing Add workflow.
                            // Navigating cannot authorize or submit a transfer.
                            if (onOpenAdd != null) onOpenAdd.invoke()
                            else { addExpanded = true; addNavigationRevision++ }
                        }
                        PhoneOptionsShortcut.SPEED_LIMITS -> scope.launch { androidx.compose.runtime.withFrameNanos { }; speedRequester.bringIntoView() }
                        PhoneOptionsShortcut.PRIVACY -> scope.launch { androidx.compose.runtime.withFrameNanos { }; privacyRequester.bringIntoView() }
                        PhoneOptionsShortcut.SNAPSHOT_RESTORE -> scope.launch { androidx.compose.runtime.withFrameNanos { }; restoreRequester.bringIntoView() }
                        PhoneOptionsShortcut.EXPORTS -> { showPhoneTools = true; scope.launch { androidx.compose.runtime.withFrameNanos { }; exportsRequester.bringIntoView() } }
                        PhoneOptionsShortcut.COLLECTION_NAMES -> scope.launch { androidx.compose.runtime.withFrameNanos { }; namesRequester.bringIntoView() }
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
            modifier = Modifier.fillMaxWidth().then(if (phoneOptionsSearch.isBlank()) Modifier
                else Modifier.height(0.dp).clipToBounds().clearAndSetSemantics { }),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        if (onConnections != null) {
            Text("Clients & environments", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onConnections, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Manage connections and environments")
            }
        }
        if (remoteEnvironmentLabel != null && onRemotePlaces != null &&
            onRemoteOrganize != null && onRemoteNames != null && onRemoteSnapshot != null) {
            // The local Options route is independently privacy-gated from
            // remote profiles. Do not speak the server host/username here.
            Text("Connected server tools", style = MaterialTheme.typography.titleMedium)
            Text("These tools operate only on the currently authenticated server. Opening a tool does not submit an Add or change any torrent.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onRemotePlaces,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Server folders & destinations")
            }
            OutlinedButton(onClick = onRemoteOrganize,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Server collections")
            }
            OutlinedButton(onClick = onRemoteNames,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Server transfer names")
            }
            OutlinedButton(onClick = onRemoteSnapshot,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Server inventory backup")
            }
        }
        Text("Automation & media", style = MaterialTheme.typography.titleMedium)
        if (onSeedingRules != null) OutlinedButton(onClick = onSeedingRules,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(seedingFocus)) {
            Text("Seeding rules…")
        }
        if (onMediaSettings != null) OutlinedButton(onClick = onMediaSettings,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Artwork & media info…")
        }
        Text("Privacy, security & bandwidth", style = MaterialTheme.typography.titleMedium)
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
                SettingScopeNote(PHONE_ENVIRONMENT_LABEL)
                if (dhtSaving) Text("Applying DHT setting…", style = MaterialTheme.typography.bodySmall)
                dhtError?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error) }
            }
            Switch(checked = dht, enabled = !dhtSaving, onCheckedChange = null)
        }
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(speedRequester), verticalArrangement = Arrangement.spacedBy(12.dp)) { LocalSpeedLimits() }
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(restoreRequester), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LocalSnapshotRestorePreviewPanel(
            transfers = transfers,
            inventoryConfirmed = hasConfirmedLocalInventory,
            inventoryStale = inventoryStale,
            busyEngine = busy || sourceReading,
            mutationGeneration = LocalScreenReader.revision.snapshot(),
            onPrepareAdd = { originalBytes ->
                check(!busy && sourceReadJob == null) { "Another source review is in progress" }
                initialTorrentUri?.let(onTorrentConsumed)
                // This callback changes only the local Add DRAFT, not the
                // engine, destination folder, existing transfers or data.
                try {
                    val bytes = originalBytes.copyOf()
                    pending = PendingLocalAdd(TorrentMetainfoReader.preview(bytes), bytes)
                    addStorage = null
                    message = "Original snapshot .torrent is ready for manual phone Add review. " +
                        "Check the destination and Start choice before confirming; no Add occurred."
                    if (onOpenAdd != null) onOpenAdd.invoke()
                    else scope.launch { scrollState.animateScrollTo(0) }
                } catch (_: Exception) {
                    noteProblem("Snapshot definition could not populate Add review." +
                        if (pending != null) " Your previous .torrent remains selected." else "")
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
                            val exactArchive = SnapshotManifestV1.encode(
                                verified.manifest, verified.metainfoByHash)
                            LocalSnapshotStoppedRestoreDraft(
                                original,
                                LocalSnapshotRestoreFingerprint.of(exactArchive),
                                frozen.toList(), generation)
                        }
                        if (sourceReadJob !== read) return@launch
                        if (request == snapshotPreparationRevision &&
                            LocalScreenReader.revision.snapshot() == generation &&
                            !LocalScreenReader.revision.blocksMutations()) {
                            snapshotRestoreDraft = draft
                            pending = null
                            message = "Review the new phone destination and explicitly confirm " +
                                "the stopped restore. No torrent has been added."
                            scrollState.animateScrollTo(0)
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
        if (onCreateTorrent != null) OutlinedButton(onClick = onCreateTorrent,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(createFocus)) {
            Text("Create a torrent…")
        }
        if (onZipReview != null) OutlinedButton(onClick = onZipReview,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(zipFocus)) {
            Text("Review ZIP archives…")
        }
        OutlinedButton(
            onClick = { showPhoneTools = !showPhoneTools },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = if (showPhoneTools) "Expanded" else "Collapsed" }
        ) { Text(if (showPhoneTools) "Hide phone tools" else "More phone tools") }
        if (showPhoneTools) {
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(exportsRequester), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LocalInventorySnapshotPanel(
            transfers = transfers,
            stale = inventoryStale,
            busyEngine = busy
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
            sourceLabel = "phone"
        )
        } // exports anchor
        }
        Column(Modifier.fillMaxWidth().bringIntoViewRequester(namesRequester), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CollectionNameManager(
            scope = TransferCollectionStore.LOCAL_ENGINE_SCOPE,
            registry = nameRegistry,
            registeredNames = registeredNames,
            onNamesChanged = { registeredNames = it }
        )
        } // names anchor
        LocalZipInterruptedExtractions()
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
        // Standalone phone tests retain their footer; ChunksApp owns the global one.
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

/** Latest phone action outcome, announced to accessibility services once per change. */
@Composable
internal fun PhoneOutcomeNotice(
    text: String,
    /** Refused, uncertain or failed: shown with a text label and warning edge, not colour alone. */
    needsAttention: Boolean = false,
    /** Optional view-only follow-up, e.g. showing the exact newly added transfer. */
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    // Fully qualified to keep the shared import block untouched.
    androidx.compose.material3.Surface(
        color = ChunksColors.SurfaceRaised,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        border = if (needsAttention) androidx.compose.foundation.BorderStroke(2.dp, ChunksColors.Yellow)
            else androidx.compose.foundation.BorderStroke(1.dp, ChunksColors.Outline),
        // Merge the text into the live-region node so its change is announced.
        modifier = Modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // The label carries the tone in words (and is spoken first), so it
            // never depends on the warning colour alone.
            if (needsAttention) Text("Needs attention", color = ChunksColors.Yellow,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(text, color = ChunksColors.Text, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null) OutlinedButton(onClick = onAction,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(actionLabel) }
        }
    }
}

/** This entry point is read-only until an existing Add operation is explicitly submitted. */
@Composable
internal fun PhoneIntakeDisclosure(
    expanded: Boolean,
    pendingReview: Boolean,
    placeLabel: String?,
    onToggle: () -> Unit
) {
    OutlinedButton(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
    ) {
        Text(when {
            expanded -> "Hide phone Add options"
            pendingReview -> "Review torrent on this phone"
            else -> "Add torrent on this phone"
        })
    }
    if (!expanded) {
        Text(
            if (placeLabel != null) "Save to: $placeLabel · Change in Add"
            else "Download location unverified · Review in Add",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
internal fun LocalAddReview(
    preview: TorrentMetainfoPreview,
    destination: File,
    placeTitle: String? = null,
    busy: Boolean,
    startAfterAdd: Boolean = false,
    storagePreflight: LocalAddStoragePreflight? = null,
    /** What Smart Seeding will apply on this phone; null when no rule store is readable. */
    seeding: io.github.gamesbyian.chunks.seeding.SeedingResolution? = null,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    cancelEnabled: Boolean = true
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Review download", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            Text(preview.name, fontWeight = FontWeight.Bold)
            Text(preview.fileCount.toString() + (if (preview.fileCount == 1) " file, " else " files, ") +
                Units.bytes(preview.sizeBytes))
            Text(if (placeTitle == null)
                "Saves into: Chunks' folder on phone storage (" + destination.absolutePath + "). Files here are removed if Chunks is uninstalled."
            else "Saves into: " + placeTitle + " (" + destination.absolutePath + ").")
            seeding?.let { s ->
                Text("Seeding: " + when (s.source) {
                    io.github.gamesbyian.chunks.seeding.SeedingResolution.Source.RULE -> "rule"
                    else -> "default for this phone"
                } + " · " + s.policy.describe(), style = MaterialTheme.typography.bodyMedium)
                s.reasons.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (s.conflicts.isNotEmpty()) Text("Other matching rules not used: " + s.conflicts.joinToString(),
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("Storage preflight (advisory; no automatic blocking or deletion)",
                style = MaterialTheme.typography.titleSmall)
            val space = storagePreflight
            if (space == null) Text("Checking the chosen download volume and folder…",
                style = MaterialTheme.typography.bodySmall)
            else {
                val cap = space.folderCapBytes
                if (cap != null) {
                    val projected = space.projectedLogicalMaximum
                    if (projected == null) Text("Saved folder limit: " + Units.bytes(cap) +
                        ". Folder inventory is incomplete; a total cannot be projected.",
                        style = MaterialTheme.typography.bodySmall)
                    else Text("Saved folder advisory limit: " + Units.bytes(cap) +
                        ". Up to " + Units.bytes(projected) +
                        " logical bytes if all torrent bytes are additional.",
                        style = MaterialTheme.typography.bodySmall)
                    if (space.projectedCapExceeded == true)
                        Text("Potentially above your advisory limit. " +
                            "Check the phone storage panel before adding.",
                            color = MaterialTheme.colorScheme.error)
                }
                val free = space.availableVolumeBytes
                if (free == null)
                    Text("Available space on this download volume could not be measured.",
                        style = MaterialTheme.typography.bodySmall)
                else {
                    Text("Currently available on this download volume: " + Units.bytes(free),
                        style = MaterialTheme.typography.bodySmall)
                    if (space.additionalBytesExceedFree == true)
                        Text("The torrent's full declared size exceeds currently available " +
                            "space if all bytes must be written anew. " +
                            "Choose files carefully or free space before starting.",
                            color = MaterialTheme.colorScheme.error)
                }
                Text("This is a worst-case estimate, not a disk reservation. Existing " +
                    "files, sparse allocation, other apps and later file selections " +
                    "can change actual storage needs.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(if (startAfterAdd)
                "Chunks adds stopped, verifies the new transfer, then starts it. Download progress depends on available peers or web seeds."
                else "It is added stopped. Nothing downloads until you press Start.",
                style = MaterialTheme.typography.bodySmall)
            if (preview.trackerCount == 0) Text("This torrent lists no tracker, so peers can only be found through DHT (if turned on) or its web sources.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onConfirm, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (startAfterAdd) "Add and start downloading" else "Add stopped")
            }
            OutlinedButton(onClick = onCancel, enabled = cancelEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
        }
    }
}

private fun stateLabel(transfer: LocalTransferStatus): String = when {
    transfer.error != null -> "Error"
    transfer.stopped -> "Stopped"
    transfer.state == LocalTransferState.CHECKING -> "Checking files"
    !transfer.metadataKnown || transfer.state == LocalTransferState.DOWNLOADING_METADATA -> "Fetching metadata"
    transfer.state == LocalTransferState.DOWNLOADING -> "Downloading"
    transfer.state == LocalTransferState.SEEDING || transfer.state == LocalTransferState.FINISHED -> "Sharing"
    else -> "Unknown"
}

/** Colour family for the state above; always shown next to its label. */
private fun stateTone(transfer: LocalTransferStatus): ChunksTone = when {
    transfer.error != null -> ChunksTone.ERROR
    transfer.stopped -> ChunksTone.PAUSED
    transfer.state == LocalTransferState.SEEDING || transfer.state == LocalTransferState.FINISHED -> ChunksTone.COMPLETE
    transfer.state == LocalTransferState.UNKNOWN -> ChunksTone.INACTIVE
    else -> ChunksTone.ACTIVE
}

@Composable
internal fun LocalTransferCard(
    transfer: LocalTransferStatus,
    busy: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onRemoveData: (() -> Unit)? = null,
    files: List<LocalTransferFile>? = null,
    filesShown: Boolean = false,
    onShowFiles: () -> Unit = {},
    onFileWanted: (Int, Boolean) -> Unit = { _, _ -> },
    onBatchFileWanted: ((List<LocalTransferFile>, Boolean) -> Unit)? = null,
    onFilePriority: ((LocalTransferFile, Int) -> Unit)? = null,
    onFocusEpisodes: ((LocalEpisodeFocusPlanner.Plan) -> Unit)? = null,
    finishInOrder: (@Composable (List<LocalTransferFile>) -> Unit)? = null,
    alias: String? = null,
    onSaveAlias: ((String?) -> Boolean)? = null,
    onPrepareRemote: (() -> Unit)? = null,
    move: (@Composable () -> Unit)? = null,
    onRepairTrackers: (() -> Unit)? = null,
    observedGrowth: LocalProgressHistory.ObservedDelta? = null,
    /** The Smart Seeding snapshot the phone controller stored; null before it has seen this transfer. */
    seeding: io.github.gamesbyian.chunks.seeding.SeedingController.Assignment? = null,
    /** The engine status read failed; rates are from the last confirmed read. */
    stale: Boolean = false,
    /** The screen's DHT setting; never read from preferences during composition. */
    dhtEnabled: Boolean = false,
    /** Similar Transfers in this card's details; null hides it. */
    similar: (@Composable () -> Unit)? = null
) {
    // An open confirmation belongs to the identity the user reviewed, not
    // just a hash that may now describe a moved or changed transfer. A stale
    // inventory or another pending action also requires a fresh review.
    var confirmRemove by remember(transfer.infoHash, transfer.name, transfer.savePath,
        transfer.nativeReviewToken, busy) { mutableStateOf(false) }
    var confirmHandoff by remember(transfer.infoHash) { mutableStateOf(false) }
    // Read-only disclosure remains bound to the exact job and Save to location.
    var diagnosticsOpen by rememberSaveable(transfer.infoHash, transfer.name, transfer.savePath) {
        mutableStateOf(false)
    }
    val percent = transfer.progressPpm / 10_000
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransferRowArtwork(rememberPhoneTransferKey(transfer.infoHash), transfer.name)
                Text(alias ?: transfer.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    // Keep phone cards as scannable as the already bounded remote
                    // and Combined rows. Expanded details reveal the full title.
                    // TalkBack always gets the entire exact visible display name.
                    maxLines = if (diagnosticsOpen) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = alias ?: transfer.name
                    })
            }
            if (diagnosticsOpen) {
                if (alias != null) {
                    Text("Original torrent: " + transfer.name,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (onSaveAlias != null) {
                    TransferAliasEditor(
                        originalName = transfer.name, hash = transfer.infoHash,
                        alias = alias, onSave = onSaveAlias
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                StatusPill(stateLabel(transfer), stateTone(transfer))
                Text("$percent% · " + transfer.peers + " peers", fontWeight = FontWeight.Bold)
            }
            transfer.error?.let { problem ->
                Text(problem, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (!transfer.metadataKnown && transfer.error == null && !transfer.stopped)
                Text("No file data is downloaded until the torrent's description arrives.",
                    style = MaterialTheme.typography.bodySmall)
            if (!transfer.stopped) {
                // The libtorrent status sample is an instantaneous estimate;
                // unlike the independent observed-growth window below, it
                // must not be presented as verified bytes or a lasting trend.
                Text((if (stale) "Last read payload ↓ " else "Live payload ↓ ") +
                    Units.bytes(transfer.downloadPayloadRateBytesPerSecond.toLong()) + "/s · ↑ " +
                    Units.bytes(transfer.uploadPayloadRateBytesPerSecond.toLong()) + "/s" +
                    (Units.timeLeft(transfer.wantedBytes - transfer.doneBytes,
                        transfer.downloadPayloadRateBytesPerSecond.toLong())
                        ?.takeIf { transfer.metadataKnown && !stale }?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall)
            }
            if (diagnosticsOpen) {
                LocalTrackerSummaryText.describe(transfer.trackers, transfer.stopped, dhtEnabled)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                seeding?.let { SeedingSnapshotSummary(transfer.infoHash, it) }
            }
            if (transfer.metadataKnown) ChunkedProgressBar(
                progress = transfer.progressPpm / 1_000_000f,
                tone = stateTone(transfer),
                historyKey = ProgressChunkStore.identity("phone", transfer.infoHash),
                observeFresh = !busy,
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = "$percent percent complete" }
            ) else Text("Progress unknown until torrent metadata arrives",
                style = MaterialTheme.typography.bodySmall)
            // totalWantedDone is hash-verified completed payload; allTimeDownload
            // is a libtorrent network counter and may exclude HTTPS web seeds.
            val verifiedText = "Verified on disk " + Units.bytes(transfer.doneBytes) + " of " +
                Units.bytes(transfer.wantedBytes) + " · Shared " +
                Units.bytes(transfer.payloadUploadedBytes)
            val diagnosticsModifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = if (diagnosticsOpen)
                    "Hide diagnostics for ${transfer.name} on this phone"
                else "More details for ${transfer.name} on this phone"
                stateDescription = if (diagnosticsOpen) "Expanded" else "Collapsed"
            }
            val diagnosticsLabel = if (diagnosticsOpen) "Hide details" else "More details"
            // Thin collapsed cards: at ordinary text sizes the disclosure shares the
            // verified-bytes line instead of a full-width row on every card.
            if (LocalDensity.current.fontScale < 1.5f) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(verifiedText, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(
                        onClick = { diagnosticsOpen = !diagnosticsOpen },
                        modifier = diagnosticsModifier) { Text(diagnosticsLabel) }
                }
            } else {
                Text(verifiedText, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { diagnosticsOpen = !diagnosticsOpen },
                    modifier = diagnosticsModifier.fillMaxWidth()
                ) { Text(diagnosticsLabel) }
            }
            if (diagnosticsOpen) {
            // Presentation only, keyed by the exact phone job; never reads or changes payload files.
            rememberPhoneTransferKey(transfer.infoHash)?.let { key ->
                // Files are listed only after the person opens them; until then no local evidence.
                val localMedia = remember(transfer.savePath, files) {
                    files?.let { LocalCompletedMediaFiles(transfer.savePath, it) }
                }
                TransferMediaDetailSection(key, transfer.name, localFiles = localMedia)
            }
            similar?.invoke()
            if (transfer.payloadDownloadedBytes > 0L) {
                Text("Network payload counter " + Units.bytes(transfer.payloadDownloadedBytes),
                    style = MaterialTheme.typography.bodySmall)
            }
            // Derived only from two or more successful native status reads,
            // never from the Started flag, an RPC acknowledgement or a stale UI.
            observedGrowth?.let { sample ->
                Text("Observed over ${sample.seconds}s: +" +
                    Units.bytes(sample.verifiedBytes) + " hash-verified on disk · +" +
                    Units.bytes(sample.peerPayloadBytes) + " peer-network payload · +" +
                    Units.bytes(sample.uploadedBytes) + " uploaded",
                    style = MaterialTheme.typography.bodySmall)
                if (!transfer.stopped && sample.verifiedBytes == 0L &&
                    sample.peerPayloadBytes == 0L && transfer.progressPpm < 1_000_000) {
                    Text("No verified or peer-network byte growth during this sample. " +
                        "That is not proof of a permanent stall; check live peers, tracker " +
                        "availability, DHT and storage. Webseed data may be absent from " +
                        "the peer-network counter.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!transfer.stopped && transfer.uploadOnly && transfer.metadataKnown) {
                Text("Running in upload-only mode: libtorrent is not requesting file pieces. " +
                    "This can follow a disk write error; inspect the error above and available storage.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else if (!transfer.stopped && transfer.peers == 0 &&
                transfer.progressPpm < 1_000_000) {
                Text(if (!transfer.metadataKnown)
                    "No peers yet. Magnet metadata cannot arrive until a tracker or DHT supplies peers."
                    else "Running, but 0 peers connected. Check trackers, DHT, VPN/firewall and live seed availability. Starting alone cannot produce bytes.",
                    style = MaterialTheme.typography.bodySmall)
            }
            } // Diagnostics are presentation-only; primary actions stay exposed.
            // Three tiles side by side split words at 200% text; stack them instead.
            val wideText = LocalDensity.current.fontScale >= 1.5f
            val tile: Modifier = if (wideText) Modifier.fillMaxWidth() else Modifier
            val actions: @Composable (Modifier) -> Unit = { each ->
                ActionTile(if (transfer.stopped) "Start" else "Stop",
                    if (transfer.stopped) ChunksColors.Green else ChunksColors.Purple,
                    onClick = onToggle, enabled = !busy,
                    modifier = each.semantics {
                        contentDescription = (if (transfer.stopped) "Start " else "Stop ") +
                            transfer.name + " on this phone"
                    },
                    icon = if (transfer.stopped) R.drawable.chunks_ic_play else R.drawable.chunks_ic_stop,
                    stacked = !wideText)
                ActionTile("Remove…", ChunksColors.Orange, onClick = { confirmRemove = true },
                    enabled = !busy && !confirmRemove,
                    modifier = each.semantics {
                        contentDescription = "Remove transfer " + transfer.name + " from this phone"
                    },
                    icon = R.drawable.chunks_ic_remove, stacked = !wideText)
                if (transfer.metadataKnown) ActionTile(if (filesShown) "Hide files" else "Files",
                    ChunksColors.Cyan, onClick = onShowFiles, enabled = !busy,
                    modifier = each.semantics {
                        contentDescription = (if (filesShown) "Hide files for " else "Show files for ") +
                            transfer.name + " on this phone"
                    },
                    icon = R.drawable.chunks_ic_folder, stacked = !wideText)
            }
            if (wideText) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { actions(tile) }
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                actions(Modifier.weight(1f))
            }
            if (diagnosticsOpen && onRepairTrackers != null && transfer.stopped &&
                transfer.metadataKnown && transfer.trackers?.total == 0) {
                OutlinedButton(
                    onClick = onRepairTrackers,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("Repair missing trackers from original .torrent…") }
            }
            if (diagnosticsOpen && onPrepareRemote != null &&
                TorrentIdentityHandoff.prepare(transfer.infoHash, transfer.name) != null) {
                OutlinedButton(onClick = { confirmHandoff = true }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Copy Transfer to server…")
                }
                if (confirmHandoff) {
                    Text("Chunks opens your rTorrent server's Add review with this torrent's " +
                        ".torrent definition, including its tracker addresses. For private " +
                        "trackers these can contain your personal passkey; they are sent only " +
                        "to the server you are signed in to, and nothing is added until you " +
                        "confirm there. If the definition is unavailable, only the magnet " +
                        "identity is used, which may not find peers. No payload bytes move; " +
                        "the server downloads from peers itself. The phone transfer is untouched.",
                        style = MaterialTheme.typography.bodySmall)
                    LocalTransferConfirmationActions(
                        confirmLabel = "Review server Add",
                        cancelLabel = "Cancel",
                        enabled = !busy,
                        onConfirm = { confirmHandoff = false; onPrepareRemote() },
                        onCancel = { confirmHandoff = false }
                    )
                }
            }
            move?.invoke()
            if (filesShown) LocalFileSelection(
                files, busy, transferKey = transfer.infoHash,
                onFileWanted = onFileWanted, onBatchFileWanted = onBatchFileWanted,
                onFilePriority = onFilePriority,
                onFocusEpisodes = onFocusEpisodes,
                finishInOrder = finishInOrder
            )
            if (filesShown && files != null) LocalFilesOnDiskCheck(transfer.infoHash, transfer.savePath, files)
            // On-demand container/track facts for the main completed media file; nothing is read
            // until "Inspect media" is tapped, and only through the verified completed-file gate.
            if (filesShown && files != null) {
                val inspectable = remember(transfer.savePath, files) { LocalCompletedMediaFiles(transfer.savePath, files) }
                inspectable.primaryInspectable?.let { hint ->
                    io.github.gamesbyian.chunks.media.ui.MediaTechnicalDetails(
                        fileName = hint.path.substringAfterLast('/'), open = { inspectable.open(hint) })
                }
            }
            if (confirmRemove) {
                Text("Remove from this phone: " + transfer.name,
                    style = MaterialTheme.typography.titleSmall)
                Text("Infohash: " + transfer.infoHash, style = MaterialTheme.typography.bodySmall)
                Text("Current folder: " + transfer.savePath, style = MaterialTheme.typography.bodySmall)
                Text("Choose what to remove from this phone. Keeping data stops this torrent in Chunks, but leaves downloaded files in their current folder.")
                LocalTransferConfirmationActions(
                    confirmLabel = "Remove transfer, keep data",
                    cancelLabel = "Cancel",
                    enabled = !busy,
                    onConfirm = { confirmRemove = false; onRemove() },
                    onCancel = { confirmRemove = false }
                )
                OutlinedButton(
                    onClick = {
                        if (onRemoveData != null) {
                            confirmRemove = false
                            onRemoveData()
                        }
                    },
                    enabled = !busy && onRemoveData != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text(if (onRemoveData == null) "Remove transfer and data (unavailable)"
                    else "Remove transfer and data") }
                Text(if (onRemoveData == null)
                    "Delete-data is unavailable for this transfer while its file mapping or inventory cannot be verified. Keeping data remains available."
                    else "This removes the transfer and deletes its exact torrent files in this folder, including matching files that were already there before Add. Unrelated files are kept. This cannot be undone. The result is independently checked; an interrupted or partial cleanup will not be described as successful.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Per-file download selection, bounded to one page even for huge multi-file torrents.
 * Unticking never deletes bytes already written. Page identity is scoped to the
 * transfer, not to a Compose list position or a periodically refreshed file list.
 */
@Composable
internal fun LocalFileSelection(
    files: List<LocalTransferFile>?,
    busy: Boolean,
    transferKey: String? = null,
    onBatchFileWanted: ((List<LocalTransferFile>, Boolean) -> Unit)? = null,
    onFilePriority: ((LocalTransferFile, Int) -> Unit)? = null,
    onFocusEpisodes: ((LocalEpisodeFocusPlanner.Plan) -> Unit)? = null,
    finishInOrder: (@Composable (List<LocalTransferFile>) -> Unit)? = null,
    onFileWanted: (Int, Boolean) -> Unit
) {
    if (files == null) {
        Text("Loading files…", style = MaterialTheme.typography.bodySmall)
        return
    }
    val pageSize = 40
    var fileSearch by rememberSaveable(transferKey) { mutableStateOf("") }
    var requestedPage by remember(transferKey, files.size) { mutableStateOf(0) }
    // Keep the native file index on every matching row; never renumber a priority target.
    val matchedIndices = remember(files, fileSearch) {
        matchingFileIndices(files.map { it.path }, fileSearch)
    }
    val pageCount = ((matchedIndices.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    val page = requestedPage.coerceIn(0, pageCount - 1)
    val pageFiles = matchedIndices.drop(page * pageSize).take(pageSize).map { files[it] }
    // Native download progress can change every second. Do not dismiss a
    // user's confirmation merely because doneBytes advances. Only a change
    // to file identity, total size or wanted selection invalidates review.
    val reviewFingerprint = remember(files) {
        files.map { file ->
            listOf(file.index.toString(), file.path, file.sizeBytes.toString(),
                file.wanted.toString(), file.priority.toString())
        }
    }
    // Key pending confirmation to the exact review identity instead of
    // clearing it from an asynchronous LaunchedEffect, which could race a
    // newly clicked Select/Skip button on the first Compose frame.
    var pendingBatch by remember(transferKey, fileSearch, page, reviewFingerprint) {
        mutableStateOf<Boolean?>(null)
    }
    var priorityEditorIndex by remember(transferKey, fileSearch, page, reviewFingerprint) {
        mutableStateOf<Int?>(null)
    }
    var pendingEpisodeFocus by remember(transferKey, reviewFingerprint) {
        mutableStateOf(false)
    }
    val focusPlan = remember(files) { LocalEpisodeFocusPlanner.plan(files) }

    Text("Untick a file to skip downloading it. Already downloaded parts are kept.",
        style = MaterialTheme.typography.bodySmall)
    if (files.isNotEmpty()) {
        ClearableTransferSearchField(
            value = fileSearch,
            onValueChange = { fileSearch = it; requestedPage = 0 },
            label = "Search this transfer's files"
        )
        Text("Showing ${matchedIndices.size} of ${files.size} file paths" +
            if (fileSearch.isNotBlank()) " · filtered" else "",
            style = MaterialTheme.typography.bodySmall)
        if (matchedIndices.isEmpty()) Text(
            "No files match this search. Clear it to see the full list.",
            style = MaterialTheme.typography.bodySmall)
    }
    // Ordered completion always reads the complete exact transfer, not the visible search subset.
    finishInOrder?.invoke(files)
    if (onFocusEpisodes != null && focusPlan != null) {
        OutlinedButton(onClick = { pendingEpisodeFocus = !pendingEpisodeFocus },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Focus first unfinished episode (one time)")
        }
        if (pendingEpisodeFocus) {
            Text("Detected first episode: " +
                focusPlan.firstEpisode.path.substringAfterLast('/') +
                ". This one-time change gives it High native priority, keeps " +
                "the following episode at Normal, and assigns later episodes " +
                "Low priority. ${focusPlan.changes.size} existing file priorities " +
                "will change, including manual settings. No files are skipped, " +
                "deleted or downloaded automatically. This does not guarantee " +
                "completion order or advance automatically to the next episode.",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    pendingEpisodeFocus = false
                    onFocusEpisodes(focusPlan)
                }, enabled = !busy && focusPlan.changes.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Confirm episode focus")
                }
                OutlinedButton(onClick = { pendingEpisodeFocus = false },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Cancel")
                }
            }
        }
    }

    if (pageCount > 1) {
        Text("Files page " + (page + 1) + " of " + pageCount + " (" +
            matchedIndices.size + " matching files)",
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { requestedPage = page - 1 }, enabled = !busy && page > 0,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Previous page") }
            OutlinedButton(onClick = { requestedPage = page + 1 },
                enabled = !busy && page + 1 < pageCount,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Next page") }
        }
    }
    if (onBatchFileWanted != null && pageFiles.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pendingBatch = true }, enabled = !busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("Select page")
            }
            OutlinedButton(onClick = { pendingBatch = false }, enabled = !busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("Skip page")
            }
        }
        pendingBatch?.let { want ->
            val changes = pageFiles.count { it.wanted != want }
            Text(if (changes == 0) "All ${pageFiles.size} files on this page already " +
                    (if (want) "are selected." else "are skipped.")
                else "Review: " + (if (want) "select " else "skip ") +
                    "${changes} of ${pageFiles.size} visible matching files on this page. " +
                    "Already downloaded bytes are never deleted. Changing wanted pieces " +
                    "may stop or resume downloading; other pages are untouched.",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val reviewed = pageFiles.toList()
                    pendingBatch = null
                    onBatchFileWanted(reviewed, want)
                }, enabled = !busy && changes > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(if (want) "Confirm select page" else "Confirm skip page")
                }
                OutlinedButton(onClick = { pendingBatch = null },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Cancel")
                }
            }
        }
    }
    pageFiles.forEach { file ->
        // Multiplying done bytes by 100 can overflow Long for large files.
        val percent = if (file.sizeBytes <= 0L) 100 else
            (file.doneBytes.toDouble() / file.sizeBytes * 100).toInt().coerceIn(0, 100)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 48.dp)
                .testTag("local-file-toggle-${file.index}")
                .toggleable(value = file.wanted, enabled = !busy, role = Role.Checkbox,
                    onValueChange = { onFileWanted(file.index, it) }),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = file.wanted, onCheckedChange = null)
                Image(painterResource(fileTypeIcon(file.path)), contentDescription = null,
                    modifier = Modifier.padding(start = 4.dp).size(28.dp))
                Column(Modifier.padding(start = 8.dp).weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(file.path.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(Units.bytes(file.sizeBytes) + " · " + percent + "%" +
                        if (file.wanted) "" else " · skipped",
                        style = MaterialTheme.typography.bodySmall)
                    FileProgressLine(if (file.sizeBytes <= 0L) 1f else file.doneBytes.toFloat() / file.sizeBytes,
                        file.wanted, Modifier.fillMaxWidth())
                }
            }
            if (file.wanted && onFilePriority != null) {
                val priorityName = when (file.priority) {
                    1 -> "Low"
                    4 -> "Normal"
                    7 -> "High"
                    else -> "Custom"
                }
                OutlinedButton(
                    onClick = { priorityEditorIndex =
                        if (priorityEditorIndex == file.index) null else file.index },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Priority: " + priorityName) }
            }
        }
        if (file.wanted && priorityEditorIndex == file.index &&
            onFilePriority != null) {
            Text("Change download priority for " + file.path.substringAfterLast('/') +
                ". This affects piece scheduling, not the files already downloaded.",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "Low", 4 to "Normal", 7 to "High").forEach { (value, label) ->
                    OutlinedButton(
                        onClick = {
                            priorityEditorIndex = null
                            onFilePriority(file, value)
                        },
                        enabled = !busy && file.priority != value,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text(label) }
                }
            }
        }
    }
}

/** KiB/s text to bytes per second; blank or 0 is unlimited, anything else invalid is null. */
internal fun parseSpeedLimit(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0
    val kib = trimmed.toIntOrNull() ?: return null
    return if (kib in 0..(Int.MAX_VALUE / 1024)) kib * 1024 else null
}

/** Current saved value and where it came from, in one sentence. */
internal fun speedLimitSummary(limits: LocalRateLimits): String {
    fun one(bytes: Int) = if (bytes == 0) "unlimited" else "${bytes / 1024} KiB/s"
    return if (limits == LocalRateLimits())
        "Now: unlimited download and upload (default)."
    else "Now: download ${one(limits.downloadBytesPerSecond)}, upload " +
        "${one(limits.uploadBytesPerSecond)} (your saved limit)."
}

private fun speedText(bytesPerSecond: Int) = if (bytesPerSecond == 0) "" else (bytesPerSecond / 1024).toString()

@Composable
internal fun LocalSpeedLimits() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(LocalEngineHolder.rateLimitsPreferred(context)) }
    var download by remember { mutableStateOf(speedText(saved.downloadBytesPerSecond)) }
    var upload by remember { mutableStateOf(speedText(saved.uploadBytesPerSecond)) }
    var result by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val downloadLimit = parseSpeedLimit(download)
    val uploadLimit = parseSpeedLimit(upload)
    Text("Speed limits", fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
    Text(speedLimitSummary(saved), style = MaterialTheme.typography.bodyMedium)
    SettingScopeNote(PHONE_ENVIRONMENT_LABEL)
    Text("In KiB per second; leave blank for unlimited. Devices on your own network are not limited.",
        style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = download, onValueChange = { download = it; result = null },
            label = { Text("Download") }, singleLine = true, isError = downloadLimit == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f))
        OutlinedTextField(value = upload, onValueChange = { upload = it; result = null },
            label = { Text("Upload") }, singleLine = true, isError = uploadLimit == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f))
    }
    OutlinedButton(onClick = {
        if (!saving) {
            val wanted = LocalRateLimits(
                downloadLimit ?: return@OutlinedButton,
                uploadLimit ?: return@OutlinedButton
            )
            saving = true
            result = null
            scope.launch {
                try {
                    val confirmed = withContext(Dispatchers.IO) {
                        LocalEngineHolder.setRateLimitsPreferred(context, wanted)
                    }
                    if (confirmed) saved = wanted
                    result = if (confirmed)
                        "Speed limits verified (if the engine is running) and saved on this phone."
                    else
                        "Speed limits were not saved. The engine did not confirm them, or local storage could not commit. Live limits may be uncertain."
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    result = "Speed limits were not confirmed or saved. The running engine may have changed; check its state before trying again."
                } finally {
                    saving = false
                }
            }
        }
    }, enabled = !saving && downloadLimit != null && uploadLimit != null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(if (saving) "Checking speed limits…" else "Save speed limits")
    }
    // Contextual reset beside the value it changes; it follows the same
    // engine-confirmed save path, so an unconfirmed reset is reported as such.
    if (saved != LocalRateLimits()) OutlinedButton(onClick = {
        if (!saving) {
            saving = true
            result = null
            scope.launch {
                try {
                    val confirmed = withContext(Dispatchers.IO) {
                        LocalEngineHolder.setRateLimitsPreferred(context, LocalRateLimits())
                    }
                    if (confirmed) {
                        saved = LocalRateLimits()
                        download = ""
                        upload = ""
                    }
                    result = if (confirmed) "Speed limits reset to unlimited on this phone."
                    else "Speed limits were not reset. The engine did not confirm, or local storage could not commit. Live limits may be uncertain."
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    result = "Speed limits were not confirmed or reset. Check the engine state before trying again."
                } finally {
                    saving = false
                }
            }
        }
    }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Reset to unlimited (default)")
    }
    result?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}

/**
 * Add-review Finish in Order choice plus the opt-in default for later Adds.
 * The default only pre-ticks future Add reviews on this phone; it never
 * changes a transfer that already exists.
 */
@Composable
internal fun FinishInOrderAddChoice(
    label: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit,
    defaultOn: Boolean, onDefault: (Boolean) -> Unit
) = Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChecked),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
    if (checked || defaultOn) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 32.dp)
        .toggleable(value = defaultOn, enabled = enabled, role = Role.Checkbox, onValueChange = onDefault),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = defaultOn, enabled = enabled, onCheckedChange = null)
        Text("Tick this for new multi-file Adds on this phone. Existing transfers are not changed.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * Create a Finish in Order policy from the suggested order of a just-added,
 * still-stopped phone transfer and apply its first verified step. Returns a
 * user-facing note; never starts the transfer or changes skipped files.
 */
internal suspend fun enableFinishInOrderAfterAdd(
    context: Context, engine: io.github.gamesbyian.chunks.engine.LocalTorrentEngine, infoHash: String
): String {
    val files = engine.files(infoHash) ?: return "Finish in Order was not set up: the file list is not readable yet."
    val suggestion = io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.suggest(files)
        ?: return "Finish in Order was not set up: no episode or numbered order was found. You can arrange one from Files."
    val store = FinishInOrderStore(context)
    val policy = io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.policyFor(
        io.github.gamesbyian.chunks.engine.FinishInOrderPlanner.Mode.BALANCED, files, suggestion.order)
    if (!FinishInOrderController.exclusive {
            store.save(infoHash, io.github.gamesbyian.chunks.engine.FinishInOrderRecord(policy))
        }) return "Finish in Order could not be saved; priorities are unchanged."
    val step = FinishInOrderController.exclusive {
        store.get(infoHash)?.let { FinishInOrderController.runner(engine, store).step(infoHash, it) }
    }
    return when (step) {
        is io.github.gamesbyian.chunks.engine.FinishInOrderRunner.Step.Applied,
        is io.github.gamesbyian.chunks.engine.FinishInOrderRunner.Step.Idle ->
            "Finish in Order is on for ${suggestion.order.size} files (Balanced)."
        is io.github.gamesbyian.chunks.engine.FinishInOrderRunner.Step.Suspended ->
            "Finish in Order is paused: " + step.record.suspendedReason
        else -> "Finish in Order is saved and will apply once the engine reports this transfer."
    }
}

/**
 * What Smart Seeding will snapshot for this phone Add, resolved from the
 * reviewed metadata's tracker hosts. Null when the saved rules are unreadable.
 */
internal fun phoneSeedingPreview(context: Context, preview: io.github.gamesbyian.chunks.rpc.TorrentMetainfoPreview):
    io.github.gamesbyian.chunks.seeding.SeedingResolution? {
    val snapshot = io.github.gamesbyian.chunks.seeding.SeedingRuleStore(context).load()
    if (snapshot.unreadable) return null
    val env = io.github.gamesbyian.chunks.seeding.PhoneSeeding.ENVIRONMENT
    return io.github.gamesbyian.chunks.seeding.SeedingRuleEngine.resolve(
        io.github.gamesbyian.chunks.seeding.TransferFacts(env, preview.trackerHosts.map { "https://$it/" }, preview.isPrivate),
        snapshot.rules, snapshot.defaults[env] ?: io.github.gamesbyian.chunks.seeding.SeedingPolicy())
}

/**
 * Read-only: the policy snapshot Smart Seeding froze for this phone transfer,
 * and why. Nothing here changes the transfer.
 */
@Composable
internal fun SeedingSnapshotSummary(hash: String, assignment: io.github.gamesbyian.chunks.seeding.SeedingController.Assignment) {
    var why by remember(hash) { mutableStateOf(false) }
    val policy = assignment.policy
    if (policy == null) {
        Text("Seeding: not managed by rules (it was here before Smart Seeding started).",
            style = MaterialTheme.typography.bodySmall)
        return
    }
    Text("Seeding: " + (if (assignment.source == io.github.gamesbyian.chunks.seeding.SeedingResolution.Source.RULE)
        "rule" else "default for this phone") + " · " + policy.describe(), style = MaterialTheme.typography.bodySmall)
    assignment.stoppedByRule?.let {
        Text("Paused by this rule ($it). If you start it again, the rule will not pause it again.",
            style = MaterialTheme.typography.bodySmall)
    }
    assignment.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (assignment.reasons.isNotEmpty()) {
        TextButton(onClick = { why = !why }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (why) "Hide why" else "Why this rule?")
        }
        if (why) {
            assignment.reasons.forEach { Text("• " + it, style = MaterialTheme.typography.bodySmall) }
            Text("Chosen when this transfer was added. Later rule edits do not change it.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
