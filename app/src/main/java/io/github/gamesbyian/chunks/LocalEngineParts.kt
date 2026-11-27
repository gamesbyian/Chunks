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

// A2611-02: the Local screen's supporting composables, moved unchanged out of LocalEngineScreen.kt.
/** Latest local action outcome, announced to accessibility services once per change. */
@Composable
internal fun LocalOutcomeNotice(
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
internal fun LocalIntakeDisclosure(
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
            expanded -> "Hide local Add options"
            pendingReview -> "Review torrent on this device"
            else -> "Add torrent on this device"
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
    /** What Smart Seeding will apply on this device; null when no rule store is readable. */
    seeding: io.github.gamesbyian.chunks.seeding.SeedingResolution? = null,
    onCancel: () -> Unit,
    cancelEnabled: Boolean = true,
    /** Foreign partial files next to the declared names; reuse only on explicit choice. */
    recovery: io.github.gamesbyian.chunks.engine.ForeignPartialAddPlan.Offer? = null,
    useRecovery: Boolean = false,
    onUseRecovery: (Boolean) -> Unit = {}
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Torrent details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            Text(preview.name, fontWeight = FontWeight.Bold)
            Text(preview.fileCount.toString() + (if (preview.fileCount == 1) " file, " else " files, ") +
                Units.bytes(preview.sizeBytes))
            Text(if (placeTitle == null)
                "Saves into: Chunks' folder on this device storage (" + destination.absolutePath + "). Files here are removed if Chunks is uninstalled."
            else "Saves into: " + placeTitle + " (" + destination.absolutePath + ").")
            seeding?.let { s ->
                Text("Seeding: " + when (s.source) {
                    io.github.gamesbyian.chunks.seeding.SeedingResolution.Source.RULE -> "rule"
                    else -> "default for this device"
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
                            "Check the local device storage panel before adding.",
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
            if (recovery != null) ForeignPartialRecoveryPanel(recovery, useRecovery, !busy, onUseRecovery)
            Text(if (startAfterAdd)
                "Chunks adds stopped, verifies the new transfer, then starts it. Download progress depends on available peers or web seeds."
                else "It is added stopped. Nothing downloads until you press Start.",
                style = MaterialTheme.typography.bodySmall)
            if (preview.trackerCount == 0) Text("This torrent lists no tracker, so peers can only be found through DHT (if turned on) or its web sources.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onCancel, enabled = cancelEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
        }
    }
}

private fun stateLabel(transfer: LocalTransferStatus): String = when {
    transfer.error != null -> "Error"
    transfer.stopped -> "Stopped"
    transfer.state == LocalTransferState.CHECKING && transfer.stopsAfterCheck -> CHECKING_STAYS_STOPPED
    transfer.state == LocalTransferState.CHECKING -> "Checking existing data"
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
    /** The Smart Seeding snapshot the local device controller stored; null before it has seen this transfer. */
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
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val artMaxWidth = minOf(maxWidth * 0.32f, 112.dp,
                    (maxWidth - 164.dp).coerceAtLeast(48.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransferRowArtwork(rememberLocalTransferKey(transfer.infoHash), transfer.name,
                        modifier = Modifier.testTag("local-card-artwork"),
                        height = if (diagnosticsOpen) 132.dp else 112.dp,
                        maxWidth = artMaxWidth)
                    Column(Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(alias ?: transfer.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    // Keep local cards as scannable as the already bounded remote
                    // and Combined rows. Expanded details reveal the full title.
                    // TalkBack always gets the entire exact visible display name.
                    maxLines = if (diagnosticsOpen) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = alias ?: transfer.name
                    })
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
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
                historyKey = ProgressChunkStore.identity("local", transfer.infoHash),
                observeFresh = !busy,
                modifier = Modifier.fillMaxWidth().testTag("local-card-progress")
                    .semantics { stateDescription = "$percent percent complete" }
            ) else Text("Progress unknown until torrent metadata arrives",
                style = MaterialTheme.typography.bodySmall)
            // totalWantedDone is hash-verified completed payload; allTimeDownload
            // is a libtorrent network counter and may exclude HTTPS web seeds.
            val verifiedText = "Verified on disk " + Units.bytes(transfer.doneBytes) + " of " +
                Units.bytes(transfer.wantedBytes) + " · Shared " +
                Units.bytes(transfer.payloadUploadedBytes)
            val diagnosticsModifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = if (diagnosticsOpen)
                    "Hide diagnostics for ${transfer.name} on this device"
                else "More details for ${transfer.name} on this device"
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
                    } // Compact information remains entirely to the right of artwork.
                }
            }
            if (diagnosticsOpen) {
            // Presentation only, keyed by the exact local job; never reads or changes payload files.
            rememberLocalTransferKey(transfer.infoHash)?.let { key ->
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
                            transfer.name + " on this device"
                    },
                    icon = if (transfer.stopped) R.drawable.chunks_ic_play else R.drawable.chunks_ic_stop,
                    stacked = !wideText)
                ActionTile("Remove…", ChunksColors.Orange, onClick = { confirmRemove = true },
                    enabled = !busy && !confirmRemove,
                    modifier = each.semantics {
                        contentDescription = "Remove transfer " + transfer.name + " from this device"
                    },
                    icon = R.drawable.chunks_ic_remove, stacked = !wideText)
                if (transfer.metadataKnown) ActionTile(if (filesShown) "Hide files" else "Files",
                    ChunksColors.Cyan, onClick = onShowFiles, enabled = !busy,
                    modifier = each.semantics {
                        contentDescription = (if (filesShown) "Hide files for " else "Show files for ") +
                            transfer.name + " on this device"
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
                    Text("Copy Transfer to remote client…")
                }
                if (confirmHandoff) {
                    Text("Chunks opens your rTorrent client's Add review with this torrent's " +
                        ".torrent definition, including its tracker addresses. For private " +
                        "trackers these can contain your personal passkey; they are sent only " +
                        "to the remote client you are signed in to, and nothing is added until you " +
                        "confirm there. If the definition is unavailable, only the magnet " +
                        "identity is used, which may not find peers. No payload bytes move; " +
                        "the remote client downloads from peers itself. The local transfer is untouched.",
                        style = MaterialTheme.typography.bodySmall)
                    LocalTransferConfirmationActions(
                        confirmLabel = "Review remote Add",
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
                Text("Remove from this device: " + transfer.name,
                    style = MaterialTheme.typography.titleSmall)
                Text("Infohash: " + transfer.infoHash, style = MaterialTheme.typography.bodySmall)
                Text("Current folder: " + transfer.savePath, style = MaterialTheme.typography.bodySmall)
                Text("Choose what to remove from this device. Keeping data stops this torrent in Chunks, but leaves downloaded files in their current folder.")
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
    SettingScopeNote(LOCAL_ENVIRONMENT_LABEL)
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
                        "Speed limits verified (if the engine is running) and saved on this device."
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
                    result = if (confirmed) "Speed limits reset to unlimited on this device."
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
 * The default only pre-ticks future Add reviews on this device; it never
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
        Text("Tick this for new multi-file Adds on this device. Existing transfers are not changed.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * Create a Finish in Order policy from the suggested order of a just-added,
 * still-stopped local transfer and apply its first verified step. Returns a
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
 * What Smart Seeding will snapshot for this device Add, resolved from the
 * reviewed metadata's tracker hosts. Null when the saved rules are unreadable.
 */
internal fun localSeedingPreview(context: Context, preview: io.github.gamesbyian.chunks.rpc.TorrentMetainfoPreview):
    io.github.gamesbyian.chunks.seeding.SeedingResolution? {
    val snapshot = io.github.gamesbyian.chunks.seeding.SeedingRuleStore(context).load()
    if (snapshot.unreadable) return null
    val env = io.github.gamesbyian.chunks.seeding.LocalSeeding.ENVIRONMENT
    return io.github.gamesbyian.chunks.seeding.SeedingRuleEngine.resolve(
        io.github.gamesbyian.chunks.seeding.TransferFacts(env, preview.trackerHosts.map { "https://$it/" }, preview.isPrivate),
        snapshot.rules, snapshot.defaults[env] ?: io.github.gamesbyian.chunks.seeding.SeedingPolicy())
}

/**
 * Read-only: the policy snapshot Smart Seeding froze for this device transfer,
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
        "rule" else "default for this device") + " · " + policy.describe(), style = MaterialTheme.typography.bodySmall)
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
