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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalFileBatchOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalRateLimits
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
import io.github.gamesbyian.chunks.snapshots.LocalSnapshotRestorePreviewPanel
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoPreview
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One engine per process. It lives only as long as Android keeps Chunks running. */
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
    val revision = LocalScreenObservationRevision()
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
    initialMagnet: String? = null,
    initialTorrentUri: Uri? = null,
    onTorrentConsumed: () -> Unit = {},
    onPrefillConsumed: () -> Unit = {},
    onPrepareRemote: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    var transferOrder by remember { mutableStateOf(LocalQueueOrder.NAME) }
    val displayedTransfers = remember(transfers, transferSearch, transferFilter,
        transferOrder, aliasRevision) {
        LocalQueueCatalog.visible(transfers, transferSearch, transferFilter, transferOrder) {
            transfer -> aliasStore.get(TransferCollectionStore.LOCAL_ENGINE_SCOPE,
                transfer.infoHash) ?: transfer.name
        }
    }
    // Changing the result set or its order always returns to page one. A new
    // engine observation does not change filter/search or silently select items.
    LaunchedEffect(transferSearch, transferFilter, transferOrder) { transferPage = 0 }
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
    LaunchedEffect(Unit) {
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
        if (onBack != null) Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
        Text("On this phone (experimental)", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Text("Downloads and seeds with Chunks' own engine. While a transfer runs, an ongoing notification keeps it going in the background, with Stop all; Android may still stop it, and from Android 15 limits this kind of background transfer to about 6 hours a day, after which Chunks pauses everything. Nothing connects to the network until you add a torrent. Transfers are remembered and come back stopped after Chunks restarts. Peers and trackers can see this phone's IP address.")
        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy && !inventoryStale && destination != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Choose .torrent file") }
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
            }
        )
        OutlinedTextField(
            value = addFolderDraft,
            onValueChange = { addFolderDraft = it },
            label = { Text("Optional phone download subfolder") },
            placeholder = { Text("Leave blank for Chunks default") },
            singleLine = true,
            isError = proposedAddDestination == null,
            enabled = !busy && !inventoryStale,
            modifier = Modifier.fillMaxWidth()
        )
        Text("Optional: give this new local transfer its own subfolder inside the " +
            "download location above. Leave blank to save directly there. " +
            "An empty existing subfolder can be reused; a nonempty folder is " +
            "refused to prevent collisions. No folder is created until Add is confirmed, " +
            "and nothing is ever created on a remote server.",
            style = MaterialTheme.typography.bodySmall)
        if (proposedAddDestination == null)
            Text("Use one plain folder name, without slashes, dot-prefixed names, " +
                "control characters or path traversal.",
                color = MaterialTheme.colorScheme.error)
        else Text("Planned local Place: " + proposedAddDestination.absolutePath,
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = dht, enabled = !dhtSaving, role = Role.Switch,
                onValueChange = ::requestDhtSetting), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Find peers without trackers (DHT)")
                Text("Needed for most magnet links. Off by default: when on, this phone's IP address is shared with the public DHT network.",
                    style = MaterialTheme.typography.bodySmall)
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
        if (magnet.isNotEmpty() && destination != null) {
            Text("Saves into the download location shown above. Magnets first discover metadata via peers/trackers; if Start after Add is selected, payload downloading begins after metadata arrives. Without peers, downloading cannot begin.",
                style = MaterialTheme.typography.bodySmall)
            LocalMagnetDiscoveryPrompt(
                magnet = magnet,
                dhtEnabled = dht,
                applying = dhtSaving,
                blocked = busy || inventoryStale,
                onEnable = { requestDhtSetting(true) }
            )
            OutlinedButton(onClick = {
                val selectedDraft = addFolderDraft
                val selectedPlace = place
                val selectedMagnet = magnet
                val autoStart = startAfterAdd
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (startAfterAdd) "Add magnet and start" else "Add magnet (metadata only)") }
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
        if (add != null && destination != null) LocalAddReview(
            add.preview, proposedAddDestination ?: destination,
            placeTitle = place?.title,
            busy = busy || inventoryStale || proposedAddDestination == null,
            startAfterAdd = startAfterAdd,
            storagePreflight = addStorage?.takeIf { it.first == add.preview.infoHashV1 }?.second,
            onConfirm = {
                val selectedDraft = addFolderDraft
                val selectedPlace = place
                val autoStart = startAfterAdd
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
                                val added = engine.addStopped(add.bytes, chosen)
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
        LocalBatchCollectionPanel(
            transfers = transfers,
            shown = displayedTransfers,
            inventoryStale = inventoryStale || busy,
            store = collectionStore,
            onMembershipChanged = {
                registeredNames = nameRegistry.names(TransferCollectionStore.LOCAL_ENGINE_SCOPE)
                collectionRevision++
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
                    onClick = { transferOrder = choice },
                    label = { Text(choice.label) },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
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
            LocalTransferCard(transfer, busy || inventoryStale,
                observedGrowth = observedDeltas[transfer.infoHash].takeIf {
                    !inventoryStale && !busy
                },
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
                onPrepareRemote = onPrepareRemote?.let { send ->
                    {
                        val prepared = TorrentIdentityHandoff.prepare(
                            transfer.infoHash, transfer.name)
                        if (prepared != null) send(prepared)
                        else message = "This torrent has no supported v1 identity to hand off."
                    }
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
    transfer.error != null -> "Error: " + transfer.error
    transfer.stopped -> "Stopped"
    transfer.state == LocalTransferState.CHECKING -> "Checking files"
    !transfer.metadataKnown || transfer.state == LocalTransferState.DOWNLOADING_METADATA -> "Fetching description (no file data)"
    transfer.state == LocalTransferState.DOWNLOADING -> "Downloading"
    transfer.state == LocalTransferState.SEEDING || transfer.state == LocalTransferState.FINISHED -> "Sharing"
    else -> "Unknown"
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
    alias: String? = null,
    onSaveAlias: ((String?) -> Boolean)? = null,
    onPrepareRemote: (() -> Unit)? = null,
    observedGrowth: LocalProgressHistory.ObservedDelta? = null
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
            Text(stateLabel(transfer) + " · " + percent + "% · " + transfer.peers + " peers")
            LocalTrackerSummaryText.describe(transfer.trackers, transfer.stopped,
                LocalEngineHolder.dhtPreferred(LocalContext.current))?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            ChunkedProgressBar(progress = transfer.progressPpm / 1_000_000f,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onToggle, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (transfer.stopped) "Start" else "Stop")
                }
                OutlinedButton(onClick = { confirmRemove = true }, enabled = !busy && !confirmRemove,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove…") }
                if (transfer.metadataKnown) OutlinedButton(onClick = onShowFiles,
                    enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (filesShown) "Hide files" else "Files")
                }
            }
            if (onPrepareRemote != null &&
                TorrentIdentityHandoff.prepare(transfer.infoHash, transfer.name) != null) {
                OutlinedButton(onClick = { confirmHandoff = true }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Prepare torrent on rTorrent (identity only)")
                }
                if (confirmHandoff) {
                    Text("Only the magnet identity goes to Chunks' server Add draft. " +
                        "No payload bytes move; the server may need to download again. " +
                        "Private/tracker-only torrents may not find metadata or peers. " +
                        "The phone transfer remains untouched.",
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
            if (filesShown) LocalFileSelection(
                files, busy, transferKey = transfer.infoHash,
                onFileWanted = onFileWanted, onBatchFileWanted = onBatchFileWanted
            )
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
                file.wanted.toString())
        }
    }
    // Key pending confirmation to the exact review identity instead of
    // clearing it from an asynchronous LaunchedEffect, which could race a
    // newly clicked Select/Skip button on the first Compose frame.
    var pendingBatch by remember(transferKey, page, reviewFingerprint) {
        mutableStateOf<Boolean?>(null)
    }
    Text("Untick a file to skip downloading it. Already downloaded parts are kept.",
        style = MaterialTheme.typography.bodySmall)
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
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = file.wanted, enabled = !busy, role = Role.Checkbox,
                onValueChange = { onFileWanted(file.index, it) }),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = file.wanted, onCheckedChange = null)
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                Text(Units.bytes(file.sizeBytes) + " · " + percent + "%" + if (file.wanted) "" else " · skipped",
                    style = MaterialTheme.typography.bodySmall)
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
