package io.github.gamesbyian.chunks

import android.app.Activity
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentCapabilities
import io.github.gamesbyian.chunks.model.EnvironmentCapabilitySnapshot
import io.github.gamesbyian.chunks.model.EnvironmentOperation
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.EnvironmentViewScope
import io.github.gamesbyian.chunks.model.LocalInventoryFrame
import io.github.gamesbyian.chunks.model.TransferSortOrdering
import io.github.gamesbyian.chunks.model.UnifiedSortKey
import io.github.gamesbyian.chunks.model.UnifiedTransferFilter
import io.github.gamesbyian.chunks.model.UnifiedTransferCatalog
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import io.github.gamesbyian.chunks.model.Units
import io.github.gamesbyian.chunks.privacy.RemoteTransferPrivacyScope
import io.github.gamesbyian.chunks.privacy.TransferPrivacyRuntime
import io.github.gamesbyian.chunks.privacy.TransferDeviceAuthenticator
import io.github.gamesbyian.chunks.privacy.TransferPrivacyPolicyStore
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.rpc.RemoteStallEvidence
import io.github.gamesbyian.chunks.model.LifecycleReply
import io.github.gamesbyian.chunks.model.UnifiedBatchLifecycle
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferDetails
import io.github.gamesbyian.chunks.model.TransferDetailReadState
import io.github.gamesbyian.chunks.model.describe
import io.github.gamesbyian.chunks.model.toLifecycleReply
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import io.github.gamesbyian.chunks.model.EnvironmentHue
import io.github.gamesbyian.chunks.model.EnvironmentPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

// A2611-02: the Combined screen's row, batch and text helpers, moved unchanged out of CombinedTransfersScreen.kt.
/** Locate the selected exact-job batch review after variable disclosure items.
 * Changes in the leading LazyColumn item order must update this helper and test.
 */
internal fun combinedBatchItemIndex(
    addVisible: Boolean,
    filterOptionsVisible: Boolean,
    sortOptionsVisible: Boolean,
    headerVisible: Boolean = true
): Int = 2 + (if (headerVisible) 1 else 0) + (if (addVisible) 1 else 0) +
    (if (filterOptionsVisible) 3 else 0) +
    (if (sortOptionsVisible) 1 else 0)

/** A confirmed empty engine is distinct from a pending or locked read. */
internal fun localOnlyEmptyInventoryMessage(confirmedCurrent: Boolean): String =
    if (confirmedCurrent) "No transfers on this device yet. Review a torrent to add one."
    else "No verified transfer list is available yet. Saved transfers restore stopped " +
        "when Local Device access is available."

/** Unconditional leading items: header (0), Add destinations (1), batch (2), view (3). */
internal const val COMBINED_ADD_ITEM_KEY = "combined-add-destinations"
internal const val COMBINED_ADD_ITEM_INDEX = 1
// The first item is the heading; the second is Add choices. The filter
// disclosure is the *third* item (zero-based index 2), before search.
// Scrolling to 3 can leave the just-opened filters clipped at 320dp/200%.
internal const val COMBINED_VIEW_ITEM_INDEX = 2

/**
 * Save only the exact read-only detail identity across rotation/process recreation.
 * A malformed or obsolete saved value is discarded; command selection is not restored.
 */
internal fun restoreCombinedDetailTarget(saved: List<String>?): UnifiedTransferKey? =
    saved?.takeIf { it.size == 2 }?.let { parts ->
        runCatching { UnifiedTransferKey.of(EnvironmentId(parts[0]), parts[1]) }.getOrNull()
    }

/**
 * The persistent environment toggle row: every available environment is shown under All;
 * tapping one hides or shows only that environment. Re-selecting every available one returns
 * to dynamic All (so a newly added connection joins it); hiding all is an explicit empty view.
 */
internal fun toggleEnvironmentVisibility(
    scope: EnvironmentViewScope, id: EnvironmentId, available: Set<EnvironmentId>
): EnvironmentViewScope {
    val visible = when (scope) {
        EnvironmentViewScope.All -> available
        is EnvironmentViewScope.Only -> scope.ids
    }
    val next = if (id in visible) visible - id else visible + id
    // A compact chip row is a reversible multi-select, never a blank catalog
    // with all sources implicitly disabled. Retain the final visible source.
    if (next.isEmpty()) return scope
    return if (available.isNotEmpty() && next.containsAll(available)) EnvironmentViewScope.All
        else EnvironmentViewScope.Only(next)
}

/** The global scope chip adds value only with at least four configured environments. */
internal fun shouldShowAllEnvironmentChip(configuredCount: Int): Boolean = configuredCount > 3

/** Compact chip count; unavailable or privacy-locked inventories never masquerade as zero. */
internal fun environmentScopeCount(source: EnvironmentTransferSnapshot?): String = when {
    source == null || source.availability == EnvironmentAvailability.LOCKED -> "?"
    !source.inventoryComplete -> if (source.transfers.isEmpty()) "?" else "${source.transfers.size}+"
    else -> source.transfers.size.toString()
}

/** A confident (automatic or person-chosen) media identity suggests "Title (Year)". */
internal fun identifiedMediaNameSuggestions(
    assignment: io.github.gamesbyian.chunks.media.TransferMediaAssignment?
): List<io.github.gamesbyian.chunks.model.DisplayNameSuggestion> {
    val confident = assignment?.takeIf {
        it.state == io.github.gamesbyian.chunks.media.MediaAssignmentState.AUTOMATIC ||
            it.state == io.github.gamesbyian.chunks.media.MediaAssignmentState.CHOSEN_BY_USER
    }?.match ?: return emptyList()
    val title = confident.title.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
    val episode = if (confident.season != null && confident.episode != null)
        " S" + confident.season.toString().padStart(2, '0') + "E" + confident.episode.toString().padStart(2, '0')
        else ""
    val name = title + episode + (confident.year?.let { " ($it)" } ?: "")
    return listOf(io.github.gamesbyian.chunks.model.DisplayNameSuggestion(name,
        "Identified media (" + confident.attribution.providerName + ")"))
}

/**
 * rTorrent names a magnet that is still fetching its metadata `<infohash>.meta`. That is a
 * legitimate state, so it is shown, but as a short readable title instead of 45 characters
 * of hex; the full name stays in the accessible label and Details.
 */
internal val META_PLACEHOLDER = Regex("(?i)^([0-9a-f]{40})\\.meta$")
internal val V1_TRANSFER_HASH = Regex("[0-9a-f]{40}")

internal fun compactTransferTitle(name: String): String {
    val meta = META_PLACEHOLDER.matchEntire(name.trim()) ?: return name
    return "Magnet " + meta.groupValues[1].take(8).lowercase() + "… (fetching metadata)"
}

internal fun rowKeyText(key: UnifiedTransferKey): String = key.environment.value + "|" + key.infoHash

/** Compact toggle text: the device, or a server's short profile name without its prefix. */
internal fun environmentToggleName(id: EnvironmentId, label: String): String =
    if (id == EnvironmentSnapshots.LOCAL) "Local"
    else label.removePrefix("Remote · ").trim().ifEmpty { label }

internal fun environmentVisible(scope: EnvironmentViewScope, id: EnvironmentId): Boolean =
    scope == EnvironmentViewScope.All || (scope is EnvironmentViewScope.Only && id in scope.ids)

/** All → only the tapped environment; otherwise toggle, and an emptied subset returns to All. */
internal fun toggleScope(scope: EnvironmentViewScope, id: EnvironmentId): EnvironmentViewScope = when (scope) {
    EnvironmentViewScope.All -> EnvironmentViewScope.Only(setOf(id))
    is EnvironmentViewScope.Only -> {
        val next = if (id in scope.ids) scope.ids - id else scope.ids + id
        if (next.isEmpty()) EnvironmentViewScope.All else EnvironmentViewScope.Only(next)
    }
}

/** Estimate only for a started, size-known, incomplete row with a reported rate. */
internal fun combinedTimeLeft(row: UnifiedTransferObservation): String? {
    if (row.started != true || row.metadataPending == true) return null
    val size = row.sizeBytes ?: return null
    val ppm = row.progressPpm ?: return null
    val remaining = size - (size.toDouble() * ppm / 1_000_000).toLong()
    return Units.timeLeft(remaining, row.downloadBytesPerSecond)
}

/** Text summary of one row; unknown facts are said as unknown, never as zero. */
internal fun combinedRowFacts(row: UnifiedTransferObservation, fresh: Boolean = false): String = listOf(
    // Same words as the local device and rTorrent rows, from observed facts only.
    if (row.metadataPending == true) "Fetching metadata" else when (row.started) {
        true -> when {
            // Existing exact-path bytes are being hash-verified before any download
            // (FOREIGN-PARTIAL-TORRENT-RECOVERY P0); the percentage is check progress.
            row.checkingExistingData == true && row.stopsAfterCheck == true -> CHECKING_STAYS_STOPPED
            row.checkingExistingData == true -> "Checking existing data"
            row.progressPpm == 1_000_000 -> "Sharing"
            fresh && (row.downloadBytesPerSecond ?: 0L) > 0L -> "Downloading"
            else -> "Started"
        }
        false -> "Stopped"
        null -> "State unknown"
    },
    row.progressPpm?.let { "${it / 10_000}%" } ?: "Progress unknown",
    row.sizeBytes?.let { Units.bytes(it) } ?: "Size unknown",
    "↓ " + (row.downloadBytesPerSecond?.let { Units.bytes(it) + "/s" } ?: "not reported"),
    "↑ " + (row.uploadBytesPerSecond?.let { Units.bytes(it) + "/s" } ?: "not reported")
).plus(listOfNotNull(combinedTimeLeft(row).takeIf { fresh })).plus(row.ratioThousandths?.let {
    listOf("Ratio " + String.format(java.util.Locale.US, "%.2f",
        it.toDouble() / 1_000.0))
} ?: emptyList()).joinToString(" · ")

/**
 * The permanently visible row states only the facts useful while scanning.
 * Size/ETA and other details are disclosed by tapping the row. Unknown
 * values remain explicitly unknown; the progress bar supplies the visual.
 */
internal fun combinedCompactRowFacts(row: UnifiedTransferObservation, fresh: Boolean = false): String = listOf(
    if (row.metadataPending == true) "Fetching metadata" else when (row.started) {
        true -> when {
            // Existing exact-path bytes are being hash-verified before any download
            // (FOREIGN-PARTIAL-TORRENT-RECOVERY P0); the percentage is check progress.
            row.checkingExistingData == true && row.stopsAfterCheck == true -> CHECKING_STAYS_STOPPED
            row.checkingExistingData == true -> "Checking existing data"
            row.progressPpm == 1_000_000 -> "Sharing"
            fresh && (row.downloadBytesPerSecond ?: 0L) > 0L -> "Downloading"
            else -> "Started"
        }
        false -> "Stopped"
        null -> "State unknown"
    },
    row.progressPpm?.let { "${it / 10_000}%" } ?: "Progress unknown",
    "↓ " + (row.downloadBytesPerSecond?.let { Units.bytes(it) + "/s" } ?: "not reported"),
    "↑ " + (row.uploadBytesPerSecond?.let { Units.bytes(it) + "/s" } ?: "not reported"),
    row.ratioThousandths?.let {
        "Ratio " + String.format(java.util.Locale.US, "%.2f", it.toDouble() / 1_000.0)
    } ?: "Ratio not reported"
).joinToString(" · ")

@Composable
internal fun CombinedTransferRow(
    row: UnifiedTransferObservation,
    environmentLabel: String,
    hue: EnvironmentHue?,
    /** Compact unique origin shown in the row; spoken actions keep [environmentLabel]. */
    visibleEnvironmentLabel: String? = null,
    source: EnvironmentTransferSnapshot?,
    /** Null when not selecting. */
    selection: Boolean? = null,
    onSelect: (Boolean) -> Unit = {},
    /** Read-only details are available even for saved, unsigned-in profiles. */
    onDetails: () -> Unit,
    /** Tapping the row body expands it in place; Details stays one tap away. */
    expanded: Boolean = false,
    onToggleExpanded: (() -> Unit)? = null,
    /** Quick operations shown only while expanded (Start/Stop through the canonical command path). */
    quickControls: (@Composable () -> Unit)? = null
) {
    // The compact row expands in place to expose quick controls. Its header Details
    // button opens the one full Details destination for the exact environment/hash.
    // One resolved hue drives rail, dot and text. The neutral fallback has
    // readable contrast on Surface and avoids a grey rail with white label.
    val accent = hue?.let { Color(it.argb) } ?: ChunksColors.TextSecondary
    val labelColour = accent
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(12.dp))
        .background(ChunksColors.Surface)) {
        // Colour is supplementary: the exact environment name is always present.
        Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
        if (selection != null) Checkbox(checked = selection, onCheckedChange = onSelect,
            modifier = Modifier.padding(start = 4.dp).align(Alignment.CenterVertically).semantics {
                contentDescription = "Select ${row.displayName} on $environmentLabel"
            })
        val toggle = onToggleExpanded ?: onDetails
        val detailsTag = "${ChunksSemantics.TRANSFERS_ROW_DETAILS}.${row.key.environment.value}.${row.key.infoHash}"
        val rowDetailsTag = if (onToggleExpanded == null) Modifier.testTag(detailsTag) else Modifier
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)
            .then(rowDetailsTag)
            .clickable(role = Role.Button,
                onClickLabel = if (onToggleExpanded == null) "Open full transfer details"
                    else if (expanded) "Collapse transfer" else "Expand transfer controls",
                onClick = toggle)
            .semantics {
                contentDescription = if (onToggleExpanded == null)
                    "Details for ${row.displayName} on $environmentLabel"
                    else "${row.displayName} on $environmentLabel"
                if (onToggleExpanded != null) stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Intrinsic-height rows cannot query a BoxWithConstraints/SubcomposeLayout.
            // Observe only the available horizontal size, leaving the source-colour rail
            // and right-column intrinsic height free of unsupported measurements.
            val density = LocalDensity.current
            var measuredWidth by remember { mutableStateOf(0.dp) }
            val artMaxWidth = if (measuredWidth > 0.dp)
                minOf(measuredWidth * 0.32f, 112.dp,
                    (measuredWidth - 164.dp).coerceAtLeast(48.dp))
            else 96.dp
            Row(Modifier.fillMaxWidth().onSizeChanged {
                    measuredWidth = with(density) { it.width.toDp() }
                }, verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransferRowArtwork(row.key, row.originalName,
                        modifier = Modifier.testTag("combined-card-artwork"),
                        hidden = source?.availability == EnvironmentAvailability.LOCKED,
                        height = if (expanded) 132.dp else 108.dp,
                        maxWidth = artMaxWidth)
                    Column(Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                EnvironmentDot(hue, size = 8)
                // Short profile name beside its colour; the exact server identity stays in
                // the spoken name, Details and Connections.
                Text(environmentToggleName(row.key.environment, visibleEnvironmentLabel ?: environmentLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColour, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 6.dp))
                if (onToggleExpanded == null) Text("Details", style = MaterialTheme.typography.labelSmall,
                    color = ChunksColors.TextSecondary)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(compactTransferTitle(row.displayName), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = row.displayName
                    })
                // A nested control in the title line (no extra row height): opening Details
                // never toggles the row.
                if (onToggleExpanded != null) androidx.compose.material3.TextButton(onClick = onDetails,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag(detailsTag).semantics {
                        contentDescription = "Details for ${row.displayName} on $environmentLabel"
                    }) { Text("Details", style = MaterialTheme.typography.labelMedium) }
            }
            // A time estimate needs a live, complete source; last-known facts never get one.
            Text(combinedCompactRowFacts(row, fresh = source?.canClaimCompleteLiveInventory == true),
                style = MaterialTheme.typography.bodySmall)
            row.progressPpm?.let { ppm ->
                ChunkedProgressBar(
                    progress = ppm / 1_000_000f,
                    modifier = Modifier.fillMaxWidth().testTag("combined-card-progress"),
                    historyKey = ProgressChunkStore.identity(
                        row.key.environment.value, row.key.infoHash),
                    observeFresh = source?.canClaimCompleteLiveInventory == true,
                    observationEpochMillis = source?.observedAtEpochMillis,
                    // Same colour meaning as the local device and rTorrent cards: green complete, paused stopped.
                    tone = combinedProgressTone(row)
                )
            }
            combinedRowFreshnessLabel(source)?.let { warning ->
                Text(warning, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (row.hasError) Text("Needs attention in its own list",
                color = ChunksColors.Red, style = MaterialTheme.typography.bodySmall)
            if (expanded) quickControls?.invoke()
                    }
                }
        }
    }
}

/**
 * Runs in stable order (environment ID, then hash), not tap order.
 * Leases each selected environment's own mutation gate, runs the pure batch
 * on exactly those adapters, then settles every lease it took. An environment
 * whose gate is busy, awaiting a readback or signed out refuses its items.
 */
internal suspend fun runCombinedLifecycle(
    chosen: Set<UnifiedTransferKey>,
    start: Boolean,
    sources: List<EnvironmentTransferSnapshot>,
    serverLifecycle: CombinedServerLifecycle?,
    serverId: EnvironmentId?,
    /** UI callers must provide the exact authenticated source for command ownership. */
    serverSource: CombinedServerSource? = null,
    localEngine: () -> io.github.gamesbyian.chunks.engine.LocalTorrentEngine? = { LocalEngineHolder.current() },
    nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    /** Old session, privacy or saved-profile review cannot authorize another mutation. */
    reviewStillCurrent: () -> Boolean = { true },
    /** Local Stop can be verified while its independent recovery save is still uncertain. */
    onLocalStopCheckpointUnconfirmed: (String) -> Unit = {}
): UnifiedBatchLifecycle.Report {
    // Confirmation can arrive between clock ticks, after the displayed source
    // expired. Re-evaluate it before acquiring any command lane.
    val currentTime = nowEpochMillis()
    val currentSources = sources.map { source ->
        if (source.environment != EnvironmentSnapshots.LOCAL &&
            source.availability == EnvironmentAvailability.LIVE &&
            EnvironmentSnapshots.serverInventoryIsOutOfDate(source.observedAtEpochMillis, currentTime))
            source.copy(availability = EnvironmentAvailability.STALE) else source
    }
    val byKey = currentSources.flatMap { it.transfers }.associateBy { it.key }
    val targets = chosen.sortedWith(compareBy({ it.environment.value }, { it.infoHash }))
        .map { UnifiedBatchLifecycle.Target(it, byKey[it]?.started) }
    // rTorrent supports v1 40-hex hashes. This is a local pre-dispatch
    // refusal, distinct from NOT_FOUND after an actual remote mutation/readback.
    // Key by full Environment+Hash so another account is never implicated.
    val unsupportedServerTargets = targets
        .filter { it.key.environment == serverId &&
            !it.key.infoHash.matches(Regex("[0-9a-f]{40}")) }
        .associate { it.key to "Unsupported rTorrent infohash format; no command was sent" }
    val adapters = HashMap<EnvironmentId, UnifiedBatchLifecycle.Adapter>()
    val refusals = HashMap<EnvironmentId, String>()
    var localLeased = false
    var serverLeased = false
    // Start this monotonic budget with the FIRST completed local Stop, not
    // before slow remote capability checks or unrelated account operations.
    var localStopBudget: io.github.gamesbyian.chunks.model.LocalStoppedCheckpointBudget? = null
    val expiredReview = "Reviewed session, privacy or profile scope changed; select transfers again"
    try {
        // An unavailable, incomplete or unobserved inventory cannot authorize
        // a batch, so never even acquire its engine's mutation gate. The pure
        // batch still returns an explicit per-transfer refusal.
        val actionable = currentSources.filter { it.canClaimCompleteLiveInventory }
            .map { it.environment }.toSet()
        for (environment in targets.map { it.key.environment }.toSet()) {
            if (environment !in actionable) continue
            if (!reviewStillCurrent()) {
                refusals[environment] = expiredReview
                continue
            }
            if (environment == EnvironmentSnapshots.LOCAL) {
                val engine = localEngine()
                when {
                    engine == null -> refusals[environment] = "local engine is not running"
                    !LocalMutationFence.revision.beginMutation() ->
                        refusals[environment] = "waiting for a fresh local list"
                    else -> {
                        localLeased = true
                        adapters[environment] = UnifiedBatchLifecycle.Adapter { hash, on ->
                            withContext(Dispatchers.IO) {
                                val outcome = (if (on) engine.resume(hash) else engine.pause(hash)).outcome
                                if (!on && outcome == io.github.gamesbyian.chunks.engine.LocalCommandOutcome.VERIFIED) {
                                    val budget = localStopBudget
                                        ?: io.github.gamesbyian.chunks.model.LocalStoppedCheckpointBudget()
                                            .also { localStopBudget = it }
                                    if (!budget.confirm(hash, engine::saveResumeDataFor))
                                        onLocalStopCheckpointUnconfirmed(hash)
                                }
                                // Failed persistence never changes a VERIFIED Stop into an
                                // unknown command or invites replay of that command.
                                outcome.toLifecycleReply()
                            }
                        }
                    }
                }
            } else if (environment == serverId && serverLifecycle != null) {
                // Pure lifecycle unit fixtures have no real authenticated adapter.
                // Production always supplies serverSource with a bound RPC owner.
                // Even duplicate valid hashes on other numbered routes are refused.
                val bound = serverLifecycle.ownerSession
                val requiresIdentity = serverSource != null || bound != null
                fun ownerMatches(): Boolean = !requiresIdentity ||
                    (bound != null && serverSource?.lifecycle === serverLifecycle &&
                        verifiedCombinedLifecycleOwner(serverSource, serverId) === bound)
                if (!ownerMatches()) {
                    refusals[environment] =
                        "Authenticated rTorrent account does not match this transfer; reconnect and review"
                    continue
                }
                // Capability RPC must use that exact owner, never a crosswired
                // callback that may have captured a foreign session.
                val exactProbe: (suspend () -> io.github.gamesbyian.chunks.rpc.RemoteProbe)? =
                    if (bound == null) serverLifecycle.probe else ({ bound.probe() })
                val capability = serverCapabilities(environment, exactProbe)
                    .decide(EnvironmentOperation.START_STOP, EnvironmentAvailability.LIVE)
                val source = currentSources.first { it.environment == environment }
                // The read-only method probe can suspend while the user changes
                // accounts or locks privacy. Check again before taking a lease.
                if (!reviewStillCurrent())
                    refusals[environment] = expiredReview
                else if (!ownerMatches())
                    refusals[environment] =
                        "Authenticated rTorrent account changed during capability check; review again"
                else if (EnvironmentSnapshots.serverInventoryIsOutOfDate(source.observedAtEpochMillis, nowEpochMillis()))
                    refusals[environment] = "Transfer list is out of date; refresh first"
                else if (!capability.allowed) refusals[environment] = capability.explanation
                else if (!serverLifecycle.begin()) refusals[environment] = "remote client is busy or refreshing"
                else {
                    serverLeased = true
                    adapters[environment] = UnifiedBatchLifecycle.Adapter { hash, on ->
                        // rTorrent identities are v1 only: refuse before any RPC rather than
                        // letting a local validation failure read as an uncertain mutation.
                        if (!hash.matches(Regex("[0-9a-f]{40}")) ||
                            !reviewStillCurrent() || !ownerMatches()) LifecycleReply.REFUSED
                        else (if (bound == null) serverLifecycle.setStarted(hash, on)
                            else bound.setStarted(hash, on)).toLifecycleReply(on)
                    }
                }
            } else refusals[environment] = "only listed here; sign in to this remote client to change it"
        }
        return UnifiedBatchLifecycle.run(targets, start, currentSources, adapters, refusals,
            // Check at each target, including no-ops, after any previous
            // command or read-only probe may have suspended. A write already
            // sent keeps its own reported result, but no later write escapes
            // a retired session/selection/privacy review.
            sourceIsFresh = { source -> reviewStillCurrent() &&
                (source.environment != serverId || serverSource == null ||
                    verifiedCombinedLifecycleOwner(serverSource, serverId) ===
                        serverLifecycle?.ownerSession) &&
                (source.environment == EnvironmentSnapshots.LOCAL ||
                    !EnvironmentSnapshots.serverInventoryIsOutOfDate(source.observedAtEpochMillis, nowEpochMillis())) },
            preflightRefusals = unsupportedServerTargets)
    } finally {
        if (localLeased) LocalMutationFence.revision.settledMutation()
        if (serverLeased) serverLifecycle?.settled?.invoke()
    }
}

internal suspend fun serverCapabilities(
    environment: EnvironmentId,
    probe: (suspend () -> io.github.gamesbyian.chunks.rpc.RemoteProbe)?
): EnvironmentCapabilitySnapshot {
    if (probe == null) return EnvironmentCapabilities.unprobed(environment)
    return try {
        EnvironmentCapabilities.fromProbe(environment, probe(), System.currentTimeMillis())
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (_: Exception) {
        EnvironmentCapabilities.unprobed(environment)
    }
}

@Composable
internal fun CombinedBatchSection(
    selecting: Boolean,
    onSelecting: (Boolean) -> Unit,
    selected: Set<UnifiedTransferKey>,
    visible: Set<UnifiedTransferKey>,
    labels: Map<EnvironmentId, String>,
    running: Boolean,
    result: List<String>,
    onClear: () -> Unit,
    onRun: (Boolean) -> Unit
) {
    var confirming by remember { mutableStateOf<Boolean?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onSelecting(!selecting); confirming = null }, enabled = !running,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(if (selecting) "Done selecting" else "Select…") }
        CombinedBatchFeedback(running = running, outcomes = result)
        if (!selecting || selected.isEmpty()) return@Column
        val hidden = (selected - visible).size
        Text("${selected.size} selected" + (if (hidden > 0) ", $hidden not shown by the current view" else ""),
            style = MaterialTheme.typography.titleSmall)
        ResponsiveActionGroup { actionModifier ->
            OutlinedButton(onClick = { confirming = true }, enabled = !running,
                modifier = actionModifier) { Text("Start…") }
            OutlinedButton(onClick = { confirming = false }, enabled = !running,
                modifier = actionModifier) { Text("Stop…") }
            OutlinedButton(onClick = { onClear(); confirming = null }, enabled = !running,
                modifier = actionModifier) { Text("Clear") }
        }
        val wanted = confirming ?: return@Column
        Text(combinedBatchPreview(wanted, selected, labels), style = MaterialTheme.typography.bodyMedium)
        ResponsiveActionGroup { actionModifier ->
            Button(onClick = { confirming = null; onRun(wanted) }, enabled = !running,
                modifier = actionModifier) { Text(if (wanted) "Start selected" else "Stop selected") }
            OutlinedButton(onClick = { confirming = null }, modifier = actionModifier) { Text("Cancel") }
        }
    }
}

/** Names every target environment and its count before anything runs. */
internal fun combinedBatchPreview(start: Boolean, selected: Set<UnifiedTransferKey>,
                                  labels: Map<EnvironmentId, String>): String {
    val perEnvironment = selected.groupingBy { it.environment }.eachCount().toList()
        .sortedBy { labels[it.first] ?: it.first.value }
        .joinToString("; ") { (id, n) -> (labels[id] ?: id.value) + ": " + n }
    return (if (start) "Start" else "Stop") + " ${selected.size} selected, one at a time, each only in its own " +
        "client: $perEnvironment. Transfers already in that state are skipped. The first uncertain " +
        "result stops the batch and nothing is retried."
}

internal fun Color.toArgbLong(): Long =
    (((alpha * 255).toInt() shl 24) or ((red * 255).toInt() shl 16) or
        ((green * 255).toInt() shl 8) or (blue * 255).toInt()).toLong() and 0xFFFFFFFFL

/** Decorative identity dot; the environment's name always accompanies it. */
@Composable
internal fun EnvironmentDot(hue: EnvironmentHue?, size: Int = 12) {
    Box(Modifier.size(size.dp).clip(CircleShape)
        .background(hue?.let { Color(it.argb) } ?: ChunksColors.TextSecondary))
}

/** Live-only totals in words, saying what is unknown or left out. */
/** "Showing 4", or "Showing 1 of 4" when search or a filter narrows the selected environments. */
internal fun combinedShowingText(shown: Int, inSelectedEnvironments: Int): String =
    if (shown >= inSelectedEnvironments) "Showing $shown" else "Showing $shown of $inSelectedEnvironments"

/** One scan-friendly summary above the first card. Only verified LIVE rates contribute.
 * Unknown rates and excluded sources remain visibly qualified, never rendered as live zeros.
 */
internal fun includedClientCount(result: io.github.gamesbyian.chunks.model.UnifiedTransferQueryResult): Int =
    (result.sources.map { it.environment }.toSet() + result.missingSelectedEnvironments).size

internal fun combinedCompactSummaryText(
    shown: Int,
    inSelectedEnvironments: Int,
    totals: io.github.gamesbyian.chunks.model.UnifiedTransferTotals,
    incompleteOrStale: Boolean,
    includedClients: Int
): String {
    val noun = if (inSelectedEnvironments == 1) "transfer" else "transfers"
    val count = if (shown >= inSelectedEnvironments) "$shown $noun"
        else "$shown of $inSelectedEnvironments transfers"
    val down = if (totals.downloadTotalBeyondRange) "above reporting range"
        else Units.bytes(totals.downloadBytesPerSecond) + "/s"
    val up = if (totals.uploadTotalBeyondRange) "above reporting range"
        else Units.bytes(totals.uploadBytesPerSecond) + "/s"
    val warnings = buildList {
        if (totals.rowsWithUnknownRate > 0)
            add("${totals.rowsWithUnknownRate} rates unknown")
        add(if (includedClients == 1) "1 client included" else "$includedClients clients included")
        if (incompleteOrStale) add("some clients incomplete")
    }
    // With every selected source excluded (out of date, offline, locked or
    // incomplete) there is no live rate to report: "Live ↓ 0 B/s" would present
    // last-known rows as measured live zeros.
    val rates = if (totals.liveRows == 0 && totals.excludedEnvironments > 0) "No live rates"
        else "Live ↓ $down · ↑ $up"
    return "$count · $rates" +
        if (warnings.isEmpty()) "" else " · " + warnings.joinToString(" · ")
}

internal fun combinedTotalsText(t: io.github.gamesbyian.chunks.model.UnifiedTransferTotals): String =
    "Live total: ↓ " + (if (t.downloadTotalBeyondRange) "exceeds reporting range"
                         else Units.bytes(t.downloadBytesPerSecond) + "/s") +
        " · ↑ " + (if (t.uploadTotalBeyondRange) "exceeds reporting range"
                    else Units.bytes(t.uploadBytesPerSecond) + "/s") +
        " across ${t.liveRows} transfers" +
        (if (t.rowsWithUnknownRate > 0) " · ${t.rowsWithUnknownRate} with rates not reported" else "") +
        (if (t.excludedEnvironments > 0) " · " + (if (t.excludedEnvironments == 1) "1 client" else "${t.excludedEnvironments} clients") + " excluded (not fully observed)" else "")


/** Progress colour shared with the local device and rTorrent cards; colour is never the only signal. */
internal fun combinedProgressTone(row: UnifiedTransferObservation): ChunksTone = when {
    row.hasError -> ChunksTone.ERROR
    (row.progressPpm ?: 0) >= 1_000_000 -> ChunksTone.COMPLETE
    row.started == false -> ChunksTone.PAUSED
    else -> ChunksTone.ACTIVE
}

/**
 * Exact view-only profile selection. Friendly display names never participate
 * in the scope key, so two servers with the same label remain independent.
 */
internal fun savedProfileViewScope(
    profileId: String,
    knownProfileIds: Set<String>
): EnvironmentViewScope? = profileId.takeIf { it.isNotBlank() && it in knownProfileIds }
    ?.let { EnvironmentViewScope.Only(setOf(EnvironmentId(it))) }
