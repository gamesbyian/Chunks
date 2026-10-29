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
import kotlinx.coroutines.delay

/** The connected server's latest accepted list, as the server screen holds it. */
internal data class CombinedServerSource(
    val aliasScope: String,
    val transfers: List<RemoteTransfer>,
    val stale: Boolean,
    val fullView: Boolean,
    val observedAtEpochMillis: Long?
)

/** A saved profile that is not signed in during this session. */
internal data class CombinedSavedProfile(val id: String, val label: String)

/** A separate single-flight reader so this view never blocks or shares the phone screen's slot. */
private object CombinedPhoneReader {
    val pump = LocalScreenObservationPump<List<LocalTransferStatus>?>(
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
 * Read-only combined Transfers: one globally sorted (or grouped) union of the
 * selected environments. Selection here is a VIEW scope only. Nothing on this
 * screen starts, stops, adds or removes; each row names its environment and
 * the open buttons go to that environment's own list for actions.
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
    LaunchedEffect(Unit) {
        while (true) {
            when (val outcome = CombinedPhoneReader.pump.takeCompleted()) {
                is LocalScreenPollOutcome.Fresh -> {
                    val list = outcome.value
                    phoneRunning = list != null
                    if (list != null) {
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
            CombinedPhoneReader.pump.tryObserve { LocalEngineHolder.current()?.list() }
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

    val sources = buildList {
        val phone = if (phoneRunning || phoneConfirmed) EnvironmentSnapshots.phone(phoneFrame, phoneConfirmed,
            locked = !phonePrivacy.mayShow, observedAtEpochMillis = phoneObservedAt)
        else EnvironmentTransferSnapshot(EnvironmentSnapshots.PHONE,
            if (phonePrivacy.mayShow) EnvironmentAvailability.OFFLINE else EnvironmentAvailability.LOCKED,
            false, null, emptyList())
        add(phone.withAliases { hash -> aliases.get(TransferCollectionStore.LOCAL_ENGINE_SCOPE, hash) })
        if (server != null && serverId != null) {
            add(EnvironmentSnapshots.server(serverId, server.transfers, connected = true, stale = server.stale,
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
            Text("A read-only view across environments. To start, stop or change a transfer, open its " +
                "own environment.", style = MaterialTheme.typography.bodySmall)
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
                    }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
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
                    Text(labels[group.source.environment].orEmpty() + " · " + group.transfers.size +
                        " shown", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                }
                items(group.transfers, key = { "row-" + it.key.environment.value + it.key.infoHash }) { row ->
                    CombinedTransferRow(row, labels[row.key.environment].orEmpty())
                }
            }
        } else {
            items(result.transfers, key = { "row-" + it.key.environment.value + it.key.infoHash }) { row ->
                CombinedTransferRow(row, labels[row.key.environment].orEmpty())
            }
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
private fun CombinedTransferRow(row: UnifiedTransferObservation, environmentLabel: String) {
    val server = row.key.environment != EnvironmentSnapshots.PHONE
    val accent = if (server) ChunksColors.Blue else ChunksColors.Cyan
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(12.dp))
        .background(ChunksColors.Surface)) {
        // Decorative rail; the environment name below carries the identity.
        Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(environmentLabel, style = MaterialTheme.typography.labelMedium, color = accent,
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
