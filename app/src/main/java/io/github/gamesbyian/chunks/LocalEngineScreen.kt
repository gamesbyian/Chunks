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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.model.TransferMove
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEpisodeFocusPlanner
import io.github.gamesbyian.chunks.engine.LocalFileBatchOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalRateLimits
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
        val candidate = LocalTorrentEngine(
            LocalEngineConfig(stateDirectory = stateDirectory(context))
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
        ?: if (stateDirectory(context).listFiles().orEmpty().any { it.name.endsWith(".resume") }) get(context) else null

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

private class PendingLocalAdd(val preview: TorrentMetainfoPreview, val bytes: ByteArray)

@Composable
internal fun LocalEngineScreen(
    onBack: (() -> Unit)? = null,
    /** Opens Smart Seeding Rules; null hides the entry. */
    onSeedingRules: (() -> Unit)? = null,
    /** Opens the read-only All environments view; null hides the entry. */
    onCombined: (() -> Unit)? = null,
    initialMagnet: String? = null,
    initialTorrentUri: Uri? = null,
    /** A connected, independently privacy-gated rTorrent Add screen is available. */
    canReviewOnConnectedServer: Boolean = false,
    /** The signed-in rTorrent account for batch Copy Transfer to server; null when signed out. */
    serverCopy: LocalServerCopyLink? = null,
    /** The connected rTorrent account, for Move Transfer checks; null when signed out. */
    moveServer: LocalMoveServer? = null,
    onTorrentConsumed: () -> Unit = {},
    onPrefillConsumed: () -> Unit = {},
    /** Pre-fills the list search (for example an exact infohash from All environments). */
    initialSearch: String? = null,
    onSearchConsumed: () -> Unit = {},
    onPrepareRemote: ((String) -> Unit)? = null,
    /** Receives a verified exported .torrent for the server's Add review. */
    onPrepareRemoteTorrent: ((ByteArray) -> Unit)? = null
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
    val transfers = inventoryFrame.entries
    val inventoryStale = inventoryFrame.stale || LocalScreenReader.revision.blocksMutations()
    var transferPage by remember { mutableStateOf(0) }
    var transferSearch by remember { mutableStateOf("") }
    var transferFilter by remember { mutableStateOf(LocalQueueFilter.ALL) }
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
    val displayedTransfers = remember(transfers, transferSearch, transferFilter,
        transferOrder, transferDirection, aliasRevision) {
        LocalQueueCatalog.visible(transfers, transferSearch, transferFilter, transferOrder,
            transferDirection) {
            transfer -> aliasStore.get(TransferCollectionStore.LOCAL_ENGINE_SCOPE,
                transfer.infoHash) ?: transfer.name
        }
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
    var pending by remember { mutableStateOf<PendingLocalAdd?>(null) }
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
    var busy by remember { mutableStateOf(false) }
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
    var magnet by remember { mutableStateOf("") }
    LaunchedEffect(initialMagnet) {
        if (initialMagnet != null && initialMagnet.startsWith("magnet:?")) {
            magnet = initialMagnet
            onPrefillConsumed()
        }
    }
    var startAfterAdd by remember { mutableStateOf(true) }
    var addFolderDraft by remember { mutableStateOf("") }
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
    // Android 10: after the user answers, re-probe; a grant alone never selects
    // Downloads, the write probe and the remembered choice still decide.
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { placeCheck++ }
    LaunchedEffect(placeCheck) {
        placeResolution = withContext(Dispatchers.IO) {
            try { LocalDownloadPlaces.resolve(context) } catch (_: Exception) {
                LocalPlaceResolution(null, emptyList(),
                    "Chunks could not check phone download locations.")
            }
        }
    }
    val place = placeResolution?.selected
    val destination = place?.root
    val proposedAddDestination = remember(destination, addFolderDraft) {
        LocalOwnedAddFolder.proposed(destination, addFolderDraft)
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
                        if (frame.transfers != null) hasConfirmedLocalInventory = true
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
        message = null
        scope.launch {
            pending = withContext(Dispatchers.IO) {
                try {
                    val reviewed = SharedTorrentDocument.read(context.contentResolver, uri)
                    PendingLocalAdd(reviewed.preview, reviewed.bytes)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) { null }
            }
            if (pending == null) message =
                "This document is not a supported v1 .torrent file, was inaccessible or exceeds 2 MiB."
        }
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
                    message = "This .torrent has a different infohash. The existing phone transfer was untouched."
                } else if (reviewed.preview.trackerCount < 1) {
                    message = "That .torrent contains no usable tracker announce endpoints. Nothing changed."
                } else {
                    repairReview = selectedHash to reviewed
                    message = "Original identity verified. Review the tracker repair below before confirming."
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                message = "This .torrent was unavailable, invalid or unsupported. No tracker change occurred."
            } finally {
                repairSelectionBusy = false
            }
        }
    }

    LaunchedEffect(initialTorrentUri) {
        val externalUri = initialTorrentUri ?: return@LaunchedEffect
        // Never start the torrent from an external VIEW/SEND Intent. Only
        // prepare the same explicit Add review reached through the picker.
        pending = null
        message = "Opening shared .torrent for review. No download has started."
        try {
            val reviewed = withContext(Dispatchers.IO) {
                SharedTorrentDocument.read(context.contentResolver, externalUri)
            }
            pending = PendingLocalAdd(reviewed.preview, reviewed.bytes)
            message = "Shared torrent reviewed locally. Confirm its destination and Add below."
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            message = "The shared file was unavailable, invalid or not supported v1 .torrent metadata. No torrent was added."
        } finally {
            onTorrentConsumed()
        }
    }

    val scrollState = rememberScrollState()
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
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().verticalScroll(scrollState)
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (onBack != null) OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
        Text("On this phone (experimental)", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        EnvironmentBadge(PHONE_ENVIRONMENT_LABEL, server = false,
            environment = io.github.gamesbyian.chunks.model.EnvironmentSnapshots.PHONE)
        if (onCombined != null) OutlinedButton(onClick = onCombined, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("All environments…")
        }
        if (onSeedingRules != null) OutlinedButton(onClick = onSeedingRules, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Seeding rules…")
        }
        Text("Downloads and seeds with Chunks' own engine. While a transfer runs, an ongoing notification keeps it going in the background, with Stop all; Android may still stop it, and from Android 15 limits this kind of background transfer to about 6 hours a day, after which Chunks pauses everything. Nothing connects to the network until you add a torrent. Transfers are remembered and come back stopped after Chunks restarts. Peers and trackers can see this phone's IP address.")
        ActionTile("Choose .torrent file", ChunksColors.Green, onClick = { picker.launch(arrayOf("*/*")) },
            enabled = !busy && !inventoryStale && destination != null,
            modifier = Modifier.fillMaxWidth(), icon = R.drawable.chunks_ic_add_torrent, stacked = false)
        LocalDownloadPlacePicker(
            resolution = placeResolution,
            enabled = !busy && !inventoryStale,
            onChoose = { choice ->
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        try { LocalDownloadPlaces.remember(context, choice) } catch (_: Exception) { false }
                    }
                    if (saved) placeResolution = placeResolution?.copy(selected = choice.place, notice = null)
                    else message = "That download location could not be saved. The previous default is unchanged."
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
            // Only the typed name can be wrong; an unresolved download location is
            // reported separately ("Checking where this phone can save…").
            isError = addFolderNameInvalid,
            enabled = !busy && !inventoryStale,
            modifier = Modifier.fillMaxWidth()
        )
        Text("Optional: give this new local transfer its own subfolder inside the " +
            "download location above. Leave blank to save directly there. " +
            "An empty existing subfolder can be reused; a nonempty folder is " +
            "refused to prevent collisions. No folder is created until Add is confirmed, " +
            "and nothing is ever created on a remote server.",
            style = MaterialTheme.typography.bodySmall)
        if (addFolderNameInvalid)
            Text("Use one plain folder name, without slashes, dot-prefixed names, " +
                "control characters or path traversal.",
                color = MaterialTheme.colorScheme.error)
        else if (proposedAddDestination != null)
            Text("Planned local Place: " + proposedAddDestination.absolutePath,
                style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = dht, enabled = !dhtSaving, role = Role.Switch,
                onValueChange = ::requestDhtSetting), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Find peers without trackers (DHT)")
                Text("Needed for most magnet links. Off by default: when on, this phone's IP address is shared with the public DHT network.",
                    style = MaterialTheme.typography.bodySmall)
                SettingScopeNote(PHONE_ENVIRONMENT_LABEL)
                if (dhtSaving) Text("Applying DHT setting…", style = MaterialTheme.typography.bodySmall)
                dhtError?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error) }
            }
            Switch(checked = dht, enabled = !dhtSaving, onCheckedChange = null)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = startAfterAdd, enabled = !busy && !inventoryStale,
                role = Role.Checkbox, onValueChange = { startAfterAdd = it }),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = startAfterAdd, enabled = !busy && !inventoryStale, onCheckedChange = null)
            Text("Start downloading after Add (recommended)", modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedTextField(value = magnet, onValueChange = { magnet = it.trim() },
            label = { Text("Or paste a magnet link") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        var finishInOrderDefault by remember { mutableStateOf(finishInOrderStore.defaultForNewAdds()) }
        var magnetFinishInOrder by remember(magnet) { mutableStateOf(finishInOrderDefault) }
        if (magnet.isNotEmpty() && destination != null) {
            FinishInOrderAddChoice(
                "Finish in Order once the file list arrives (episodes or numbered files)",
                checked = magnetFinishInOrder, enabled = !busy && !inventoryStale,
                onChecked = { magnetFinishInOrder = it },
                defaultOn = finishInOrderDefault,
                onDefault = { on -> if (finishInOrderStore.setDefaultForNewAdds(on)) finishInOrderDefault = on }
            )
            Text("Saves into the download location shown above. Magnets first discover metadata via peers/trackers; if Start after Add is selected, payload downloading begins after metadata arrives. Without peers, downloading cannot begin.",
                style = MaterialTheme.typography.bodySmall)
            LocalMagnetDiscoveryPrompt(
                magnet = magnet,
                dhtEnabled = dht,
                applying = dhtSaving,
                blocked = busy || inventoryStale,
                onEnable = { requestDhtSetting(true) }
            )
            ActionTile(if (startAfterAdd) "Add magnet and start" else "Add magnet (metadata only)",
                ChunksColors.Purple, icon = R.drawable.chunks_ic_magnet, stacked = false, onClick = {
                val selectedDraft = addFolderDraft
                val selectedPlace = place
                val selectedMagnet = magnet
                val autoStart = startAfterAdd
                val orderLater = magnetFinishInOrder
                performLocalMutation {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val chosen = LocalOwnedAddFolder.prepare(
                                selectedPlace?.let(LocalDownloadPlaces::materialize), selectedDraft)
                            if (chosen == null) LocalAddResult.Refused(
                                "The download location or subfolder is unavailable, nonempty or unsafe. Nothing was added.")
                            else LocalEngineHolder.get(context).addMagnet(
                                selectedMagnet, chosen, autoStartAfterMetadata = autoStart)
                        }
                        if (orderLater && result is LocalAddResult.FetchingMetadata) withContext(Dispatchers.IO) {
                            finishInOrderStore.requestAfterMetadata(result.status.infoHash)
                        }
                        message = when (result) {
                            is LocalAddResult.Refused -> result.reason
                            is LocalAddResult.FetchingMetadata ->
                                if (startAfterAdd) "Magnet added. Discovering metadata; download begins automatically when metadata arrives and peers are available."
                                else "Magnet added in metadata-only mode. Use Start after the metadata is ready."
                            is LocalAddResult.AddedStopped -> null
                        }
                        if (result !is LocalAddResult.Refused) {
                            if (startAfterAdd) {
                                try { LocalEngineService.start(context) } catch (_: Exception) {
                                    message = "Magnet added, but Android did not start the background service. Keep Chunks open."
                                }
                            }
                            magnet = ""
                            addFolderDraft = ""
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        inventoryFrame = inventoryFrame.copy(stale = true)
                        message = "Magnet Add was not confirmed. Wait for a fresh transfer list and check for the magnet before retrying."
                    }
                }
            }, enabled = !busy && !inventoryStale && !dhtSaving &&
                proposedAddDestination != null,
                modifier = Modifier.fillMaxWidth())
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
                                        message = "Tracker repair was not confirmed. Check a fresh " +
                                            "phone status before any other change."
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
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        restoreNotice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (inventoryStale) {
            Text("Phone torrent status unavailable. Showing the last confirmed transfers. " +
                "Start, Stop, Remove, file selection, new Adds and library publication are disabled " +
                "until a fresh engine status read succeeds.",
                color = MaterialTheme.colorScheme.error)
        }

        val add = pending
        if (add != null && canReviewOnConnectedServer &&
            onPrepareRemoteTorrent != null) {
            ReviewedTorrentTargetChooser(
                verifiedTorrentBytes = add.bytes,
                enabled = !busy && !inventoryStale,
                onPrepareServerReview = { original ->
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
                checked = finishInOrderAfterAdd, enabled = !busy && !inventoryStale,
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
            busy = busy || inventoryStale || proposedAddDestination == null,
            startAfterAdd = startAfterAdd,
            storagePreflight = addStorage?.takeIf { it.first == add.preview.infoHashV1 }?.second,
            seeding = seedingPreview,
            onConfirm = {
                val selectedDraft = addFolderDraft
                val selectedPlace = place
                val autoStart = startAfterAdd
                val orderAfterAdd = finishInOrderAfterAdd
                var orderNote: String? = null
                performLocalMutation {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val chosen = LocalOwnedAddFolder.prepare(
                                selectedPlace?.let(LocalDownloadPlaces::materialize), selectedDraft)
                            if (chosen == null)
                                LocalAddResult.Refused(
                                    "The download location or subfolder is unavailable, nonempty or unsafe. Nothing was added.") to null
                            else {
                                val engine = LocalEngineHolder.get(context)
                                val added = engine.addStoppedChecked(add.bytes, chosen)
                                // While still stopped: set up the policy and apply its first verified
                                // step, so the very first piece requests already follow the order.
                                if (orderAfterAdd && added is LocalAddResult.AddedStopped) {
                                    orderNote = enableFinishInOrderAfterAdd(context, engine, added.status.infoHash)
                                }
                                if (autoStart && added is LocalAddResult.AddedStopped)
                                    added to engine.resume(added.status.infoHash)
                                else added to null
                            }
                        }
                        val (added, started) = result
                        message = when (added) {
                            is LocalAddResult.Refused -> added.reason
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
                        if (added !is LocalAddResult.Refused) {
                            pending = null
                            addFolderDraft = ""
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        inventoryFrame = inventoryFrame.copy(stale = true)
                        message = "Torrent Add was not confirmed. Wait for a fresh transfer list and check for this torrent before retrying."
                    }
                }
            },
            onCancel = { pending = null })

        snapshotRestoreDraft?.let { draft ->
            val eligible = hasConfirmedLocalInventory && !inventoryStale && !busy &&
                pending == null && place != null &&
                draft.frozenHashes == transfers.map { it.infoHash } &&
                LocalScreenReader.revision.snapshot() == draft.frozenGeneration
            LocalSnapshotStoppedRestoreReview(
                draft = draft,
                selectedPlace = place?.title ?: "No verified phone download location",
                eligible = eligible,
                onCancel = { snapshotRestoreDraft = null },
                onConfirm = {
                    val selected = place
                    if (selected == null || !eligible) {
                        message = "The destination or transfer list changed. Reopen the snapshot."
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

        LocalSnapshotRestorePreviewPanel(
            transfers = transfers,
            inventoryConfirmed = hasConfirmedLocalInventory,
            inventoryStale = inventoryStale,
            busyEngine = busy,
            mutationGeneration = LocalScreenReader.revision.snapshot(),
            onPrepareAdd = { originalBytes ->
                // This callback changes only the local Add DRAFT, not the
                // engine, destination folder, existing transfers or data.
                try {
                    val bytes = originalBytes.copyOf()
                    pending = PendingLocalAdd(TorrentMetainfoReader.preview(bytes), bytes)
                    addStorage = null
                    message = "Original snapshot .torrent is ready for manual phone Add review. " +
                        "Check the destination and Start choice before confirming; no Add occurred."
                    scope.launch { scrollState.animateScrollTo(0) }
                } catch (failure: Exception) {
                    pending = null
                    message = "Snapshot definition could not populate Add review: " +
                        (failure.message ?: "Unsupported original torrent")
                }
            },
            onReviewOneRestore = { verified, entry, frozen, generation ->
                // Assemble a canonical source fingerprint off the Compose lane.
                // No journal mutation or engine Add happens at this stage.
                val request = ++snapshotPreparationRevision
                scope.launch {
                    try {
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
                        if (request == snapshotPreparationRevision &&
                            LocalScreenReader.revision.snapshot() == generation &&
                            !LocalScreenReader.revision.blocksMutations()) {
                            snapshotRestoreDraft = draft
                            pending = null
                            message = "Review the new phone destination and explicitly confirm " +
                                "the stopped restore. No torrent has been added."
                            scrollState.animateScrollTo(0)
                        } else {
                            message = "The comparison changed before restore review. Reopen the snapshot."
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        message = "The verified original snapshot definition could not be prepared."
                    }
                }
            }
        )
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
        moveServer?.let { server ->
            val digest = TransferMoveStore.scopeDigest(server.aliasScope)
            val pending = remember(transfers, moveRevision) {
                moveStore.all(TransferMove.Direction.PHONE_TO_SERVER).filter { record ->
                    record.stage == TransferMove.Stage.TARGET_VERIFIED && record.serverScopeDigest == digest &&
                        transfers.any { it.infoHash.equals(record.infoHash, ignoreCase = true) }
                }
            }
            BatchMoveRemovalPanel(
                pending = pending, serverLabel = TransferMoveStore.hostLabel(server.aliasScope),
                busy = busy || inventoryStale,
                observeTarget = { hash -> server.observe(hash) },
                removeSource = { hash ->
                    withContext(Dispatchers.IO) { LocalEngineHolder.current()?.removeKeepingData(hash) }
                },
                observeSource = { hash -> TransferMoveObservations.phone(hash) },
                save = { record -> moveStore.save(record) },
                forget = { record -> moveStore.remove(record.direction, record.infoHash) },
                onFinished = { text -> message = text; moveRevision++ }
            )
        }
        LocalBatchCollectionPanel(
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
                        val journaled = withContext(Dispatchers.IO) {
                            verified.count { (item, _) ->
                                moveStore.save(TransferMove.start(TransferMove.Direction.PHONE_TO_SERVER,
                                    item.infoHash, item.name, TransferMoveStore.scopeDigest(server.aliasScope),
                                    TransferMoveStore.hostLabel(server.aliasScope))
                                    .copy(stage = TransferMove.Stage.TARGET_VERIFIED))
                            }
                        }
                        moveRevision++
                        report.summary() + " $journaled ready to finish moving: review removal of the " +
                            "original phone jobs below."
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
                    val report = withContext(Dispatchers.IO) {
                        val engine = LocalEngineHolder.get(context)
                        try {
                            LocalBatchLifecycle.run(targets, start) { hash, wanted ->
                                if (wanted) engine.resume(hash).outcome else engine.pause(hash).outcome
                            }
                        } catch (_: IllegalArgumentException) { null }
                    }
                    message = if (report == null) "This selection cannot be changed as a batch. Nothing changed."
                    else LocalBatchLifecycle.describe(report)
                    if (start && (report?.verified ?: 0) > 0) {
                        try { LocalEngineService.start(context) } catch (_: Exception) {
                            message += " Android refused the background service; keep Chunks open."
                        }
                    }
                }
            }
        )
        CollectionNameManager(
            scope = TransferCollectionStore.LOCAL_ENGINE_SCOPE,
            registry = nameRegistry,
            registeredNames = registeredNames,
            onNamesChanged = { registeredNames = it }
        )
        Text("Phone transfers", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = transferSearch,
            onValueChange = { transferSearch = it },
            label = { Text("Search local names, aliases, folders or hashes") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LocalQueueFilter.entries.forEach { choice ->
                FilterChip(
                    selected = transferFilter == choice,
                    onClick = { transferFilter = choice },
                    label = { Text(choice.label) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
        Text("Sort by", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
        Text("Showing ${displayedTransfers.size} of ${transfers.size} local transfers",
            style = MaterialTheme.typography.bodySmall)
        if (transfers.isEmpty()) {
            Text("No transfers on this phone yet.", style = MaterialTheme.typography.bodySmall)
        } else if (displayedTransfers.isEmpty()) {
            Text("No transfers match this search or filter.",
                style = MaterialTheme.typography.bodySmall)
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
            LocalTransferCard(transfer, busy || inventoryStale,
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
                            val outcome = withContext(Dispatchers.IO) {
                                val engine = LocalEngineHolder.get(context)
                                if (transfer.stopped) engine.resume(transfer.infoHash) else engine.pause(transfer.infoHash)
                            }
                            var serviceProblem: String? = null
                            if (transfer.stopped && outcome.outcome != LocalCommandOutcome.NOT_FOUND) {
                                try { LocalEngineService.start(context) } catch (_: Exception) {
                                    serviceProblem = "Android did not allow background running; keep Chunks open."
                                }
                            }
                            message = when (outcome.outcome) {
                                LocalCommandOutcome.VERIFIED -> serviceProblem
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
                            message = "Start/Stop was not confirmed. Wait for fresh phone torrent status before trying again."
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
                                LocalCommandOutcome.NOT_FOUND -> "This transfer or file is no longer available."
                                LocalCommandOutcome.UNCERTAIN -> "File selection is unconfirmed. Wait for fresh status before trying again."
                            }
                            files = withContext(Dispatchers.IO) { LocalEngineHolder.current()?.files(transfer.infoHash) }
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            inventoryFrame = inventoryFrame.copy(stale = true)
                            files = null
                            message = "File selection or readback failed. Wait for fresh status before trying again."
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
                            message = "Episode-priority change is uncertain. Refresh first."
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
                            message = "File priority outcome is uncertain. Refresh before another change."
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
                            message = "Native batch file selection was not confirmed. " +
                                "Wait for fresh status before trying again."
                        }
                    }
                },
                onPrepareRemote = prepareRemoteHandoff,
                move = {
                    TransferMoveControl(
                        direction = TransferMove.Direction.PHONE_TO_SERVER,
                        infoHash = transfer.infoHash, name = transfer.name,
                        serverAliasScope = moveServer?.aliasScope?.takeIf { prepareRemoteHandoff != null },
                        busy = busy || inventoryStale,
                        ports = TransferMovePorts(
                            observeTarget = moveServer?.let { server -> { server.observe(transfer.infoHash) } },
                            observeSource = { TransferMoveObservations.phone(transfer.infoHash) },
                            removeSource = {
                                withContext(Dispatchers.IO) {
                                    LocalEngineHolder.current()?.removeKeepingData(transfer.infoHash)
                                }
                            },
                            targetUnavailableReason = "Connect to the server account to check it."
                        ),
                        onStartMove = { prepareRemoteHandoff?.invoke() },
                        revision = moveRevision
                    )
                },
                onRemove = {
                    performLocalMutation {
                        try {
                            val removed = withContext(Dispatchers.IO) {
                                LocalEngineHolder.current()?.removeKeepingData(transfer.infoHash)
                            }
                            message = if (removed == true) null
                                else "Session removal was not confirmed. Check the transfer list before trying again."
                            if (removed != true) inventoryFrame = inventoryFrame.copy(stale = true)
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            // Session removal may have succeeded before the exception.
                            inventoryFrame = inventoryFrame.copy(stale = true)
                            message = "Session removal was not confirmed. Wait for fresh status before trying again. Downloaded files were not requested for deletion."
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
        LocalZipInterruptedExtractions()
        LocalSpeedLimits()
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
    onCancel: () -> Unit
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
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
        }
    }
}

private fun stateLabel(transfer: LocalTransferStatus): String = when {
    transfer.error != null -> "Error"
    transfer.stopped -> "Stopped"
    transfer.state == LocalTransferState.CHECKING -> "Checking files"
    !transfer.metadataKnown || transfer.state == LocalTransferState.DOWNLOADING_METADATA -> "Fetching description"
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
    seeding: io.github.gamesbyian.chunks.seeding.SeedingController.Assignment? = null
) {
    var confirmRemove by remember(transfer.infoHash) { mutableStateOf(false) }
    var confirmHandoff by remember(transfer.infoHash) { mutableStateOf(false) }
    val percent = transfer.progressPpm / 10_000
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(alias ?: transfer.name, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
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
                Text("Live payload ↓ " +
                    Units.bytes(transfer.downloadPayloadRateBytesPerSecond.toLong()) + "/s · ↑ " +
                    Units.bytes(transfer.uploadPayloadRateBytesPerSecond.toLong()) + "/s",
                    style = MaterialTheme.typography.bodySmall)
            }
            LocalTrackerSummaryText.describe(transfer.trackers, transfer.stopped,
                LocalEngineHolder.dhtPreferred(LocalContext.current))?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            seeding?.let { SeedingSnapshotSummary(transfer.infoHash, it) }
            ChunkedProgressBar(progress = transfer.progressPpm / 1_000_000f, tone = stateTone(transfer),
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = "$percent percent complete" })
            // totalWantedDone is hash-verified completed payload; allTimeDownload
            // is a libtorrent network counter and may exclude HTTPS web seeds.
            Text("Verified on disk " + Units.bytes(transfer.doneBytes) + " of " +
                Units.bytes(transfer.wantedBytes) + " · Shared " +
                Units.bytes(transfer.payloadUploadedBytes), style = MaterialTheme.typography.bodySmall)
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
            // Three tiles side by side split words at 200% text; stack them instead.
            val wideText = LocalDensity.current.fontScale >= 1.5f
            val tile: Modifier = if (wideText) Modifier.fillMaxWidth() else Modifier
            val actions: @Composable (Modifier) -> Unit = { each ->
                ActionTile(if (transfer.stopped) "Start" else "Stop",
                    if (transfer.stopped) ChunksColors.Green else ChunksColors.Purple,
                    onClick = onToggle, enabled = !busy, modifier = each,
                    icon = if (transfer.stopped) R.drawable.chunks_ic_play else R.drawable.chunks_ic_stop,
                    stacked = !wideText)
                ActionTile("Remove…", ChunksColors.Orange, onClick = { confirmRemove = true },
                    enabled = !busy && !confirmRemove, modifier = each,
                    icon = R.drawable.chunks_ic_remove, stacked = !wideText)
                if (transfer.metadataKnown) ActionTile(if (filesShown) "Hide files" else "Files",
                    ChunksColors.Cyan, onClick = onShowFiles, enabled = !busy, modifier = each,
                    icon = R.drawable.chunks_ic_folder, stacked = !wideText)
            }
            if (wideText) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { actions(tile) }
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                actions(Modifier.weight(1f))
            }
            if (onRepairTrackers != null && transfer.stopped &&
                transfer.metadataKnown && transfer.trackers?.total == 0) {
                OutlinedButton(
                    onClick = onRepairTrackers,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("Repair missing trackers from original .torrent…") }
            }
            if (onPrepareRemote != null &&
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            confirmHandoff = false
                            onPrepareRemote()
                        }, enabled = !busy, modifier = Modifier.weight(1f)
                            .heightIn(min = 48.dp)) { Text("Review server Add") }
                        OutlinedButton(onClick = { confirmHandoff = false },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("Cancel")
                        }
                    }
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
            if (confirmRemove) {
                Text("Remove from the phone engine only. Downloaded files stay in Chunks' folder; sharing of this torrent stops.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { confirmRemove = false; onRemove() }, enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove, keep files") }
                    OutlinedButton(onClick = { confirmRemove = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Keep")
                    }
                }
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
    val pageCount = if (files.isEmpty()) 1 else (files.size - 1) / pageSize + 1
    var requestedPage by remember(transferKey, files.size) { mutableStateOf(0) }
    val page = requestedPage.coerceIn(0, pageCount - 1)
    val pageFiles = files.drop(page * pageSize).take(pageSize)
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
    var pendingBatch by remember(transferKey, page, reviewFingerprint) {
        mutableStateOf<Boolean?>(null)
    }
    var priorityEditorIndex by remember(transferKey, page, reviewFingerprint) {
        mutableStateOf<Int?>(null)
    }
    var pendingEpisodeFocus by remember(transferKey, reviewFingerprint) {
        mutableStateOf(false)
    }
    val focusPlan = remember(files) { LocalEpisodeFocusPlanner.plan(files) }

    Text("Untick a file to skip downloading it. Already downloaded parts are kept.",
        style = MaterialTheme.typography.bodySmall)
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
        Text("Files page " + (page + 1) + " of " + pageCount + " (" + files.size + " files)",
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
                    "${changes} of ${pageFiles.size} files on this page. " +
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

private fun speedText(bytesPerSecond: Int) = if (bytesPerSecond == 0) "" else (bytesPerSecond / 1024).toString()

@Composable
internal fun LocalSpeedLimits() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { LocalEngineHolder.rateLimitsPreferred(context) }
    var download by remember { mutableStateOf(speedText(saved.downloadBytesPerSecond)) }
    var upload by remember { mutableStateOf(speedText(saved.uploadBytesPerSecond)) }
    var result by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val downloadLimit = parseSpeedLimit(download)
    val uploadLimit = parseSpeedLimit(upload)
    Text("Speed limits", fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
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
