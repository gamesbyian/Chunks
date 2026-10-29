package io.github.gamesbyian.chunks

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentSnapshots
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.EnvironmentViewScope
import io.github.gamesbyian.chunks.model.LocalInventoryFrame
import io.github.gamesbyian.chunks.model.TransferSortOrdering
import io.github.gamesbyian.chunks.model.UnifiedSortKey
import io.github.gamesbyian.chunks.model.UnifiedTransferCatalog
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import io.github.gamesbyian.chunks.model.Units
import io.github.gamesbyian.chunks.privacy.RemoteTransferPrivacyScope
import io.github.gamesbyian.chunks.privacy.TransferPrivacyRuntime
import io.github.gamesbyian.chunks.privacy.TransferPrivacyVisibility
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.model.UnifiedBatchLifecycle
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
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
import io.github.gamesbyian.chunks.model.EnvironmentHue
import io.github.gamesbyian.chunks.model.EnvironmentPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The connected server's latest accepted list, as the server screen holds it. */
internal data class CombinedServerSource(
    val aliasScope: String,
    val transfers: List<RemoteTransfer>,
    val stale: Boolean,
    val fullView: Boolean,
    val observedAtEpochMillis: Long?,
    /** Start/Stop through the server screen's own mutation gate; null when not offered. */
    val lifecycle: CombinedServerLifecycle? = null
)

/** The connected server's lease, command and settle, exactly as its own list uses them. */
internal class CombinedServerLifecycle(
    val begin: () -> Boolean,
    val settled: () -> Unit,
    val setStarted: suspend (String, Boolean) -> LifecycleOutcome
)

/** A saved profile that is not signed in during this session. */
internal data class CombinedSavedProfile(val id: String, val label: String)

/** A separate single-flight reader so this view never blocks or shares the phone screen's slot. */
private object CombinedPhoneReader {
    /** The list (null if no engine) and the fence revision sampled before the read. */
    val pump = LocalScreenObservationPump<Pair<List<LocalTransferStatus>?, Long>>(
        java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "chunks-combined-phone-status").apply { isDaemon = true }
        }
    )
}

private fun Context.findActivity(): Activity? {
    var here: Context? = this
    repeat(8) {
        if (here is Activity) return here as Activity
        here = (here as? ContextWrapper)?.baseContext
    }
    return null
}

/** Sort keys every source can fill at least partly; "added" is not observed yet, so not offered. */
internal val CombinedSortChoices = listOf(
    UnifiedSortKey.NAME to "Name", UnifiedSortKey.PROGRESS to "Progress", UnifiedSortKey.SIZE to "Size",
    UnifiedSortKey.DOWNLOAD_SPEED to "Download speed", UnifiedSortKey.UPLOAD_SPEED to "Upload speed",
    UnifiedSortKey.PEERS to "Peers")

internal fun UnifiedSortKey.orderingKind(): TransferSortOrdering.Kind = when (this) {
    UnifiedSortKey.NAME -> TransferSortOrdering.Kind.NAME
    UnifiedSortKey.ADDED_AT -> TransferSortOrdering.Kind.ADDED
    UnifiedSortKey.SIZE -> TransferSortOrdering.Kind.SIZE
    UnifiedSortKey.PROGRESS -> TransferSortOrdering.Kind.PROGRESS
    UnifiedSortKey.DOWNLOAD_SPEED, UnifiedSortKey.UPLOAD_SPEED -> TransferSortOrdering.Kind.SPEED
    UnifiedSortKey.PEERS -> TransferSortOrdering.Kind.PEERS
}

internal fun availabilityText(source: EnvironmentTransferSnapshot): String = when (source.availability) {
    EnvironmentAvailability.LIVE -> if (source.inventoryComplete) "Live" else "Live, list may be incomplete"
    EnvironmentAvailability.STALE -> "Out of date, showing last list"
    EnvironmentAvailability.OFFLINE -> if (source.environment == EnvironmentSnapshots.PHONE)
        "Engine not running, open On this phone" else "Not connected"
    EnvironmentAvailability.LOCKED -> "Locked, open it to unlock"
}

/**
 * Combined Transfers: one globally sorted (or grouped) union of the selected
 * environments. The environment chips are a VIEW scope only. The one action
 * offered is reviewed Start/Stop over an explicit row selection, run per
 * environment through that environment's own gate; Add, remove and every
 * other change stay in each environment's own list.
 */
@Composable
internal fun CombinedTransfersScreen(
    server: CombinedServerSource?,
    savedProfiles: List<CombinedSavedProfile>,
    onOpenPhone: () -> Unit,
    onOpenServer: (() -> Unit)?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val preferences = remember(context) { CombinedViewPreferences(context) }
    val aliases = remember(context) { TransferAliasStore(context) }
    var query by remember { mutableStateOf(preferences.load()) }
    fun update(next: UnifiedTransferQuery) {
        query = next
        preferences.save(next.copy(search = ""))
    }

    // Phone: read only an engine already running in this process. Opening this
    // view never starts the phone engine or its service (remote-only use).
    var phoneFrame by remember { mutableStateOf(LocalInventoryFrame<LocalTransferStatus>(emptyList(), stale = false)) }
    var phoneConfirmed by remember { mutableStateOf(false) }
    var phoneRunning by remember { mutableStateOf(LocalEngineHolder.current() != null) }
    var phoneObservedAt by remember { mutableStateOf<Long?>(null) }
    // Explicit cross-environment selection; never derived from the view scope.
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<UnifiedTransferKey>>(emptySet()) }
    var batchRunning by remember { mutableStateOf(false) }
    var batchResult by remember { mutableStateOf<List<String>>(emptyList()) }
    val batchScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (true) {
            when (val outcome = CombinedPhoneReader.pump.takeCompleted()) {
                is LocalScreenPollOutcome.Fresh -> {
                    val (list, sample) = outcome.value
                    phoneRunning = list != null
                    // A read sampled before or during a phone command cannot clear its readback latch.
                    if (list != null && PhoneMutationFence.revision.acceptObservation(sample, list)) {
                        phoneFrame = LocalInventoryFrame(list, stale = false)
                        phoneConfirmed = true
                        phoneObservedAt = System.currentTimeMillis()
                    }
                }
                LocalScreenPollOutcome.Unavailable -> phoneFrame = phoneFrame.copy(stale = true)
                null -> Unit
            }
            if ((CombinedPhoneReader.pump.inFlightMillis() ?: 0L) >= 15_000L)
                phoneFrame = phoneFrame.copy(stale = true)
            val sample = PhoneMutationFence.revision.snapshot()
            CombinedPhoneReader.pump.tryObserve { LocalEngineHolder.current()?.list() to sample }
            delay(1_000)
        }
    }

    // Privacy follows each environment's own gate. Reading the lifecycle
    // revision re-evaluates after background/foreground transitions.
    val privacyRevision = TransferPrivacyRuntime.lifecycleRevision
    val phonePrivacy = remember(privacyRevision) {
        TransferPrivacyVisibility.current(context, activity, TransferCollectionStore.LOCAL_ENGINE_SCOPE)
    }
    val serverPrivacy = remember(privacyRevision, server?.aliasScope) {
        server?.let { RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(it.aliasScope) }
            ?.let { TransferPrivacyVisibility.current(context, activity, it) }
    }

    val serverId = server?.let {
        EnvironmentSnapshots.serverId(it.aliasScope.substringBefore('\u0000'), it.aliasScope.substringAfter('\u0000', ""))
    }
    val labels = LinkedHashMap<EnvironmentId, String>().apply {
        put(EnvironmentSnapshots.PHONE, PHONE_ENVIRONMENT_LABEL)
        if (server != null && serverId != null) put(serverId, serverEnvironmentLabel(server.aliasScope))
        savedProfiles.forEach { profile -> putIfAbsent(EnvironmentId(profile.id), "Server · " + profile.label) }
    }
    // Colours follow stable IDs: assigned once, then kept through rename/reorder/reconnect.
    val colourStore = remember(context) { EnvironmentColourStore(context) }
    var palette by remember(labels.keys.toList()) { mutableStateOf(colourStore.ensure(labels.keys.toList())) }
    var choosingColours by remember { mutableStateOf(false) }

    val sources = buildList {
        val phoneStale = phoneFrame.stale || PhoneMutationFence.revision.blocksMutations() || batchRunning
        val phone = if (phoneRunning || phoneConfirmed) EnvironmentSnapshots.phone(
            phoneFrame.copy(stale = phoneStale), phoneConfirmed,
            locked = !phonePrivacy.mayShow, observedAtEpochMillis = phoneObservedAt)
        else EnvironmentTransferSnapshot(EnvironmentSnapshots.PHONE,
            if (phonePrivacy.mayShow) EnvironmentAvailability.OFFLINE else EnvironmentAvailability.LOCKED,
            false, null, emptyList())
        add(phone.withAliases { hash -> aliases.get(TransferCollectionStore.LOCAL_ENGINE_SCOPE, hash) })
        if (server != null && serverId != null) {
            add(EnvironmentSnapshots.server(serverId, server.transfers, connected = true,
                stale = server.stale || batchRunning,
                locked = serverPrivacy?.mayShow != true, fullView = server.fullView,
                observedAtEpochMillis = server.observedAtEpochMillis)
                .withAliases { hash -> aliases.get(server.aliasScope, hash) })
        }
        savedProfiles.filter { EnvironmentId(it.id) != serverId }.forEach { profile ->
            add(EnvironmentSnapshots.server(EnvironmentId(profile.id), emptyList(), connected = false,
                stale = false, locked = false, fullView = false, observedAtEpochMillis = null))
        }
    }
    val result = remember(sources, query) { UnifiedTransferCatalog.query(sources, query) }

    // Hide this window from screenshots/recents whenever a protected scope is shown.
    val secure = (phonePrivacy.needsSecureWindow && phonePrivacy.mayShow) ||
        (serverPrivacy?.needsSecureWindow == true && serverPrivacy.mayShow)
    DisposableEffect(activity, secure) {
        if (secure) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (secure) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("All environments", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
            }
            Text("Browse every environment together. Start and Stop can be applied to a selection " +
                "here; every other change happens in each environment's own list.",
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            CombinedBatchSection(
                selecting = selecting,
                onSelecting = { selecting = it; if (!it) selected = emptySet(); batchResult = emptyList() },
                selected = selected,
                visible = result.transfers.map { it.key }.toSet(),
                labels = labels,
                running = batchRunning,
                result = batchResult,
                onClear = { selected = emptySet() },
                onRun = { start ->
                    batchRunning = true
                    batchResult = emptyList()
                    val chosen = selected
                    val snapshot = sources
                    batchScope.launch {
                        try {
                            batchResult = runCombinedLifecycle(chosen, start, snapshot, server?.lifecycle, serverId)
                                .describe { labels[it] ?: it.value }
                        } finally {
                            selected = emptySet()
                            batchRunning = false
                        }
                    }
                }
            )
        }
        item {
            Text("Show", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = query.scope == EnvironmentViewScope.All,
                    onClick = { update(query.copy(scope = EnvironmentViewScope.All)) },
                    label = { Text("All") }, modifier = Modifier.heightIn(min = 48.dp))
                labels.forEach { (id, label) ->
                    val scope = query.scope
                    val selected = scope is EnvironmentViewScope.Only && id in scope.ids
                    FilterChip(selected = selected, onClick = {
                        update(query.copy(scope = toggleScope(scope, id)))
                    }, label = { Text(label) }, leadingIcon = { EnvironmentDot(palette.hueOf(id)) },
                        modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        item {
            OutlinedTextField(value = query.search, onValueChange = { query = query.copy(search = it) },
                label = { Text("Search all selected") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            Text("Sort by", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CombinedSortChoices.forEach { (key, label) ->
                    FilterChip(selected = query.sortKey == key, onClick = {
                        if (query.sortKey != key) update(query.copy(sortKey = key,
                            direction = TransferSortOrdering.naturalDirection(key.orderingKind())))
                    }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            val sortName = CombinedSortChoices.firstOrNull { it.first == query.sortKey }?.second ?: "Name"
            TransferSortDirectionButton(sortName,
                TransferSortOrdering.label(query.sortKey.orderingKind(), query.direction),
                onReverse = { update(query.copy(direction = TransferSortOrdering.reversed(query.direction))) })
            // Stacked so the label can wrap at 320dp and 200% text without pushing the switch off-screen.
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Group by environment", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                Switch(checked = query.groupByEnvironment,
                    onCheckedChange = { update(query.copy(groupByEnvironment = it)) })
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                result.sources.forEach { source ->
                    Text(labels[source.environment].orEmpty() + ": " + availabilityText(source) + " · " +
                        source.transfers.size + " transfers", style = MaterialTheme.typography.bodySmall)
                }
                if (result.missingSelectedEnvironments.isNotEmpty()) Text(
                    "${result.missingSelectedEnvironments.size} selected environment(s) are no longer set up. " +
                        "Choose All or reselect environments.", style = MaterialTheme.typography.bodySmall,
                    color = ChunksColors.Yellow)
                Text("Showing ${result.transfers.size}" +
                    if (result.hasIncompleteOrStaleSource) " · some environments are missing or out of date" else "",
                    style = MaterialTheme.typography.titleSmall)
            }
        }
        if (query.groupByEnvironment) {
            result.groups.forEach { group ->
                item(key = "header-" + group.source.environment.value) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EnvironmentDot(palette.hueOf(group.source.environment))
                        Text(labels[group.source.environment].orEmpty() + " · " + group.transfers.size +
                            " shown", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() })
                    }
                }
                items(group.transfers, key = { "row-" + it.key.environment.value + it.key.infoHash }) { row ->
                    CombinedTransferRow(row, labels[row.key.environment].orEmpty(), palette.hueOf(row.key.environment),
                        selection = if (selecting) row.key in selected else null,
                        onSelect = { on -> selected = if (on) selected + row.key else selected - row.key })
                }
            }
        } else {
            items(result.transfers, key = { "row-" + it.key.environment.value + it.key.infoHash }) { row ->
                CombinedTransferRow(row, labels[row.key.environment].orEmpty(), palette.hueOf(row.key.environment),
                    selection = if (selecting) row.key in selected else null,
                    onSelect = { on -> selected = if (on) selected + row.key else selected - row.key })
            }
        }
        item {
            OutlinedButton(onClick = { choosingColours = !choosingColours },
                modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
                Text(if (choosingColours) "Done with colours" else "Environment colours…")
            }
            if (choosingColours) EnvironmentColourChooser(labels, palette,
                onChoose = { id, hue -> colourStore.choose(id, hue); palette = colourStore.load() },
                onRestore = { id -> colourStore.restoreAutomatic(id); palette = colourStore.load() })
        }
        item {
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenPhone, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Open On this phone")
                }
                if (onOpenServer != null) OutlinedButton(onClick = onOpenServer,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Open server list") }
            }
        }
    }
}

/** All → only the tapped environment; otherwise toggle, and an emptied subset returns to All. */
internal fun toggleScope(scope: EnvironmentViewScope, id: EnvironmentId): EnvironmentViewScope = when (scope) {
    EnvironmentViewScope.All -> EnvironmentViewScope.Only(setOf(id))
    is EnvironmentViewScope.Only -> {
        val next = if (id in scope.ids) scope.ids - id else scope.ids + id
        if (next.isEmpty()) EnvironmentViewScope.All else EnvironmentViewScope.Only(next)
    }
}

private fun EnvironmentTransferSnapshot.withAliases(alias: (String) -> String?): EnvironmentTransferSnapshot =
    copy(transfers = transfers.map { item -> alias(item.key.infoHash)?.let { item.copy(displayName = it) } ?: item })

/** Text summary of one row; unknown facts are said as unknown, never as zero. */
internal fun combinedRowFacts(row: UnifiedTransferObservation): String = listOf(
    when (row.started) { true -> "Started"; false -> "Stopped"; null -> "State unknown" },
    row.progressPpm?.let { "${it / 10_000}%" } ?: "Progress unknown",
    row.sizeBytes?.let { Units.bytes(it) } ?: "Size unknown",
    "↓ " + (row.downloadBytesPerSecond?.let { Units.bytes(it) + "/s" } ?: "not reported"),
    "↑ " + (row.uploadBytesPerSecond?.let { Units.bytes(it) + "/s" } ?: "not reported")
).joinToString(" · ")

@Composable
private fun CombinedTransferRow(
    row: UnifiedTransferObservation,
    environmentLabel: String,
    hue: EnvironmentHue?,
    /** Null when not selecting. */
    selection: Boolean? = null,
    onSelect: (Boolean) -> Unit = {}
) {
    val accent = hue?.let { Color(it.argb) } ?: ChunksColors.Outline
    // The hue is used for the label only where it meets AA on this surface.
    val labelColour = if (hue != null && EnvironmentPalette.labelIsReadable(hue, ChunksColors.Surface.toArgbLong()))
        accent else ChunksColors.Text
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(12.dp))
        .background(ChunksColors.Surface)) {
        // Decorative rail; the environment name below carries the identity.
        Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
        if (selection != null) Checkbox(checked = selection, onCheckedChange = onSelect,
            modifier = Modifier.padding(start = 4.dp).align(Alignment.CenterVertically).semantics {
                contentDescription = "Select ${row.displayName} on $environmentLabel"
            })
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(environmentLabel, style = MaterialTheme.typography.labelMedium, color = labelColour,
                fontWeight = FontWeight.Bold)
            Text(row.displayName, style = MaterialTheme.typography.titleSmall)
            if (row.displayName != row.originalName) Text("Torrent name: " + row.originalName,
                style = MaterialTheme.typography.bodySmall)
            Text(combinedRowFacts(row), style = MaterialTheme.typography.bodySmall)
            if (row.hasError) Text("Needs attention in its own list", color = ChunksColors.Red,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
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
    phoneEngine: () -> io.github.gamesbyian.chunks.engine.LocalTorrentEngine? = { LocalEngineHolder.current() }
): UnifiedBatchLifecycle.Report {
    val byKey = sources.flatMap { it.transfers }.associateBy { it.key }
    val targets = chosen.sortedWith(compareBy({ it.environment.value }, { it.infoHash }))
        .map { UnifiedBatchLifecycle.Target(it, byKey[it]?.started) }
    val adapters = HashMap<EnvironmentId, UnifiedBatchLifecycle.Adapter>()
    val refusals = HashMap<EnvironmentId, String>()
    var phoneLeased = false
    var serverLeased = false
    try {
        // A source that is not live is refused by the batch itself; never lease its gate.
        val live = sources.filter { it.availability == EnvironmentAvailability.LIVE }.map { it.environment }.toSet()
        for (environment in targets.map { it.key.environment }.toSet()) {
            if (environment !in live) continue
            if (environment == EnvironmentSnapshots.PHONE) {
                val engine = phoneEngine()
                when {
                    engine == null -> refusals[environment] = "phone engine is not running"
                    !PhoneMutationFence.revision.beginMutation() ->
                        refusals[environment] = "waiting for a fresh phone list"
                    else -> {
                        phoneLeased = true
                        adapters[environment] = UnifiedBatchLifecycle.Adapter { hash, on ->
                            withContext(Dispatchers.IO) {
                                (if (on) engine.resume(hash) else engine.pause(hash)).outcome.toLifecycleReply()
                            }
                        }
                    }
                }
            } else if (environment == serverId && serverLifecycle != null) {
                if (!serverLifecycle.begin()) refusals[environment] = "server is busy or refreshing"
                else {
                    serverLeased = true
                    adapters[environment] = UnifiedBatchLifecycle.Adapter { hash, on ->
                        serverLifecycle.setStarted(hash, on).toLifecycleReply(on)
                    }
                }
            } else refusals[environment] = "not connected in this session"
        }
        return UnifiedBatchLifecycle.run(targets, start, sources, adapters, refusals)
    } finally {
        if (phoneLeased) PhoneMutationFence.revision.settledMutation()
        if (serverLeased) serverLifecycle?.settled?.invoke()
    }
}

@Composable
private fun CombinedBatchSection(
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
        if (running) Text("Working… each environment's list will refresh afterwards.",
            style = MaterialTheme.typography.bodySmall)
        result.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (!selecting || selected.isEmpty()) return@Column
        val hidden = (selected - visible).size
        Text("${selected.size} selected" + (if (hidden > 0) ", $hidden not shown by the current view" else ""),
            style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirming = true }, enabled = !running,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Start…") }
            OutlinedButton(onClick = { confirming = false }, enabled = !running,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop…") }
            OutlinedButton(onClick = { onClear(); confirming = null }, enabled = !running,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear") }
        }
        val wanted = confirming ?: return@Column
        Text(combinedBatchPreview(wanted, selected, labels), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { confirming = null; onRun(wanted) }, enabled = !running,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(if (wanted) "Start selected" else "Stop selected") }
            OutlinedButton(onClick = { confirming = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
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
        "environment: $perEnvironment. Transfers already in that state are skipped. The first uncertain " +
        "result stops the batch and nothing is retried."
}

private fun Color.toArgbLong(): Long =
    (((alpha * 255).toInt() shl 24) or ((red * 255).toInt() shl 16) or
        ((green * 255).toInt() shl 8) or (blue * 255).toInt()).toLong() and 0xFFFFFFFFL

/** Decorative identity dot; the environment's name always accompanies it. */
@Composable
internal fun EnvironmentDot(hue: EnvironmentHue?, size: Int = 12) {
    Box(Modifier.size(size.dp).clip(CircleShape)
        .background(hue?.let { Color(it.argb) } ?: ChunksColors.Outline))
}

/**
 * Per-environment palette choice. Repeating a colour is allowed; Restore
 * automatic returns to the colour first assigned to that environment.
 */
@Composable
private fun EnvironmentColourChooser(
    labels: Map<EnvironmentId, String>,
    palette: EnvironmentPalette.State,
    onChoose: (EnvironmentId, EnvironmentHue) -> Unit,
    onRestore: (EnvironmentId) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        labels.forEach { (id, label) ->
            val current = palette.hueOf(id)
            Text(label + " · " + (current?.label ?: "no colour") +
                if (id in palette.chosen) " (chosen)" else " (automatic)",
                style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                EnvironmentHue.entries.forEach { hue ->
                    val isCurrent = hue == current
                    Box(Modifier.size(48.dp).clip(CircleShape)
                        .clickable(role = Role.RadioButton) { onChoose(id, hue) }
                        .semantics {
                            contentDescription = "$label colour ${hue.label}"
                            selected = isCurrent
                        }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(if (isCurrent) 36.dp else 28.dp).clip(CircleShape)
                            .background(Color(hue.argb))
                            .then(if (isCurrent) Modifier.border(3.dp, ChunksColors.Text, CircleShape) else Modifier))
                    }
                }
            }
            if (id in palette.chosen) OutlinedButton(onClick = { onRestore(id) },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Restore automatic colour") }
        }
    }
}
