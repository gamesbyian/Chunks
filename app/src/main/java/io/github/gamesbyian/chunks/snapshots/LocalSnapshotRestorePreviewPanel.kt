package io.github.gamesbyian.chunks.snapshots

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import java.util.Locale
import kotlinx.coroutines.CancellationException
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
    onPrepareAdd: ((ByteArray) -> Unit)? = null
) = SnapshotRestorePreviewPanel(
    targetHashes = transfers.map { it.infoHash },
    target = SnapshotRestoreTarget.PHONE,
    inventoryConfirmed = inventoryConfirmed,
    inventoryStale = inventoryStale,
    busyEngine = busyEngine,
    mutationGeneration = mutationGeneration,
    onPrepareAdd = onPrepareAdd
)

/** Wording for the engine a snapshot is compared against. */
internal data class SnapshotRestoreTarget(
    val noun: String,
    val title: String,
    val observation: String,
    val busyOperation: String,
    val presentLabel: String,
    val addLabel: String
) {
    companion object {
        val PHONE = SnapshotRestoreTarget("phone", "Phone", "phone-engine",
            "a native operation", "Already on phone (skip)",
            "Review original .torrent in phone Add")
        val SERVER = SnapshotRestoreTarget("rTorrent server", "rTorrent server",
            "rTorrent server (all-transfers view)", "a server operation",
            "Already on this server (skip)", "Review original .torrent in server Add")
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
    onPrepareMagnet: ((String) -> Unit)? = null
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
    var pageIndex by remember { mutableIntStateOf(0) }

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
                    result = null
                    verifiedSource = null
                    comparedHashes = null
                    manualNotice = null
                    error = null
                    search = ""
                    chosenDecision = null
                    pageIndex = 0
                },
                enabled = !reading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Text(if (expanded) "Close ${target.noun} restore comparison"
                    else "Compare saved snapshot with ${target.noun}")
            }
            if (expanded) {
                Text("Snapshot restore comparison (read-only)",
                    style = MaterialTheme.typography.titleMedium)
                Text("Compares a verified .chunksnap against every torrent in a " +
                    "fresh, complete ${target.observation} observation. It can identify missing " +
                    "torrent definitions, but cannot restore data or move downloaded files.")
                Text("The existing ${target.noun} transfers stay untouched. A match means " +
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
                        result = null
                        verifiedSource = null
                        comparedHashes = null
                        manualNotice = null
                        error = null
                        open.launch(arrayOf("*/*"))
                    },
                    enabled = targetReady && !reading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) {
                    Text(if (reading) "Inspecting snapshot…"
                        else "Choose .chunksnap to compare")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                result?.let { preview ->
                    Text("Compared with ${preview.targetInventoryCount} current ${target.noun} transfers.",
                        style = MaterialTheme.typography.titleMedium)
                    Text("Already present: ${preview.alreadyPresentCount}. " +
                        "Missing definitions needing individual review: " +
                        "${preview.candidateCount}. " +
                        "Not recoverable from this file: ${preview.unavailableCount}.")
                    Text("No destination was selected. No torrent was added, started, " +
                        "stopped, deleted, downloaded or otherwise changed.",
                        color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it; pageIndex = 0 },
                        label = { Text("Search compared names or infohashes") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
