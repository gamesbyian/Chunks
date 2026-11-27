package io.github.gamesbyian.chunks.snapshots

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.RemoteFolderPicker
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * User-selected .chunksnap -> validated original-identity comparison against a
 * CURRENT FULL local-engine list. No engine command, network request, restored
 * file, destination choice or mutation is reachable from this screen.
 *
 * The caller is responsible for proving at least one successful whole-engine
 * observation. A fresh-looking initial empty Compose list is NOT proof.
 */
@Composable
internal fun LocalSnapshotRestorePreviewPanel(
    transfers: List<LocalTransferStatus>,
    inventoryConfirmed: Boolean,
    inventoryStale: Boolean,
    busyEngine: Boolean,
    mutationGeneration: Long,
    /** Only primes the existing Add review. Never adds a torrent. */
    onPrepareAdd: ((ByteArray) -> Unit)? = null,
    /** Queues a separate explicit Place + stopped-restore confirmation. */
    onReviewOneRestore: ((SnapshotManifestV1.Verified, SnapshotRestorePreviewV1.Entry,
        List<String>, Long) -> Unit)? = null,
    /** One explicit batch review for all currently missing complete metainfo. */
    onReviewBatchRestore: ((SnapshotManifestV1.Verified, List<SnapshotRestorePreviewV1.Entry>,
        List<String>, Long) -> Unit)? = null,
    /** Read-only batch recovery also checks already-present metainfo identities. */
    onReviewBatchRecovery: ((SnapshotManifestV1.Verified, List<SnapshotRestorePreviewV1.Entry>,
        List<String>, Long) -> Unit)? = null,
    /** Existing hash only: check an earlier journal, never submit. */
    onReviewPreviousRestore: ((SnapshotManifestV1.Verified, SnapshotRestorePreviewV1.Entry,
        List<String>, Long) -> Unit)? = null,
    onApplyAlias: ((SnapshotRestorePreviewV1.Entry, Long, (String) -> Unit) -> Unit)? = null,
    readPriorityReview: (suspend (SnapshotRestorePreviewV1.Entry) -> LocalSnapshotPriorityRestore?)? = null,
    onApplyPriorities: ((LocalSnapshotPriorityRestore, Long, (String) -> Unit) -> Unit)? = null
) = SnapshotRestorePreviewPanel(
    targetHashes = transfers.map { it.infoHash },
    target = SnapshotRestoreTarget.LOCAL,
    inventoryConfirmed = inventoryConfirmed,
    inventoryStale = inventoryStale,
    busyEngine = busyEngine,
    mutationGeneration = mutationGeneration,
    onPrepareAdd = onPrepareAdd,
    onReviewOneRestore = onReviewOneRestore,
    onReviewBatchRestore = onReviewBatchRestore,
    onReviewBatchRecovery = onReviewBatchRecovery,
    onReviewPreviousRestore = onReviewPreviousRestore,
    onApplyAlias = onApplyAlias,
    readPriorityReview = readPriorityReview,
    onApplyPriorities = onApplyPriorities
)

/** Wording for the engine a snapshot is compared against. */
internal data class SnapshotRestoreTarget(
    val noun: String,
    val title: String,
    val observation: String,
    val busyOperation: String,
    val presentLabel: String,
    val addLabel: String,
    val supportsLowPriority: Boolean
) {
    companion object {
        val LOCAL = SnapshotRestoreTarget("local", "Local", "local-engine",
            "a native operation", "Already on this device (skip)",
            "Review original .torrent in local Add", supportsLowPriority = true)
        val SERVER = SnapshotRestoreTarget("rTorrent client", "rTorrent client",
            "rTorrent client (all-transfers view)", "a remote operation",
            "Already on this remote client (skip)", "Review original .torrent in remote Add", supportsLowPriority = false)
    }
}

/**
 * Engine-neutral comparison. [targetHashes] must be one complete, fresh
 * inventory of a single engine or profile; any change revokes the result.
 */
@Composable
internal fun SnapshotRestorePreviewPanel(
    targetHashes: List<String>,
    target: SnapshotRestoreTarget,
    inventoryConfirmed: Boolean,
    inventoryStale: Boolean,
    busyEngine: Boolean,
    mutationGeneration: Long,
    /** Only primes the existing Add review. Never adds a torrent. */
    onPrepareAdd: ((ByteArray) -> Unit)? = null,
    /** Only primes an Add review with a magnet-only record. Never adds. */
    onPrepareMagnet: ((String) -> Unit)? = null,
    onReviewOneRestore: ((SnapshotManifestV1.Verified, SnapshotRestorePreviewV1.Entry,
        List<String>, Long) -> Unit)? = null,
    onReviewBatchRestore: ((SnapshotManifestV1.Verified, List<SnapshotRestorePreviewV1.Entry>,
        List<String>, Long) -> Unit)? = null,
    onReviewBatchRecovery: ((SnapshotManifestV1.Verified, List<SnapshotRestorePreviewV1.Entry>,
        List<String>, Long) -> Unit)? = null,
    onReviewPreviousRestore: ((SnapshotManifestV1.Verified, SnapshotRestorePreviewV1.Entry,
        List<String>, Long) -> Unit)? = null,
    onApplyAlias: ((SnapshotRestorePreviewV1.Entry, Long, (String) -> Unit) -> Unit)? = null,
    readPriorityReview: (suspend (SnapshotRestorePreviewV1.Entry) -> LocalSnapshotPriorityRestore?)? = null,
    onApplyPriorities: ((LocalSnapshotPriorityRestore, Long, (String) -> Unit) -> Unit)? = null,
    /** Independently read current files on this exact connected rTorrent account. No writes. */
    readRemotePriorityReview: (suspend (SnapshotRestorePreviewV1.Entry) -> SnapshotRemotePriorityComparison?)? = null,
    /** Explicit consent and exact-session fencing for rTorrent-only priority writes. */
    onApplyRemotePriorities: ((SnapshotRestorePreviewV1.Entry,
        SnapshotRemotePriorityComparison, Long, (String) -> Unit) -> Unit)? = null,
    remoteDefinitionRestore: SnapshotRemoteDefinitionRestore? = null,
    remoteBatchStoppedRestore: SnapshotRemoteBatchStoppedRestore? = null,
    remoteDefinitionActionScope: CoroutineScope? = null,
    remoteDefinitionAccountLabel: String? = null,
    /** Only the currently authenticated exact account supplies browse capability. */
    remoteDefinitionFolderAccount: RTorrentRemote? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SnapshotRestorePreviewV1.Preview?>(null) }
    // The verified source stays in process memory only while the exact target
    // inventory/revision that was compared remains current.
    var verifiedSource by remember { mutableStateOf<SnapshotManifestV1.Verified?>(null) }
    var comparedHashes by remember { mutableStateOf<List<String>?>(null) }
    var comparedGeneration by remember { mutableLongStateOf(-1L) }
    var manualNotice by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var chosenDecision by remember {
        mutableStateOf<SnapshotRestorePreviewV1.Decision?>(null)
    }
    var aliasNotice by remember { mutableStateOf<String?>(null) }
    var priorityNotice by remember { mutableStateOf<String?>(null) }
    var priorityReview by remember { mutableStateOf<LocalSnapshotPriorityRestore?>(null) }
    var remotePriorityReview by remember { mutableStateOf<SnapshotRemotePriorityComparison?>(null) }
    var remotePriorityEntry by remember { mutableStateOf<SnapshotRestorePreviewV1.Entry?>(null) }
    var confirmRemotePriorities by remember { mutableStateOf(false) }
    var remoteApplying by remember { mutableStateOf(false) }
    var checkingPriorities by remember { mutableStateOf(false) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var remoteDefinitionReview by remember(remoteDefinitionRestore) {
        mutableStateOf<SnapshotRemoteDefinitionRestore.Review?>(null)
    }
    var remoteDefinitionBusy by remember { mutableStateOf(false) }
    var remoteDefinitionNotice by remember { mutableStateOf<String?>(null) }
    var remoteBatchDraft by remember(remoteBatchStoppedRestore) {
        mutableStateOf<SnapshotRemoteBatchStoppedRestore.Draft?>(null)
    }
    var remoteBatchBusy by remember(remoteBatchStoppedRestore) { mutableStateOf(false) }
    var remoteBatchNotice by remember(remoteBatchStoppedRestore) { mutableStateOf<String?>(null) }
    var remotePreviousReport by remember(remoteDefinitionRestore) {
        mutableStateOf<SnapshotRemoteDefinitionRestore.PreviousReport?>(null)
    }
    // The destination is an ephemeral draft. No manually entered text becomes
    // restore authority until the coordinator verifies the exact server folder.
    var remoteSelectedPlace by remember(remoteDefinitionRestore) { mutableStateOf<String?>(null) }
    var chooseRemotePlace by remember(remoteDefinitionRestore) { mutableStateOf(false) }

    // Local status counters and peers may update each second. Identity changes
    // are the relevant source boundary. A stale/unverified frame remains
    // unavailable even if its old hash list appears unchanged.
    val observedHashes = targetHashes.map { it.lowercase(Locale.ROOT) }
    val targetReady = inventoryConfirmed && !inventoryStale && !busyEngine
    val latestHashes by rememberUpdatedState(observedHashes)
    val latestReady by rememberUpdatedState(targetReady)
    val latestGeneration by rememberUpdatedState(mutationGeneration)
    LaunchedEffect(observedHashes, targetReady, mutationGeneration) {
        result = null
        verifiedSource = null
        comparedHashes = null
        manualNotice = null
        priorityReview = null
        remotePriorityReview = null
        remotePriorityEntry = null
        confirmRemotePriorities = false
        remoteDefinitionReview = null
        remotePreviousReport = null
        // A changed comparison revokes an unconfirmed bulk review, but not a
        // series already separately confirmed and executing on its own lease.
        if (!remoteBatchBusy) remoteBatchDraft = null
        if (!targetReady) error = "A fresh, complete ${target.noun} transfer list is required before comparison."
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            reading = false
        } else {
            // Freeze exactly the local inventory that was present when the
            // document picker returned. Later native updates revoke the result.
            val frozen = latestHashes.toList()
            val frozenGeneration = latestGeneration
            if (!latestReady) {
                reading = false
                result = null
                verifiedSource = null
                comparedHashes = null
                error = "${target.title} transfer inventory became stale. Refresh before trying again."
            } else {
                reading = true
                result = null
                verifiedSource = null
                comparedHashes = null
                manualNotice = null
                error = null
                scope.launch {
                    try {
                        val candidate = withContext(Dispatchers.IO) {
                            val inspected = SnapshotDocumentReaderV1.inspect(
                                context.contentResolver, uri)
                            inspected to SnapshotRestorePreviewV1.plan(
                                inspected, frozen, targetInventoryComplete = true)
                        }
                        if (latestReady && latestHashes == frozen &&
                            latestGeneration == frozenGeneration) {
                            result = candidate.second
                            verifiedSource = candidate.first
                            comparedHashes = frozen
                            comparedGeneration = frozenGeneration
                        } else {
                            error = "${target.title} torrent identities changed during review. " +
                                "The comparison was discarded; reopen the snapshot."
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        error = "Cannot verify this snapshot against the ${target.noun}: " +
                            (failure.message ?: "Unsupported or unsafe archive")
                    } finally {
                        reading = false
                    }
                }
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    expanded = !expanded
                    aliasNotice = null
                    priorityNotice = null
                    priorityReview = null
                    remotePriorityReview = null
                    result = null
                    verifiedSource = null
                    comparedHashes = null
                    manualNotice = null
                    remoteDefinitionReview = null
                    remoteDefinitionNotice = null
                    remoteBatchDraft = null
                    remoteBatchNotice = null
                    remotePreviousReport = null
                    error = null
                    search = ""
                    chosenDecision = null
                    pageIndex = 0
                },
                enabled = !reading && !remoteBatchBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(if (expanded) "Close ${target.noun} restore comparison"
                    else "Compare saved snapshot with ${target.noun}")
            }
            if (expanded) {
                Text(if (onReviewOneRestore != null || onReviewPreviousRestore != null ||
                    remoteDefinitionRestore != null)
                    "Compare snapshot and review stopped restore"
                    else "Snapshot restore comparison (read-only)",
                    style = MaterialTheme.typography.titleMedium)
                Text("Compares a verified .chunksnap against every torrent in a " +
                    "fresh, complete ${target.observation} observation. It can identify " +
                    "missing definitions. An explicitly confirmed stopped restore adds " +
                    "only a stopped transfer definition: no downloaded payload " +
                    "files are migrated.")
                Text("Comparison leaves existing ${target.noun} transfers untouched. A match means " +
                    "the exact torrent identity already exists here; it does not " +
                    "prove its payload is complete or correctly seeded.",
                    style = MaterialTheme.typography.bodySmall)
                if (!targetReady) {
                    Text("${target.title} inventory is not yet confirmed, is stale, or " +
                        "${target.busyOperation} is in progress. Wait for a fresh full read.",
                        color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Current verified ${target.noun} queue: ${observedHashes.size} transfer " +
                        "identities. Comparisons are discarded if this list changes.",
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = {
                        reading = true
                        aliasNotice = null
                        priorityNotice = null
                        priorityReview = null
                        remotePriorityReview = null
                        result = null
                        verifiedSource = null
                        comparedHashes = null
                        manualNotice = null
                        remoteDefinitionReview = null
                        remoteBatchDraft = null
                        remoteBatchNotice = null
                        error = null
                        remotePreviousReport = null
                        open.launch(arrayOf("*/*"))
                    },
                    enabled = targetReady && !reading && !remoteDefinitionBusy && !remoteBatchBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) {
                    Text(if (reading) "Inspecting snapshot…"
                        else "Choose .chunksnap to compare")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                aliasNotice?.let {
                    Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                priorityNotice?.let {
                    Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                remoteDefinitionNotice?.let {
                    Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                remoteBatchNotice?.let {
                    Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                remotePreviousReport?.let { previous ->
                    Text("Previous rTorrent restore journals: ${previous.items.size} originals, " +
                        "${previous.verifiedCount} independently verified stopped at this Place, " +
                        "${previous.unresolvedCount} requiring individual investigation.",
                        style = MaterialTheme.typography.bodyMedium)
                    val grouped = previous.items.groupingBy { it.state }.eachCount()
                    val neverSubmitted = grouped[
                        SnapshotRemoteDefinitionRestore.PreviousState.NO_SUBMISSION_IN_THIS_JOURNAL] ?: 0
                    if (neverSubmitted > 0) Text("$neverSubmitted with no submission recorded in " +
                        "this exact journal. This does not authorize another Add.",
                        style = MaterialTheme.typography.bodySmall)
                    previous.items.filter {
                        it.state != SnapshotRemoteDefinitionRestore.PreviousState.VERIFIED_STOPPED
                    }.take(8).forEach { item ->
                        val explanation = when (item.state) {
                            SnapshotRemoteDefinitionRestore.PreviousState.VERIFIED_STOPPED -> "Verified stopped"
                            SnapshotRemoteDefinitionRestore.PreviousState.NO_SUBMISSION_IN_THIS_JOURNAL ->
                                "No Add recorded in this journal"
                            SnapshotRemoteDefinitionRestore.PreviousState.JOURNAL_UNAVAILABLE ->
                                "Journal unavailable; previous Add unknown"
                            SnapshotRemoteDefinitionRestore.PreviousState.DIFFERENT_REVIEWED_PLACE ->
                                "Previously reviewed Place differs"
                            SnapshotRemoteDefinitionRestore.PreviousState.UNRESOLVED ->
                                "No exact stopped/Place verification"
                        }
                        Text(item.name + " · " + item.hash.take(12) + ": " + explanation,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (previous.items.count {
                            it.state != SnapshotRemoteDefinitionRestore.PreviousState.VERIFIED_STOPPED
                        } > 8) Text("Additional unresolved journal entries are not shown. " +
                        "Use individual read-only checks for their identities.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Read-only on the remote client. This check did not submit, start or retry " +
                        "a torrent. Journal verification may update only Chunks-owned local receipts.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (remoteDefinitionRestore != null && remoteDefinitionFolderAccount != null &&
                    remoteDefinitionAccountLabel != null && target == SnapshotRestoreTarget.SERVER) {
                    Text(if (remoteSelectedPlace == null)
                        "Stopped restore uses this remote client's current default folder."
                    else "Selected restore folder: $remoteSelectedPlace. Chunks will check this " +
                        "exact remote folder again before restoring.",
                        style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { chooseRemotePlace = true },
                            enabled = !remoteDefinitionBusy && !remoteBatchBusy && !reading,
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Choose restore folder")
                        }
                        if (remoteSelectedPlace != null) OutlinedButton(onClick = {
                            remoteSelectedPlace = null
                            remoteDefinitionReview = null
                            remoteBatchDraft = null
                            remoteBatchNotice = null
                            remotePreviousReport = null
                            remoteDefinitionNotice = null
                        }, enabled = !remoteDefinitionBusy && !remoteBatchBusy && !reading,
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Use remote default")
                        }
                    }
                    if (chooseRemotePlace) RemoteFolderPicker(
                        remote = remoteDefinitionFolderAccount,
                        connectionName = remoteDefinitionAccountLabel,
                        initialFolder = remoteSelectedPlace,
                        onChoose = { selected ->
                            remoteSelectedPlace = selected
                            remoteDefinitionReview = null
                            remoteBatchDraft = null
                            remoteBatchNotice = null
                            remotePreviousReport = null
                            remoteDefinitionNotice = null
                            chooseRemotePlace = false
                        },
                        onDismiss = { chooseRemotePlace = false }
                    )
                }
                val bulkReview = remoteBatchDraft
                if (bulkReview != null && remoteBatchStoppedRestore != null &&
                    remoteDefinitionAccountLabel != null) {
                    RemoteSnapshotBatchStoppedConfirmation(
                        draft = bulkReview,
                        accountLabel = remoteDefinitionAccountLabel,
                        onCancel = { remoteBatchDraft = null },
                        onConfirm = {
                            remoteBatchDraft = null
                            if (!remoteBatchBusy && latestReady &&
                                latestHashes == comparedHashes &&
                                latestGeneration == comparedGeneration) {
                                remoteBatchBusy = true
                                remoteBatchNotice =
                                    "Checking all restore journals before stopped remote Adds…"
                                scope.launch {
                                    try {
                                        val report = remoteBatchStoppedRestore.execute(
                                            bulkReview, confirmed = true)
                                        remoteBatchNotice = if (report.complete) {
                                            "${report.verified}/${report.total} stopped remote " +
                                                "definitions independently verified. No Start or " +
                                                "payload copy. Refresh the comparison to review " +
                                                "saved priorities."
                                        } else {
                                            "${report.verified}/${report.total} definitions " +
                                                "verified before the group stopped" +
                                                (report.stoppedAtHash?.let {
                                                    " at infohash " + it.take(12)
                                                } ?: "") + ". Reason: " +
                                                (report.stop?.name ?: "unknown") +
                                                ". No automatic retry or rollback. Check previous " +
                                                "remote restores before considering another Add."
                                        }
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (_: Exception) {
                                        remoteBatchNotice =
                                            "The group outcome could not be verified. No automatic " +
                                                "retry. Check previous remote restores."
                                    } finally {
                                        remoteBatchBusy = false
                                    }
                                }
                            } else {
                                remoteBatchNotice = "The remote comparison changed. " +
                                    "No group submission was authorized."
                            }
                        }
                    )
                }
                val definitionReview = remoteDefinitionReview
                if (definitionReview != null && remoteDefinitionRestore != null &&
                    remoteDefinitionActionScope != null && remoteDefinitionAccountLabel != null) {
                    RemoteSnapshotDefinitionConfirmation(
                        review = definitionReview, accountLabel = remoteDefinitionAccountLabel,
                        onDismiss = { remoteDefinitionReview = null },
                        onConfirm = {
                            remoteDefinitionReview = null
                            if (!remoteDefinitionBusy && latestReady && latestHashes == comparedHashes &&
                                latestGeneration == comparedGeneration) {
                                remoteDefinitionBusy = true
                                remoteDefinitionNotice = if (definitionReview.recoveryOnly)
                                    "Checking the previous restore…" else "Restoring one stopped definition…"
                                remoteDefinitionActionScope.launch {
                                    try {
                                        val outcome = if (definitionReview.recoveryOnly)
                                            remoteDefinitionRestore.reconcile(definitionReview)
                                        else remoteDefinitionRestore.restore(definitionReview, confirmed = true)
                                        remoteDefinitionNotice = when (outcome) {
                                            SnapshotRemoteDefinitionRestore.Outcome.VERIFIED ->
                                                "Stopped at the reviewed Place. To restore saved priorities, " +
                                                    "compare this snapshot again and check the transfer’s current priorities."
                                            SnapshotRemoteDefinitionRestore.Outcome.OBSERVED_STOPPED ->
                                                "The exact transfer is stopped at the reviewed Place. " +
                                                    "The origin of the registration could not be confirmed."
                                            SnapshotRemoteDefinitionRestore.Outcome.STALE ->
                                                "The rTorrent account or comparison changed. Review again."
                                            SnapshotRemoteDefinitionRestore.Outcome.BLOCKED ->
                                                "No Add was sent by this action. Check a previous stopped " +
                                                    "restore or refresh the comparison and review again."
                                            SnapshotRemoteDefinitionRestore.Outcome.UNCERTAIN ->
                                                "Restore not verified. Compare again and check the previous " +
                                                    "stopped restore. Chunks did not retry the Add."
                                        }
                                    } finally {
                                        remoteDefinitionBusy = false
                                    }
                                }
                            }
                        }
                    )
                }
                priorityReview?.let { review ->
                    if (onApplyPriorities != null) LocalSnapshotPriorityConfirmation(
                        review = review,
                        onConfirm = {
                            priorityReview = null
                            remotePriorityReview = null
                            if (latestReady && latestHashes == comparedHashes &&
                                latestGeneration == comparedGeneration) {
                                priorityNotice = "Applying reviewed priorities…"
                                onApplyPriorities(review, comparedGeneration) { priorityNotice = it }
                            } else priorityNotice = "The Local inventory changed. Compare the snapshot again."
                        },
                        onDismiss = { priorityReview = null }
                    )
                }
                remotePriorityReview?.let { review ->
                    Text("Current rTorrent priorities independently checked. " +
                        "Changed: ${review.changeCount}; unsupported Low choices: ${review.unsupportedCount}. " +
                        "No remote priority was changed during review.",
                        style = MaterialTheme.typography.bodySmall)
                    val changes = review.rows.filter { it.saved != it.current }
                    changes.take(8).forEach { row ->
                        Text("#${row.file.index} ${row.file.path} · " +
                            "${row.current.label} → ${row.saved.label}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (changes.size > 8) Text(
                        "${changes.size - 8} additional file differences. None applied yet.",
                        style = MaterialTheme.typography.bodySmall)
                    if (onApplyRemotePriorities != null && remotePriorityEntry != null &&
                        review.changeCount > 0 && review.unsupportedCount == 0) {
                        OutlinedButton(onClick = { confirmRemotePriorities = true },
                            enabled = !remoteApplying && latestReady,
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Review applying ${review.changeCount} saved priorities")
                        }
                    }
                    val reviewedEntry = remotePriorityEntry
                    if (confirmRemotePriorities && reviewedEntry != null &&
                        onApplyRemotePriorities != null) {
                        RemoteSnapshotPriorityConfirmation(
                            entry = reviewedEntry, review = review,
                            onDismiss = { confirmRemotePriorities = false },
                            onConfirm = {
                                confirmRemotePriorities = false
                                remotePriorityReview = null
                                remotePriorityEntry = null
                                if (latestReady && latestHashes == comparedHashes &&
                                    latestGeneration == comparedGeneration && !remoteApplying) {
                                    remoteApplying = true
                                    priorityNotice = "Submitting confirmed rTorrent priorities…"
                                    onApplyRemotePriorities(reviewedEntry, review, comparedGeneration) { notice ->
                                        remoteApplying = false
                                        priorityNotice = notice
                                    }
                                } else {
                                    priorityNotice =
                                        "The rTorrent session inventory changed. Compare again."
                                }
                            }
                        )
                    }
                }
                result?.let { preview ->
                    // A sentence, not a heading: the readable sans (see ChunksTypography).
                    Text("Compared with ${preview.targetInventoryCount} current ${target.noun} transfers.",
                        style = MaterialTheme.typography.titleSmall)
                    Text("Already present: ${preview.alreadyPresentCount}. " +
                        "Missing definitions needing individual review: " +
                        "${preview.candidateCount}. " +
                        "Not recoverable from this file: ${preview.unavailableCount}.")
                    Text("This comparison made no changes. " +
                        "Separately confirmed file-priority or saved-alias actions " +
                        "can modify an already-present transfer.",
                        color = MaterialTheme.colorScheme.primary)
                    val batchReady = preview.entries.filter {
                        it.decision == SnapshotRestorePreviewV1.Decision.METADATA_NEEDS_REVIEW
                    }
                    val remoteMissingOriginals = batchReady.filter { candidate ->
                        verifiedSource?.manifest?.items?.any { original ->
                            original.infoHashV1 == candidate.infoHashV1 &&
                                original.recovery == SnapshotManifestV1.Recovery.METAINFO
                        } == true
                    }
                    if (remoteBatchStoppedRestore != null &&
                        remoteDefinitionAccountLabel != null &&
                        remoteMissingOriginals.size in 2..32) {
                        OutlinedButton(
                            onClick = {
                                val source = verifiedSource
                                val frozen = comparedHashes
                                val generation = comparedGeneration
                                val selected = remoteSelectedPlace
                                if (!remoteBatchBusy && !remoteDefinitionBusy &&
                                    source != null && frozen != null &&
                                    latestReady && latestHashes == frozen &&
                                    latestGeneration == generation) {
                                    remoteBatchDraft = null
                                    remoteBatchBusy = true
                                    remoteBatchNotice = "Preflighting every original, " +
                                        "the remote roster and all previous Add journals…"
                                    scope.launch {
                                        try {
                                            val draft = remoteBatchStoppedRestore.prepare(
                                                source,
                                                remoteMissingOriginals.map { it.infoHashV1 },
                                                frozen, selectedPlace = selected)
                                            if (verifiedSource === source && result === preview &&
                                                latestReady && latestHashes == frozen &&
                                                latestGeneration == generation &&
                                                remoteSelectedPlace == selected) {
                                                remoteBatchDraft = draft
                                                remoteBatchNotice = if (draft == null)
                                                    "Could not verify every original, journal, " +
                                                        "exact account and chosen folder. " +
                                                        "No remote Add was sent."
                                                else null
                                            }
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) {
                                            remoteBatchNotice =
                                                "Stopped batch review failed safely. " +
                                                    "No remote Add was sent."
                                        } finally {
                                            remoteBatchBusy = false
                                        }
                                    }
                                }
                            },
                            enabled = targetReady && !reading && !remoteBatchBusy &&
                                !remoteDefinitionBusy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        ) {
                            Text("Review ${remoteMissingOriginals.size} stopped remote restores")
                        }
                    } else if (remoteBatchStoppedRestore != null &&
                        remoteMissingOriginals.size > 32) {
                        Text("Stopped remote group restore is limited to 32 verified " +
                            "definitions per confirmation. Restore individual originals " +
                            "or compare a smaller snapshot.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (onReviewBatchRestore != null && batchReady.size >= 2) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    check(latestReady && latestHashes == comparedHashes &&
                                        latestGeneration == comparedGeneration) {
                                        "Current local inventory changed"
                                    }
                                    val verified = requireNotNull(verifiedSource) {
                                        "Verified snapshot has expired"
                                    }
                                    check(batchReady.all { item ->
                                        verified.manifest.items.any { source ->
                                            source.infoHashV1 == item.infoHashV1 &&
                                                source.recovery == SnapshotManifestV1.Recovery.METAINFO
                                        }
                                    })
                                    onReviewBatchRestore(
                                        verified, batchReady.toList(),
                                        requireNotNull(comparedHashes).toList(), comparedGeneration)
                                    manualNotice = "Batch is awaiting a separate Local Place " +
                                        "and explicit stopped-restore confirmation. Nothing was added."
                                    error = null
                                } catch (_: Exception) {
                                    manualNotice = null
                                    error = "Batch review could not be prepared. Refresh the " +
                                        "local inventory and compare the snapshot again."
                                }
                            },
                            enabled = targetReady && !reading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        ) { Text("Review ${batchReady.size} missing stopped restores") }
                    }
                    // Read-only recovery must include exact metainfo identities
                    // already present in the target: a previous batch may have
                    // verified some items before dying. This never selects them
                    // for another Add or creates a missing restore journal.
                    val previousCandidates = preview.entries.filter { item ->
                        item.decision in setOf(
                            SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT,
                            SnapshotRestorePreviewV1.Decision.METADATA_NEEDS_REVIEW
                        ) && verifiedSource?.manifest?.items?.any { original ->
                            original.infoHashV1 == item.infoHashV1 &&
                                original.recovery == SnapshotManifestV1.Recovery.METAINFO
                        } == true
                    }
                    if (onReviewBatchRecovery != null && previousCandidates.size >= 2) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    check(latestReady && latestHashes == comparedHashes &&
                                        latestGeneration == comparedGeneration) {
                                        "Current local inventory changed"
                                    }
                                    onReviewBatchRecovery(
                                        requireNotNull(verifiedSource),
                                        previousCandidates.toList(),
                                        requireNotNull(comparedHashes).toList(),
                                        comparedGeneration)
                                    manualNotice = "Previous group check is awaiting an explicit " +
                                        "read-only review of the original Local destination. " +
                                        "No transfer was changed."
                                    error = null
                                } catch (_: Exception) {
                                    manualNotice = null
                                    error = "The previous batch check could not be prepared. " +
                                        "Refresh the Local inventory and compare again."
                                }
                            },
                            enabled = targetReady && !reading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        ) { Text("Check ${previousCandidates.size} previous restores (read-only)") }
                    }
                    if (remoteDefinitionRestore != null && remoteDefinitionAccountLabel != null &&
                        previousCandidates.size >= 2) {
                        OutlinedButton(
                            onClick = {
                                val source = verifiedSource
                                val hashes = comparedHashes
                                val selected = remoteSelectedPlace
                                val generation = comparedGeneration
                                if (!remoteDefinitionBusy && source != null && hashes != null &&
                                    latestReady && latestHashes == hashes &&
                                    latestGeneration == generation) {
                                    remoteDefinitionReview = null
                                    remotePreviousReport = null
                                    remoteDefinitionBusy = true
                                    remoteDefinitionNotice =
                                        "Reading existing journals and current stopped remote transfers…"
                                    scope.launch {
                                        try {
                                            val report = remoteDefinitionRestore.inspectPreviousBatch(
                                                source, hashes, selectedPlace = selected)
                                            if (verifiedSource === source && result === preview &&
                                                latestReady && latestHashes == hashes &&
                                                latestGeneration == generation &&
                                                remoteSelectedPlace == selected) {
                                                remotePreviousReport = report
                                                remoteDefinitionNotice = if (report == null)
                                                    "Could not establish a complete, current authenticated " +
                                                        "remote and journal comparison. No Add was sent."
                                                else null
                                            }
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (_: Exception) {
                                            remoteDefinitionNotice =
                                                "Previous remote batch could not be verified. No Add was sent."
                                        } finally {
                                            remoteDefinitionBusy = false
                                        }
                                    }
                                }
                            },
                            enabled = targetReady && !reading && !remoteDefinitionBusy && !remoteBatchBusy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        ) { Text("Check ${previousCandidates.size} previous remote restores (read-only)") }
                    }
                    io.github.gamesbyian.chunks.ClearableTransferSearchField(
                        value = search,
                        onValueChange = { search = it; pageIndex = 0 },
                        label = "Search compared names or infohashes"
                    )
                    // Wrap so every decision filter is reachable without sideways scrolling.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            selected = chosenDecision == null,
                            onClick = { chosenDecision = null; pageIndex = 0 },
                            label = { Text("All") },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                        SnapshotRestorePreviewV1.Decision.entries.forEach { choice ->
                            FilterChip(
                                selected = chosenDecision == choice,
                                onClick = { chosenDecision = choice; pageIndex = 0 },
                                label = { Text(choice.name.replace('_', ' ').lowercase()) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                    manualNotice?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    if (preview.containsSettings) Text(
                        "Restore and Add actions below do not apply saved settings. " +
                            when {
                                onApplyPriorities != null ->
                                    "To apply priorities, review a stopped Local transfer separately."
                                onApplyRemotePriorities != null ->
                                    "To apply priorities, review the current files and confirm separately."
                                else -> "File priorities are shown for review."
                            },
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (onApplyAlias != null && target == SnapshotRestoreTarget.SERVER &&
                        preview.containsSettings) Text(
                        "Applying a saved display alias only changes this device's Chunks label. " +
                            "It never renames the remote torrent or any files.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    val shown = SnapshotRestoreCompareCatalogV1.select(
                        preview.entries, search, chosenDecision, pageIndex)
                    Text("Showing ${shown.first}–${shown.last} of ${shown.matches} " +
                        "matching records (${shown.total} compared).",
                        style = MaterialTheme.typography.bodySmall)
                    shown.entries.forEach { entry ->
                        val category = when (entry.decision) {
                            SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT ->
                                target.presentLabel
                            SnapshotRestorePreviewV1.Decision.METADATA_NEEDS_REVIEW ->
                                "Missing: verified torrent metadata needs Add review"
                            SnapshotRestorePreviewV1.Decision.MAGNET_NEEDS_REVIEW ->
                                "Missing: magnet needs metadata discovery and Add review"
                            SnapshotRestorePreviewV1.Decision.INVENTORY_ONLY ->
                                "Inventory only: no recovery definition"
                            SnapshotRestorePreviewV1.Decision.SOURCE_FAILED ->
                                "Source failed: no recovery definition"
                        }
                        Text(entry.originalName + " · " + category,
                            style = MaterialTheme.typography.bodySmall)
                        if (preview.containsSettings) {
                            if (entry.displayAlias != null || entry.fileMappings.isNotEmpty()) {
                                Text("Infohash ${entry.infoHashV1}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            SnapshotSettingsPreview(entry, target)
                            if (readRemotePriorityReview != null &&
                                entry.fileMappings.isNotEmpty() &&
                                entry.decision == SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT) {
                                OutlinedButton(
                                    onClick = {
                                        val generation = comparedGeneration
                                        remotePriorityReview = null
                                        remotePriorityEntry = null
                                        checkingPriorities = true
                                        priorityNotice = "Reading this remote client's current file priorities…"
                                        scope.launch {
                                            try {
                                                val checked = readRemotePriorityReview(entry)
                                                if (result !== preview) return@launch
                                                priorityNotice = when {
                                                    !latestReady || latestHashes != comparedHashes ||
                                                        latestGeneration != generation ->
                                                        "The remote inventory changed. Compare the snapshot again."
                                                    checked == null ->
                                                        "The exact remote transfer, original file layout or priorities could not be confirmed. No changes were made."
                                                    else -> {
                                                        remotePriorityReview = checked
                                                        remotePriorityEntry = entry
                                                        "Current rTorrent priorities checked. ${checked.changeCount} differ; " +
                                                            "${checked.unsupportedCount} saved Low choices are unsupported. " +
                                                            (if (onApplyRemotePriorities == null ||
                                                                checked.unsupportedCount > 0)
                                                                "This review cannot apply changes."
                                                            else if (checked.changeCount == 0)
                                                                "No changes are needed."
                                                            else "Apply only after separate confirmation.")
                                                    }
                                                }
                                            } catch (cancel: CancellationException) {
                                                throw cancel
                                            } catch (_: Exception) {
                                                if (result === preview)
                                                    priorityNotice = "Could not check this remote client's current file priorities."
                                            } finally {
                                                checkingPriorities = false
                                            }
                                        }
                                    },
                                    enabled = !checkingPriorities && !remoteApplying,
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) { Text("Check current rTorrent file priorities") }
                            }
                            if (readPriorityReview != null && onApplyPriorities != null &&
                                entry.fileMappings.isNotEmpty() &&
                                entry.decision == SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT) {
                                OutlinedButton(
                                    onClick = {
                                        val generation = comparedGeneration
                                        checkingPriorities = true
                                        priorityNotice = "Checking the stopped transfer’s files…"
                                        scope.launch {
                                            try {
                                                val review = readPriorityReview(entry)
                                                if (result !== preview) return@launch
                                                priorityNotice = when {
                                                    !latestReady || latestHashes != comparedHashes ||
                                                        latestGeneration != generation ->
                                                        "The Local inventory changed. Compare the snapshot again."
                                                    review == null ->
                                                        "Could not verify a stopped Local transfer with matching files and no partial-piece sidecar."
                                                    review.wouldReselectSkippedFile ->
                                                        "The snapshot would reselect skipped files. Use transfer Details to review those files."
                                                    review.changeCount == 0 ->
                                                        "The stopped Local transfer already has these file priorities."
                                                    else -> {
                                                        priorityReview = review
                                                        null
                                                    }
                                                }
                                            } catch (cancel: CancellationException) {
                                                throw cancel
                                            } catch (_: Exception) {
                                                if (result === preview)
                                                    priorityNotice = "Could not verify the stopped Local transfer’s files."
                                            } finally {
                                                checkingPriorities = false
                                            }
                                        }
                                    },
                                    enabled = !checkingPriorities,
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) { Text("Check Local files for priority restore") }
                            }

                            if (onApplyAlias != null && entry.displayAlias != null &&
                                entry.displayAlias != entry.originalName &&
                                entry.decision == SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT) {
                                OutlinedButton(
                                    onClick = {
                                        if (latestReady && latestHashes == comparedHashes &&
                                            latestGeneration == comparedGeneration) {
                                            aliasNotice = "Checking the ${target.title} display alias…"
                                            onApplyAlias(entry, comparedGeneration) { aliasNotice = it }
                                        } else {
                                            error = "The ${target.noun} inventory changed. Compare the snapshot again."
                                        }
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) { Text("Apply saved alias if this transfer has none") }
                            } else if (entry.displayAlias != null) {
                                Text("Alias application is unavailable for this target transfer.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        // Both present and missing snapshot identities may have
                        // an interrupted journal. Checking its status never
                        // authorizes another Add, even if inventory is absent.
                        if (entry.decision in setOf(
                                SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT,
                                SnapshotRestorePreviewV1.Decision.METADATA_NEEDS_REVIEW
                            ) && onReviewPreviousRestore != null &&
                            verifiedSource?.manifest?.items?.singleOrNull {
                                it.infoHashV1 == entry.infoHashV1
                            }?.recovery == SnapshotManifestV1.Recovery.METAINFO) {
                            OutlinedButton(
                                onClick = {
                                    try {
                                        check(latestReady &&
                                            latestHashes == comparedHashes &&
                                            latestGeneration == comparedGeneration) {
                                            "Current local inventory changed"
                                        }
                                        val source = requireNotNull(verifiedSource)
                                        SnapshotVerifiedTorrentDocumentV1.prepare(
                                            source, entry.infoHashV1)
                                        onReviewPreviousRestore(
                                            source, entry,
                                            requireNotNull(comparedHashes).toList(),
                                            comparedGeneration)
                                        manualNotice = "Previous restore check is ready for " +
                                            "a separate read-only destination review. No Add occurred."
                                        error = null
                                    } catch (_: Exception) {
                                        manualNotice = null
                                        error = "The previous restore check could not be prepared. " +
                                            "Refresh this snapshot comparison."
                                    }
                                },
                                enabled = targetReady && !reading,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) { Text("Check previous stopped restore") }
                        }
                        if (remoteDefinitionRestore != null && remoteDefinitionAccountLabel != null &&
                            entry.decision in setOf(
                                SnapshotRestorePreviewV1.Decision.ALREADY_PRESENT,
                                SnapshotRestorePreviewV1.Decision.METADATA_NEEDS_REVIEW
                            ) && verifiedSource?.manifest?.items?.any {
                                it.infoHashV1 == entry.infoHashV1 &&
                                    it.recovery == SnapshotManifestV1.Recovery.METAINFO
                            } == true) {
                            val canRestore = entry.decision ==
                                SnapshotRestorePreviewV1.Decision.METADATA_NEEDS_REVIEW
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val choices = if (canRestore) listOf(false, true) else listOf(true)
                                for (recoveryOnly in choices) {
                                    OutlinedButton(
                                        onClick = {
                                            val source = verifiedSource
                                            val hashes = comparedHashes
                                            if (!remoteDefinitionBusy && source != null && hashes != null &&
                                                latestReady && latestHashes == hashes &&
                                                latestGeneration == comparedGeneration) {
                                                remoteDefinitionBusy = true
                                                remoteDefinitionNotice = "Checking the rTorrent account and Place…"
                                                scope.launch {
                                                    try {
                                                        val review = remoteDefinitionRestore.review(
                                                            source, entry.infoHashV1, hashes, recoveryOnly,
                                                            selectedPlace = remoteSelectedPlace)
                                                        if (latestReady && latestHashes == hashes &&
                                                            latestGeneration == comparedGeneration &&
                                                            verifiedSource === source) {
                                                            remoteDefinitionReview = review
                                                            remoteDefinitionNotice = if (review == null)
                                                                "Could not verify this rTorrent account, stopped Add " +
                                                                    "capability, or selected remote folder. No Add was sent."
                                                            else null
                                                        }
                                                    } finally {
                                                        remoteDefinitionBusy = false
                                                    }
                                                }
                                            }
                                        },
                                        enabled = targetReady && !reading && !remoteDefinitionBusy && !remoteBatchBusy,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    ) {
                                        Text(if (recoveryOnly) "Check previous remote restore"
                                            else "Review stopped remote restore")
                                    }
                                }
                            }
                        }
                        if (entry.decision == SnapshotRestorePreviewV1.Decision.METADATA_NEEDS_REVIEW &&
                            onPrepareAdd != null) {
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val source = requireNotNull(verifiedSource) {
                                            "Verified snapshot has expired"
                                        }
                                        val originalBytes = SnapshotManualAddReviewV1.prepare(
                                            verified = source,
                                            reviewedEntry = entry,
                                            frozenTargetHashes = requireNotNull(comparedHashes),
                                            currentTargetHashes = latestHashes,
                                            frozenGeneration = comparedGeneration,
                                            currentGeneration = latestGeneration,
                                            completeFreshTarget = latestReady
                                        )
                                        onPrepareAdd(originalBytes)
                                        manualNotice = "Original torrent prepared for ${target.noun} Add review. " +
                                            "Choose its Place and confirm Add separately. No restore occurred."
                                        error = null
                                    } catch (failure: Exception) {
                                        manualNotice = null
                                        error = "Cannot prepare this torrent: " +
                                            (failure.message ?: "Snapshot or ${target.noun} inventory changed")
                                    }
                                },
                                enabled = targetReady && !reading,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) { Text(target.addLabel) }
                        }
                        if (entry.decision ==
                            SnapshotRestorePreviewV1.Decision.METADATA_NEEDS_REVIEW &&
                            onReviewOneRestore != null) {
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val source = requireNotNull(verifiedSource)
                                        SnapshotManualAddReviewV1.prepare(
                                            verified = source, reviewedEntry = entry,
                                            frozenTargetHashes = requireNotNull(comparedHashes),
                                            currentTargetHashes = latestHashes,
                                            frozenGeneration = comparedGeneration,
                                            currentGeneration = latestGeneration,
                                            completeFreshTarget = latestReady
                                        )
                                        onReviewOneRestore(source, entry,
                                            requireNotNull(comparedHashes).toList(),
                                            comparedGeneration)
                                        manualNotice = "Stopped restore is awaiting a separate " +
                                            "destination and action review. Nothing was added."
                                        error = null
                                    } catch (_: Exception) {
                                        manualNotice = null
                                        error = "Restore preparation failed: reopen this snapshot " +
                                            "after refreshing the complete target inventory."
                                    }
                                },
                                enabled = targetReady && !reading,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) { Text("Review journalled stopped restore") }
                        }
                        if (entry.decision == SnapshotRestorePreviewV1.Decision.MAGNET_NEEDS_REVIEW &&
                            onPrepareMagnet != null) {
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val link = SnapshotManualAddReviewV1.prepareMagnet(
                                            verified = requireNotNull(verifiedSource) {
                                                "Verified snapshot has expired"
                                            },
                                            reviewedEntry = entry,
                                            frozenTargetHashes = requireNotNull(comparedHashes),
                                            currentTargetHashes = latestHashes,
                                            frozenGeneration = comparedGeneration,
                                            currentGeneration = latestGeneration,
                                            completeFreshTarget = latestReady
                                        )
                                        onPrepareMagnet(link)
                                        manualNotice = "Magnet prepared for ${target.noun} Add review. " +
                                            "Confirm Add separately. No restore occurred."
                                        error = null
                                    } catch (failure: Exception) {
                                        manualNotice = null
                                        error = "Cannot prepare this magnet: " +
                                            (failure.message ?: "Snapshot or ${target.noun} inventory changed")
                                    }
                                },
                                enabled = targetReady && !reading,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) { Text("Review magnet in ${target.noun} Add") }
                        }
                    }
                    if (shown.entries.isEmpty()) {
                        Text("No records match these filters. No restore action was attempted.")
                    }
                    if (shown.hasPrevious || shown.hasNext) {
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { pageIndex = (shown.page - 1).coerceAtLeast(0) },
                                enabled = shown.hasPrevious,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) { Text("Previous comparisons") }
                            OutlinedButton(
                                onClick = { pageIndex = shown.page + 1 },
                                enabled = shown.hasNext,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) { Text("Next comparisons") }
                        }
                    }
                    Text("Next steps are explicit Place remapping, a durable per-item " +
                        "import journal and exact-hash readback. A preview alone " +
                        "cannot authorize restoration.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
