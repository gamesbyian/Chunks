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

/**
 * Exact-principal match only. A user-selected display name takes precedence
 * over an endpoint banner in the Combined list, without changing server
 * identity, authenticated authority or a similarly named account's colour.
 */
internal fun connectedServerFriendlyLabel(
    aliasScope: String,
    serverId: EnvironmentId,
    savedProfiles: List<CombinedSavedProfile>
): String = savedProfiles.firstOrNull { it.id == serverId.value }
    ?.let { "Server · " + it.label }
    ?: serverEnvironmentLabel(aliasScope)

/** The connected server's latest accepted list, as the server screen holds it. */
/** Ephemeral selection owner. Never save credentials, sessions or selections to instance state. */
/** A completed off-main-thread read, valid only for this exact privacy/session view. */
private data class CombinedAliasLoad(
    val witness: CombinedBatchViewIdentity,
    val requests: Set<CombinedAliasRequest>,
    val revision: Int,
    val values: Map<CombinedAliasRequest, String>
)

private data class CombinedBatchViewIdentity(
    val signedInServer: EnvironmentId?,
    val authenticatedScope: String?,
    val authenticatedSession: Any?,
    val savedScopes: List<Pair<String, String?>>,
    val privacyEpoch: String,
    val localVisible: Boolean,
    val serverVisible: Boolean?,
    val savedVisibility: List<Pair<String, Boolean>>
)

internal data class CombinedServerSource(
    val aliasScope: String,
    val transfers: List<RemoteTransfer>,
    val stale: Boolean,
    val fullView: Boolean,
    val observedAtEpochMillis: Long?,
    /** Start/Stop through the server screen's own mutation gate; null when not offered. */
    val lifecycle: CombinedServerLifecycle? = null,
    /** Live authenticated RTorrentRemote identity, never a saved profile string. */
    val authenticatedSession: Any? = null,
    /** Parent's accepted inventory revision, required for destructive review identity. */
    val snapshotRevision: Long = 0L
)

/**
 * Destructive Details controls require a *transport-backed* principal match.
 * A rendered source alias and ID are independently supplied view data, so
 * matching those to each other never proves that their RTorrentRemote adapter
 * targets the same endpoint, username and numbered ruTorrent route.
 *
 * This is a pure eligibility check: it sends no RPC, creates no mutation
 * ticket and only returns the exact live row already in the source snapshot.
 */
internal fun authenticatedExactRemovalRow(
    key: UnifiedTransferKey,
    source: CombinedServerSource?,
    advertisedServerId: EnvironmentId?
): RemoteTransfer? {
    val owner = source ?: return null
    val remote = owner.authenticatedSession as? RTorrentRemote ?: return null
    if (remote.addAccountScope() != owner.aliasScope) return null
    val derivedId = EnvironmentSnapshots.serverIdOfAliasScope(owner.aliasScope) ?: return null
    if (derivedId != key.environment || derivedId != advertisedServerId) return null
    return owner.transfers.singleOrNull { it.hash.equals(key.infoHash, ignoreCase = true) }
}

/** The connected server's lease, command and settle, exactly as its own list uses them. */
internal class CombinedServerLifecycle(
    val begin: () -> Boolean,
    val settled: () -> Unit,
    val setStarted: suspend (String, Boolean) -> LifecycleOutcome,
    /** Read-only method-list probe of the same signed-in server; null skips the pre-check. */
    val probe: (suspend () -> io.github.gamesbyian.chunks.rpc.RemoteProbe)? = null,
    /** Exact-identity, read-only diagnosis on the same live authenticated server. */
    val inspectStall: (suspend (String) -> RemoteStallEvidence)? = null,
    /** The command lease and RPC are owned by this exact transport, not a label. */
    val ownerSession: RTorrentRemote? = null
)

/** Exact adapter-to-view binding for mutating canonical rTorrent operations. */
internal fun verifiedCombinedLifecycleOwner(
    source: CombinedServerSource?, advertisedServerId: EnvironmentId?
): RTorrentRemote? {
    val owner = source ?: return null
    val remote = owner.authenticatedSession as? RTorrentRemote ?: return null
    if (owner.lifecycle?.ownerSession !== remote) return null
    if (remote.addAccountScope() != owner.aliasScope) return null
    if (EnvironmentSnapshots.serverIdOfAliasScope(owner.aliasScope) != advertisedServerId) return null
    return remote
}

/** A saved profile that is not signed in during this session. */
internal data class CombinedSavedProfile(
    val id: String,
    val label: String,
    /** Authenticated endpoint + NUL + username, for its own privacy gate and aliases. */
    val aliasScope: String? = null,
    /** The person's friendly profile name alone, for compact rows; [label] stays exact. */
    val shortLabel: String? = null,
    /** Read-only list of its "main" view with its own saved credentials; null when unavailable. */
    val listOnly: (suspend () -> List<RemoteTransfer>)? = null
)

/**
 * Compact visible origin labels for rows and view chips. The exact label
 * (which includes the server address) remains in headings, confirmations,
 * details and every spoken row action. A compact name is used only when it is
 * unique among the environments shown; otherwise the exact label is kept, so
 * two instances on one host can never look identical.
 */
internal fun compactEnvironmentLabels(
    exact: Map<EnvironmentId, String>,
    compact: Map<EnvironmentId, String>
): Map<EnvironmentId, String> {
    val counts = exact.keys.mapNotNull { compact[it]?.trim()?.takeIf(String::isNotEmpty) }
        .groupingBy { it }.eachCount()
    return exact.mapValues { (id, full) ->
        val short = compact[id]?.trim()?.takeIf(String::isNotEmpty)
        if (short != null && counts[short] == 1 &&
            exact.none { (other, label) -> other != id && label == short }) short else full
    }
}

/** "Server · user @ host" without the RPC path, for compact display only. */
internal fun compactServerLabel(aliasScope: String): String {
    val endpoint = aliasScope.substringBefore('\u0000')
    val user = aliasScope.substringAfter('\u0000', "").trim()
    val host = try { java.net.URI(endpoint.trim()).host } catch (_: Exception) { null }
        ?.takeIf { it.isNotBlank() } ?: return serverEnvironmentLabel(aliasScope)
    return "Server · " + (if (user.isEmpty()) host else "$user @ $host")
}

/** One read-only fetch of another saved server: never a command, never a sign-in switch. */
internal data class SavedServerListing(
    val transfers: List<RemoteTransfer> = emptyList(),
    val observedAtEpochMillis: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** Source witness; a profile change must not reuse a previous account's rows. */
    val sourceAliasScope: String? = null
)

internal fun listingFailure(failure: Throwable): String = when (failure) {
    is kotlinx.coroutines.TimeoutCancellationException -> "did not answer in time"
    // A read timeout is an unresponsive server, not a generic network fault.
    is java.net.SocketTimeoutException -> "did not answer in time"
    is java.net.UnknownHostException -> "host name not found"
    is java.net.ConnectException -> "connection refused"
    is javax.net.ssl.SSLException -> "secure connection failed"
    // Only a genuine transport-observed status is classified; sign-in
    // rejection and a gateway without its rTorrent need different actions.
    is io.github.gamesbyian.chunks.rpc.RpcHttpStatusException -> when (failure.status) {
        401, 403 -> "sign-in rejected; check this saved login"
        502, 503, 504 -> "server reached but rTorrent did not answer (HTTP ${failure.status})"
        else -> "server returned HTTP ${failure.status}"
    }
    is java.io.IOException -> "network error"
    else -> "unexpected reply"
}

/** A listing older than this is shown as out of date; it is never refreshed silently. */
internal const val SAVED_LISTING_FRESH_MS = EnvironmentSnapshots.SERVER_INVENTORY_FRESH_MS

/** A failed refresh invalidates live status immediately, while preserving last-known rows. */
internal fun savedListingIsStale(listing: SavedServerListing, now: Long): Boolean =
    listing.loading || listing.error != null ||
        EnvironmentSnapshots.serverInventoryIsOutOfDate(listing.observedAtEpochMillis, now)

/** A separate single-flight reader so this view never blocks or shares the local device screen's slot. */
private object CombinedLocalReader {
    /** The list (null if no engine) and the fence revision sampled before the read. */
    val pump = LocalScreenObservationPump<Pair<List<LocalTransferStatus>?, Long>>(
        java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "chunks-combined-local-status").apply { isDaemon = true }
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

/** Shared sort keys. Date added is known on the local device only; server rows sort as unknown (last). */
internal val CombinedSortChoices = listOf(
    UnifiedSortKey.NAME to "Name", UnifiedSortKey.ADDED_AT to "Date added",
    UnifiedSortKey.PROGRESS to "Progress", UnifiedSortKey.SIZE to "Size",
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
    EnvironmentAvailability.LIVE -> if (source.canClaimCompleteLiveInventory) "Live" else "Live, list may be incomplete"
    EnvironmentAvailability.STALE -> "Out of date, showing last list"
    EnvironmentAvailability.OFFLINE -> if (source.environment == EnvironmentSnapshots.LOCAL)
        "Engine not running; open transfers on this device" else "Not connected"
    EnvironmentAvailability.LOCKED -> "Locked, open it to unlock"
}

/** Facts from a stale/incomplete source remain visible, never advertised as current row status. */
internal fun combinedRowFreshnessLabel(source: EnvironmentTransferSnapshot?): String? = when {
    source == null -> "Source unavailable; status unverified"
    source.canClaimCompleteLiveInventory -> null
    source.availability == EnvironmentAvailability.LOCKED -> "Locked; current status hidden"
    source.availability == EnvironmentAvailability.OFFLINE -> "Offline; last known facts only"
    source.availability == EnvironmentAvailability.STALE -> "Out of date; last known facts only"
    else -> "List not verified complete; last known facts only"
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
    onOpenLocal: () -> Unit,
    onOpenServer: (() -> Unit)?,
    /** Opens the connected, verified server's existing Add review. Never targets a saved-only profile. */
    onAddToServer: (() -> Unit)? = null,
    /** Opens this exact transfer in its own environment's list; only local and the signed-in server. */
    onOpenTransfer: (UnifiedTransferKey) -> Unit = {},
    onBack: () -> Unit,
    showNavigationFooter: Boolean = true,
    /** Caller-owned list position, kept when visiting an owning list and returning. */
    listState: androidx.compose.foundation.lazy.LazyListState? = null,
    /**
     * Caller-owned search text, kept when opening a transfer in its owning
     * list and coming Back. It is still never written to preferences.
     */
    searchState: androidx.compose.runtime.MutableState<String>? = null,
    /** Caller-supplied verified Add identity, never inferred from a title or an inventory delta. */
    recentlyAddedTransfer: UnifiedTransferKey? = null,
    /** The same app-wide Connections destination as local and signed-in-server lists. */
    onConnections: (() -> Unit)? = null,
    /** A distinct Add callback: merely opening the local device inventory must not consume a shared source. */
    onAddToLocal: (() -> Unit)? = null,
    /** Source content remains in MainActivity process memory; only presence/status reach this list. */
    pendingSharedMagnet: Boolean = false,
    pendingSharedLink: Boolean = false,
    pendingSharedTorrent: Boolean = false,
    readingSharedTorrent: Boolean = false,
    sharedTorrentError: String? = null,
    /** A one-shot view-only request keyed to immutable saved profile ID. */
    scopeFocusRequest: String? = null,
    onScopeFocusConsumed: () -> Unit = {},
    /** Root Transfers returns from full Details without changing filters or identity. */
    returnToCatalogRevision: Long = 0L,
    /** Explicit All environments navigation is distinct from a saved profile link. */
    showAllEnvironmentsRevision: Long = 0L,
    onShowAllEnvironmentsConsumed: (Long) -> Unit = {},
    /** Host opt-in: genuine first-run omits all inventory-management chrome. */
    enableFirstRunWelcome: Boolean = false,
    /** True for an existing verified vault login not yet represented by a roster. */
    hasRememberedRemoteLogin: Boolean = false,
    onWelcomeAdd: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    // Persistent preference reads run off the composition thread. A saved
    // snapshot cannot replace a scope/filter/search chosen while it loads.
    var query by remember { mutableStateOf(UnifiedTransferQuery(search = searchState?.value.orEmpty())) }
    var queryTouched by remember { mutableStateOf(false) }
    // Read the mutable query state inside each callback. A remembered updated
    // value only refreshes on recomposition, so two rapid clicks (sort key,
    // then reverse) can otherwise invert the *previous* key's direction.
    var loadedPreferences by remember { mutableStateOf<CombinedViewPreferences?>(null) }
    LaunchedEffect(context) {
        try {
            val (store, saved) = withContext(Dispatchers.IO) {
                val store = CombinedViewPreferences(context)
                store to store.load()
            }
            // Saved preferences hold no search; keep the session's own text.
            query = CombinedViewPreferences.reconcileLoadedView(query, saved, queryTouched)
                .copy(search = query.search)
            loadedPreferences = store
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Unknown or unreadable persisted view cannot replace user input.
        }
    }
    fun update(next: UnifiedTransferQuery) {
        queryTouched = true
        query = next
        searchState?.value = next.search
        // Save at the explicit event, not during composition; no queued write
        // can reorder a newer choice behind a late preference read.
        (loadedPreferences ?: CombinedViewPreferences(context)).save(next.copy(search = ""))
    }

    // Only an explicit "All environments" action may clear the narrower
    // previously selected saved-profile scope; Details return preserves it.
    LaunchedEffect(showAllEnvironmentsRevision) {
        val requested = showAllEnvironmentsRevision
        if (requested > 0L) {
            // A historical nonzero counter must never replay when the catalog
            // remounts after navigating to a different exact saved profile.
            update(CombinedViewPreferences.showAll(query))
            onShowAllEnvironmentsConsumed(requested)
        }
    }

    // A saved-connection tap changes only the existing canonical browser's scope.
    // Never interpret a friendly label as identity and never fall back to All
    // when a profile is forgotten between tap and composition.
    var explicitSavedRead by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scopeFocusRequest) {
        val id = scopeFocusRequest ?: return@LaunchedEffect
        val eligible = savedProfiles.map { it.id }.toSet() +
            listOfNotNull(server?.aliasScope?.let(
                EnvironmentSnapshots::serverIdOfAliasScope)?.value)
        val requested = savedProfileViewScope(id, eligible)
            ?: runCatching { EnvironmentViewScope.Only(setOf(EnvironmentId(id))) }.getOrNull()
        if (requested != null) {
            update(query.copy(scope = requested))
            explicitSavedRead = id
        }
        // A refused/invalid view request never changes Add or remote session state.
        onScopeFocusConsumed()
    }

    // Local: read only an engine already running in this process. Opening this
    // view never starts the local device engine or its service (remote-only use).
    var localFrame by remember { mutableStateOf(LocalInventoryFrame<LocalTransferStatus>(emptyList(), stale = false)) }
    var localConfirmed by remember { mutableStateOf(false) }
    var localRunning by remember { mutableStateOf(LocalEngineHolder.current() != null) }
    // No native engine can also mean saved torrents waiting for restoration.
    // Positive absence comes from read-only durable discovery, never an empty
    // in-memory list. Repeat after engine stop/list count change to avoid
    // stale absence evidence from a prior Add.
    var noPersistedLocalJobs by remember(context) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(context, localRunning, localFrame.entries.size) {
        noPersistedLocalJobs = try {
            withContext(Dispatchers.IO) {
                !io.github.gamesbyian.chunks.engine.LocalRestoreDiscovery.shouldAttemptRestore(
                    java.io.File(context.filesDir, "local-engine"))
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) { false }
    }
    var localObservedAt by remember { mutableStateOf<Long?>(null) }
    // Restores only a read-only detail target, never an in-flight command or batch selection.
    var savedDetailTarget by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    LaunchedEffect(returnToCatalogRevision) {
        if (returnToCatalogRevision > 0L) savedDetailTarget = null
    }
    val detailKey = restoreCombinedDetailTarget(savedDetailTarget)
    val batchScope = rememberCoroutineScope()
    var listings by remember { mutableStateOf<Map<EnvironmentId, SavedServerListing>>(emptyMap()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); now = System.currentTimeMillis() } }
    LaunchedEffect(Unit) {
        while (true) {
            when (val outcome = CombinedLocalReader.pump.takeCompleted()) {
                is LocalScreenPollOutcome.Fresh -> {
                    val (list, sample) = outcome.value
                    localRunning = list != null
                    if (list == null) {
                        // The engine went away: nothing here is live any more.
                        localConfirmed = false
                        localFrame = LocalInventoryFrame(emptyList(), stale = false)
                        localObservedAt = null
                    }
                    // A read sampled before or during a local command cannot clear its readback latch.
                    if (list != null && LocalMutationFence.revision.acceptObservation(sample, list)) {
                        localFrame = LocalInventoryFrame(list, stale = false)
                        localConfirmed = true
                        localObservedAt = System.currentTimeMillis()
                    }
                }
                LocalScreenPollOutcome.Unavailable -> localFrame = localFrame.copy(stale = true)
                null -> Unit
            }
            if ((CombinedLocalReader.pump.inFlightMillis() ?: 0L) >= 15_000L)
                localFrame = localFrame.copy(stale = true)
            val sample = LocalMutationFence.revision.snapshot()
            CombinedLocalReader.pump.tryObserve { LocalEngineHolder.current()?.list() to sample }
            delay(1_000)
        }
    }

    // Privacy policy storage and SHA-256 preference-key derivation must never
    // run during composition. Until a cancellable, exact-scope asynchronous
    // read completes, every environment is locked, including opt-out scopes.
    val privacyRevision = TransferPrivacyRuntime.lifecycleRevision
    val policyRevision = TransferPrivacyPolicyStore.revision
    val localPrivacyScope = TransferCollectionStore.LOCAL_ENGINE_SCOPE
    val serverPrivacyScope = remember(server?.aliasScope) {
        server?.aliasScope?.let(RemoteTransferPrivacyScope::fromAuthenticatedAliasScope)
    }
    // Profile identity is verified before admission: neither a renamed
    // account nor a different ruTorrent instance may borrow a prior policy.
    val savedScopeIdentities = savedProfiles.map { it.id to it.aliasScope }
    val savedPrivacyScopes = remember(savedScopeIdentities) {
        savedProfiles.associate { profile ->
            EnvironmentId(profile.id) to profile.aliasScope
                ?.takeIf { EnvironmentSnapshots.serverIdOfAliasScope(it)?.value == profile.id }
                ?.let(RemoteTransferPrivacyScope::fromAuthenticatedAliasScope)
        }
    }
    val requestedPrivacyScopes = remember(serverPrivacyScope, savedPrivacyScopes) {
        buildSet {
            add(localPrivacyScope)
            serverPrivacyScope?.let { add(it) }
            savedPrivacyScopes.values.filterNotNull().forEach { add(it) }
        }
    }
    var loadedPrivacy by remember { mutableStateOf<CombinedPrivacyEvidence?>(null) }
    LaunchedEffect(context, requestedPrivacyScopes, policyRevision, privacyRevision) {
        loadedPrivacy = null
        try {
            val modes = withContext(Dispatchers.IO) {
                val store = TransferPrivacyPolicyStore(context)
                requestedPrivacyScopes.associateWith(store::mode)
            }
            loadedPrivacy = CombinedPrivacyEvidence(
                requestedPrivacyScopes, policyRevision, privacyRevision, modes)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // A failed preference read never turns an unknown mode into NEVER.
        }
    }
    // An old policy result is invisible immediately after lifecycle, account,
    // or policy changes, even before LaunchedEffect starts the next read.
    val privacyEvidence = loadedPrivacy?.takeIf {
        it.current(requestedPrivacyScopes, policyRevision, privacyRevision)
    }
    val privacySession = TransferPrivacyRuntime.session
    val deviceSecure = activity != null && TransferDeviceAuthenticator.hasSecureLock(activity)
    val localPrivacy = privacyEvidence?.decision(localPrivacyScope, privacySession, deviceSecure)
        ?: CombinedPrivacyEvidence.LOCKED
    val serverPrivacy = serverPrivacyScope?.let {
        privacyEvidence?.decision(it, privacySession, deviceSecure) ?: CombinedPrivacyEvidence.LOCKED
    }
    val savedPrivacy = savedPrivacyScopes.mapValues { (_, scope) ->
        privacyEvidence?.decision(scope, privacySession, deviceSecure) ?: CombinedPrivacyEvidence.LOCKED
    }
    val serverId = server?.let {
        EnvironmentSnapshots.serverIdOfAliasScope(it.aliasScope)
    }
    // A saved source can be read only while this exact profile's privacy gate
    // allows it. Both the server identity and visibility are checked again
    // before a background read is allowed to publish its response.
    val activeSavedScopes = savedProfiles.mapNotNull { profile ->
        val id = EnvironmentId(profile.id)
        profile.aliasScope?.takeIf { scope ->
            id != serverId && SavedProfileIdentity.matches(profile.id, scope) &&
                profile.listOnly != null && savedPrivacy[id]?.mayShow == true
        }?.let { id to it }
    }.toMap()
    // An invalid saved authenticated endpoint/account identity may remain
    // visible as a configured row, but never load or borrow another scope.
    val invalidSavedProfiles = savedProfiles.filter { it.aliasScope != null &&
        !SavedProfileIdentity.matches(it.id, it.aliasScope) &&
        EnvironmentId(it.id) != serverId
    }.map { EnvironmentId(it.id) }.toSet()
    val currentSavedScopes = rememberUpdatedState(activeSavedScopes)
    val savedListingFence = remember { SavedListingResponseFence() }
    LaunchedEffect(activeSavedScopes) {
        savedListingFence.retireOutside(activeSavedScopes)
        // A profile with changed credentials/endpoint or a locked gate must not
        // leave its old rows or network status in this view.
        listings = listings.filter { (id, listing) ->
            activeSavedScopes[id] != null &&
                activeSavedScopes[id] == listing.sourceAliasScope
        }
    }
    val loadable = savedProfiles.filter { profile ->
        val id = EnvironmentId(profile.id)
        id != serverId && profile.listOnly != null && profile.aliasScope != null &&
            activeSavedScopes[id] == profile.aliasScope
    }
    // Both the all-server and exact-server buttons share the same fenced, read-only path.
    fun loadSavedServers(profiles: List<CombinedSavedProfile>) {
        profiles.forEach { profile ->
            val id = EnvironmentId(profile.id)
            val load = profile.listOnly ?: return@forEach
            val identity = profile.aliasScope ?: return@forEach
            if (activeSavedScopes[id] != identity || listings[id]?.loading == true) return@forEach
            val ticket = savedListingFence.begin(
                SavedListingResponseFence.Source(id, identity))
            val previous = listings[id]?.takeIf { it.sourceAliasScope == identity }
                ?: SavedServerListing(sourceAliasScope = identity)
            listings = listings + (id to previous.copy(loading = true, error = null))
            batchScope.launch {
                // Profile removal/lock may occur between the tap and dispatch.
                if (!savedListingFence.accepts(ticket, currentSavedScopes.value))
                    return@launch
                val next = try {
                    val list = withContext(Dispatchers.IO) {
                        kotlinx.coroutines.withTimeout(45_000) { load() }
                    }
                    SavedServerListing(list, System.currentTimeMillis(),
                        sourceAliasScope = identity)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    if (cancel is kotlinx.coroutines.TimeoutCancellationException)
                        previous.copy(loading = false, error = listingFailure(cancel))
                    else throw cancel
                } catch (failure: Exception) {
                    // Keep last-known rows, never claim the failed read is live.
                    previous.copy(loading = false, error = listingFailure(failure))
                }
                // A slow response from another account or a pre-lock read may
                // neither replace current rows nor resurrect hidden content.
                if (savedListingFence.accepts(ticket, currentSavedScopes.value)) {
                    listings = listings + (id to next)
                    now = System.currentTimeMillis()
                }
            }
        }
    }

    // A saved profile requested explicitly from Connections may initiate one
    // bounded, fenced READ only once its independent privacy gate permits it.
    // Changing scope, forgetting the profile, or a live authenticated session
    // all retire the request without any RPC mutation or cross-account fallback.
    LaunchedEffect(explicitSavedRead, activeSavedScopes, query.scope, savedProfiles, serverId) {
        val id = explicitSavedRead ?: return@LaunchedEffect
        if (serverId?.value == id) {
            explicitSavedRead = null
            return@LaunchedEffect
        }
        if (savedProfiles.none { it.id == id } ||
            query.scope != EnvironmentViewScope.Only(setOf(EnvironmentId(id)))) {
            explicitSavedRead = null
            return@LaunchedEffect
        }
        val selected = loadable.singleOrNull { it.id == id } ?: return@LaunchedEffect
        explicitSavedRead = null
        loadSavedServers(listOf(selected))
    }

    // A selection and its confirmation belong to an exact authenticated UI
    // session, saved-profile roster and privacy state, not merely to a hash.
    // Preserve independent browse/sort state when a session changes, but never
    // carry an armed cross-environment Start/Stop confirmation into the next.
    val batchIdentity = CombinedBatchViewIdentity(
        signedInServer = serverId,
        authenticatedScope = server?.aliasScope,
        authenticatedSession = server?.authenticatedSession,
        savedScopes = savedProfiles.map { it.id to it.aliasScope }.sortedBy { it.first },
        privacyEpoch = privacyRevision.toString(),
        localVisible = localPrivacy.mayShow,
        serverVisible = serverPrivacy?.mayShow,
        savedVisibility = savedPrivacy.map { (id, decision) -> id.value to decision.mayShow }
            .sortedBy { it.first }
    )
    val latestBatchIdentity = rememberUpdatedState(batchIdentity)
    // All four state fields must share exactly the same identity boundary.
    var selecting by remember(batchIdentity) { mutableStateOf(false) }
    var selected by remember(batchIdentity) { mutableStateOf<Set<UnifiedTransferKey>>(emptySet()) }
    var batchRunning by remember(batchIdentity) { mutableStateOf(false) }
    var batchResult by remember(batchIdentity) { mutableStateOf<List<String>>(emptyList()) }
    // Rows are compact by default; one tap expands a row in place for quick controls.
    // Exact env|hash keys, so a recycled lazy row never expands a different transfer.
    var expandedRows by rememberSaveable { mutableStateOf(listOf<String>()) }
    var quickRunning by remember(batchIdentity) { mutableStateOf<UnifiedTransferKey?>(null) }
    var quickResults by remember(batchIdentity) {
        mutableStateOf<Map<UnifiedTransferKey, List<String>>>(emptyMap()) }
    val labels = LinkedHashMap<EnvironmentId, String>().apply {
        put(EnvironmentSnapshots.LOCAL, LOCAL_ENVIRONMENT_LABEL)
        if (server != null && serverId != null)
            put(serverId, connectedServerFriendlyLabel(server.aliasScope, serverId, savedProfiles))
        savedProfiles.forEach { profile -> putIfAbsent(EnvironmentId(profile.id), "Server · " + profile.label) }
    }
    val rowLabels = remember(labels, savedProfiles, server?.aliasScope) {
        compactEnvironmentLabels(labels, buildMap {
        put(EnvironmentSnapshots.LOCAL, LOCAL_ENVIRONMENT_LABEL)
        savedProfiles.forEach { profile ->
            profile.shortLabel?.let { put(EnvironmentId(profile.id), "Server · $it") }
        }
        if (server != null && serverId != null && serverId !in this)
            put(serverId, compactServerLabel(server.aliasScope))
        })
    }
    // Palette storage is a lifecycle effect, not work performed by remember or
    // by LazyColumn composition. Read external changes via the shared revision:
    // profile rename, navigation and chooser updates never overwrite user hues.
    val paletteIdentities = labels.keys.toList()
    val paletteRevision = EnvironmentColourStore.revision
    var palette by remember { mutableStateOf(EnvironmentPalette.State(emptyMap(), emptyMap())) }
    var coloursEnabled by remember { mutableStateOf(true) }
    LaunchedEffect(context, paletteIdentities, paletteRevision) {
        try {
            val (savedPalette, enabled) = withContext(Dispatchers.IO) {
                val store = EnvironmentColourStore(context)
                store.ensure(paletteIdentities) to store.showEnvironmentColours()
            }
            palette = savedPalette
            coloursEnabled = enabled
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Keep prior colours and the global preference if storage is unreadable.
        }
    }
    val hueFor: (EnvironmentId) -> EnvironmentHue? = { id ->
        palette.hueOf(id).takeIf { coloursEnabled }
    }
    var showingViewOptions by rememberSaveable { mutableStateOf(false) }
    var showingBatchTools by rememberSaveable { mutableStateOf(false) }
    var showingSortOptions by rememberSaveable { mutableStateOf(false) }
    var showingSourceStatus by rememberSaveable { mutableStateOf(false) }
    // Only environments with a live list here can open a row there.
    fun openFor(key: UnifiedTransferKey): (() -> Unit)? =
        if (key.environment == EnvironmentSnapshots.LOCAL || (serverId != null && key.environment == serverId))
            ({
                savedDetailTarget = null
                onOpenTransfer(key)
            }) else null

    // Keyed on data only: MainActivity rebuilds the server source (and its lifecycle lambdas) each frame.
    val rawSources = remember(localFrame, localConfirmed, localRunning, localObservedAt, localPrivacy,
        server?.aliasScope, server?.transfers, server?.stale, server?.fullView, server?.observedAtEpochMillis,
        serverPrivacy, savedPrivacy, activeSavedScopes, listings, now, batchRunning, quickRunning,
        LocalMutationFence.revision.blocksMutations()) { buildList {
        // A quick row command is fenced exactly like a batch run: its pre-command rows are stale.
        val commandInFlight = batchRunning || quickRunning != null
        // The ticker invalidates old reads; use the actual clock when a newer
        // observation arrives between ticks so it is not mistaken for a future read.
        val snapshotNow = System.currentTimeMillis()
        val localStale = localFrame.stale || LocalMutationFence.revision.blocksMutations() || commandInFlight
        val local = if (localRunning || localConfirmed) EnvironmentSnapshots.local(
            localFrame.copy(stale = localStale), localConfirmed,
            locked = !localPrivacy.mayShow, observedAtEpochMillis = localObservedAt)
        else EnvironmentTransferSnapshot(EnvironmentSnapshots.LOCAL,
            if (localPrivacy.mayShow) EnvironmentAvailability.OFFLINE else EnvironmentAvailability.LOCKED,
            false, null, emptyList())
        add(local)
        if (server != null && serverId != null) {
            add(EnvironmentSnapshots.server(serverId, server.transfers, connected = true,
                stale = server.stale || commandInFlight,
                locked = serverPrivacy?.mayShow != true, fullView = server.fullView,
                observedAtEpochMillis = server.observedAtEpochMillis, nowEpochMillis = snapshotNow)
            )
        }
        savedProfiles.filter { EnvironmentId(it.id) != serverId }.forEach { profile ->
            val id = EnvironmentId(profile.id)
            val listing = activeSavedScopes[id]?.let { witness ->
                listings[id]?.takeIf { it.sourceAliasScope == witness }
            }
            val observed = listing?.observedAtEpochMillis
            val locked = savedPrivacy[id]?.mayShow == false
            add(EnvironmentSnapshots.server(id, listing?.transfers.orEmpty(), connected = observed != null,
                stale = listing?.let { savedListingIsStale(it, snapshotNow) } == true,
                locked = locked, fullView = true, observedAtEpochMillis = observed, nowEpochMillis = snapshotNow)
            )
        }
    }
    }
    // Resolve labels only from explicitly authorized owner scopes. An account
    // or privacy change makes a previous alias map unusable immediately, even
    // before the new asynchronous read has finished.
    val aliasScopes = buildMap<EnvironmentId, String> {
        if (localPrivacy.mayShow) put(EnvironmentSnapshots.LOCAL, TransferCollectionStore.LOCAL_ENGINE_SCOPE)
        if (serverId != null && server != null && serverPrivacy?.mayShow == true) {
            put(serverId, server.aliasScope)
        }
        savedProfiles.forEach { profile ->
            val scope = profile.aliasScope ?: return@forEach
            val id = EnvironmentId(profile.id)
            if (id != serverId && savedPrivacy[id]?.mayShow == true &&
                activeSavedScopes[id] == scope &&
                EnvironmentSnapshots.serverIdOfAliasScope(scope) == id) {
                put(id, scope)
            }
        }
    }
    val aliasRequests = remember(rawSources, aliasScopes) {
        CombinedAliasProjection.requests(rawSources, aliasScopes)
    }
    val aliasRevision = TransferAliasStore.revision
    var loadedAliases by remember { mutableStateOf<CombinedAliasLoad?>(null) }
    LaunchedEffect(context, aliasRequests, aliasRevision, batchIdentity) {
        loadedAliases = null
        if (aliasRequests.isEmpty()) return@LaunchedEffect
        try {
            val found = withContext(Dispatchers.IO) {
                TransferAliasStore(context).getBatch(aliasRequests)
            }
            loadedAliases = CombinedAliasLoad(batchIdentity, aliasRequests, aliasRevision, found)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Raw observed torrent titles remain readable; never use stale
            // aliases or accidentally import a different account's labels.
        }
    }
    val currentAliases = loadedAliases?.takeIf {
        it.witness == batchIdentity && it.requests == aliasRequests && it.revision == aliasRevision
    }?.values.orEmpty()
    val sources = remember(rawSources, aliasScopes, currentAliases) {
        CombinedAliasProjection.apply(rawSources, aliasScopes, currentAliases)
    }
    // All currently observed, unlocked local + remote jobs enter one backend, independent
    // of the chosen tab, search/filter or composed LazyColumn rows. Exact owner scope,
    // observation generation and transfer identity are captured with each inventory.
    val mediaController = io.github.gamesbyian.chunks.media.LocalTransferMediaController.current
    val mediaGraph = remember(context) { io.github.gamesbyian.chunks.media.ChunksMedia.get(context) }
    // Privacy lifecycle/policy revisions break stale provider authority even if
    // the same account/title later reappears after lock and unlock.
    // Fresh inventory timestamps do not restart the unchanged ongoing work.
    val mediaPrivacyEpoch = "$privacyRevision/$policyRevision"
    val mediaSnapshot = remember(sources, aliasScopes, mediaPrivacyEpoch) {
        io.github.gamesbyian.chunks.media.AuthorizedMediaInventorySnapshot.build(
            sources, aliasScopes, mediaPrivacyEpoch)
    }
    // The activity-lifetime host (#1453) keeps working on the last fed inventory after this
    // screen unmounts and runs the connectivity/retry heartbeat itself. This screen only
    // feeds it. Without a host (isolated screen tests), fall back to a screen-owned session
    // with the same witness so no second executor or unauthorized pass can appear.
    val mediaHost = io.github.gamesbyian.chunks.media.LocalMediaInventoryHost.current
    val currentMediaSnapshot = rememberUpdatedState(mediaSnapshot)
    var mediaSession by remember(mediaGraph, mediaController, mediaHost) {
        mutableStateOf<io.github.gamesbyian.chunks.media.MediaInventoryEnrichmentSession?>(null)
    }
    // Creating/closing the fallback session is an effect; composition itself does no I/O.
    DisposableEffect(mediaGraph, mediaController, mediaHost, batchScope) {
        val owned = if (mediaHost == null) mediaController?.let {
            mediaGraph.openInventorySession(batchScope, it)
        } else null
        mediaSession = owned
        onDispose {
            owned?.close()
            if (mediaSession === owned) mediaSession = null
        }
    }
    LaunchedEffect(mediaHost, mediaSession, mediaSnapshot) {
        if (mediaHost != null) mediaHost.feed(mediaSnapshot)
        else mediaSession?.replace(mediaSnapshot.entries) { key ->
            // Never accept the historical lookup's cached authorization: compare against
            // the current privacy/profile scope, fresh source and exact job membership.
            mediaSnapshot.stillAuthorizes(key, currentMediaSnapshot.value)
        }
    }
    // Fallback only: reuse the existing 10s source-status ticker, without creating a second
    // inventory worker. Connectivity/metering/power changes retry immediately; a sparse
    // 30-minute heartbeat also notices expired provider negative-cache and image-failure TTLs.
    val mediaNetworkChanges = remember(mediaSession) {
        io.github.gamesbyian.chunks.media.MediaNetworkChangeTracker()
    }
    val mediaRetryHeartbeat = remember(mediaSession) {
        io.github.gamesbyian.chunks.media.MediaInventoryRetryHeartbeat()
    }
    LaunchedEffect(mediaSession, now) {
        val active = mediaSession ?: return@LaunchedEffect
        val net = withContext(Dispatchers.IO) { mediaGraph.currentNetworkState() }
        val networkChanged = mediaNetworkChanges.changedAfterFirstObservation(net)
        val retryDue = mediaRetryHeartbeat.shouldRefresh(android.os.SystemClock.elapsedRealtime())
        // The controller remains the one lookup engine; it rechecks exact-key
        // exclusion, third-party consent, source authorization and retry TTL.
        if (networkChanged || retryDue) active.refresh()
    }
    val mediaTitles by io.github.gamesbyian.chunks.media.rememberMediaSearchTitles()
    val result = remember(sources, query, mediaTitles) {
        UnifiedTransferCatalog.query(sources, query) { key ->
            mediaTitles[io.github.gamesbyian.chunks.media.MediaTransferKey.of(key)]
        }
    }
    val addedHidden = combinedAddedTransferHidden(recentlyAddedTransfer, sources,
        result.transfers.map { it.key })
    val sourcesByEnvironment = result.sources.associateBy { it.environment }
    // ONE eligibility rule for Details' Start/Stop review and a row's quick Start/Stop.
    fun lifecycleEligible(key: UnifiedTransferKey, readCurrent: Boolean): Boolean = readCurrent &&
        sourcesByEnvironment[key.environment]?.canClaimCompleteLiveInventory == true &&
        ((key.environment == EnvironmentSnapshots.LOCAL && localPrivacy.mayShow && localRunning) ||
            (key.environment == serverId && server?.lifecycle != null &&
                verifiedCombinedLifecycleOwner(server, serverId) != null && serverPrivacy?.mayShow == true &&
                server.stale == false &&
                // rTorrent Start/Stop only supports exact v1 hashes. Keep
                // unsupported remote identities read-only before review.
                key.infoHash.matches(V1_TRANSFER_HASH)))
    val firstRunVisible = enableFirstRunWelcome && showCanonicalFirstRunWelcome(
        noPersistedJobs = noPersistedLocalJobs,
        nativeRunning = localRunning,
        nativeConfirmed = localConfirmed,
        nativeStale = localFrame.stale || LocalMutationFence.revision.blocksMutations(),
        nativeCount = localFrame.entries.size,
        hasRemoteProfile = configuredRemoteForWelcome(
            server != null, savedProfiles.isNotEmpty(), hasRememberedRemoteLogin),
        restrictedView = CombinedViewPreferences.narrowingActive(query),
        privacyAllowed = localPrivacy.mayShow,
        pendingExternalSource = pendingSharedMagnet || pendingSharedLink ||
            pendingSharedTorrent || readingSharedTorrent ||
            sharedTorrentError != null || recentlyAddedTransfer != null
    )
    val checkingFreshLocal = enableFirstRunWelcome && noPersistedLocalJobs == null &&
        !localRunning && !localConfirmed && savedProfiles.isEmpty() && server == null &&
        !hasRememberedRemoteLogin &&
        !CombinedViewPreferences.narrowingActive(query) && localPrivacy.mayShow &&
        !pendingSharedMagnet && !pendingSharedTorrent && !readingSharedTorrent

    // Hide this window from screenshots/recents whenever a protected scope is shown.
    val secure = (localPrivacy.needsSecureWindow && localPrivacy.mayShow) ||
        (serverPrivacy?.needsSecureWindow == true && serverPrivacy.mayShow) ||
        savedPrivacy.any { (id, d) -> d.needsSecureWindow && d.mayShow && listings[id]?.observedAtEpochMillis != null }
    DisposableEffect(activity, secure) {
        if (secure) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (secure) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    val listState = listState ?: rememberLazyListState()
    // A shared Android magnet must expose its exact destination choices even
    // when the Combined list was last visited far below its Add card. This
    // changes only the view position, never authorizes an Add or remote call.
    LaunchedEffect(pendingSharedMagnet, pendingSharedTorrent) {
        if (pendingSharedMagnet || pendingSharedTorrent) listState.scrollToItem(COMBINED_ADD_ITEM_INDEX)
    }
    // Reading LazyListState.layoutInfo during composition creates a feedback
    // loop: layout publishes info, composition reads it, then remeasures.
    // Capture only actual size changes and use stable scroll state for visibility.
    var viewportHeightPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val showReturnToTop = ReturnToTopPolicy.remote(
        firstVisibleIndex = listState.firstVisibleItemIndex,
        firstVisibleOffsetPx = listState.firstVisibleItemScrollOffset,
        viewportHeightPx = viewportHeightPx,
        hasTransfers = result.transfers.isNotEmpty()
    )
    val focusedKey = detailKey
    val signedInFocus = focusedKey?.takeIf { it.environment == serverId }
    val capabilityGate = remember { RemoteCapabilityProbeSessionGate() }
    // MainActivity creates a fresh CombinedServerSource each recomposition.
    // The session witness comes from its live RTorrentRemote object, not this
    // replaceable data wrapper or the persisted profile's endpoint string.
    capabilityGate.bind(server?.authenticatedSession, serverId, signedInFocus)
    val stallGate = remember { RemoteCapabilityProbeSessionGate() }
    stallGate.bind(server?.authenticatedSession, serverId, signedInFocus)
    val latestServer = rememberUpdatedState(server)
    val latestServerId = rememberUpdatedState(serverId)
    val latestFocus = rememberUpdatedState(signedInFocus)
    val latestStallAllowed = rememberUpdatedState(server?.stale == false &&
        serverPrivacy?.mayShow == true && server?.authenticatedSession != null)
    var pendingStall by remember {
        mutableStateOf<RemoteCapabilityProbeSessionGate.Ticket?>(null)
    }
    var stallEvidence by remember {
        mutableStateOf<Pair<RemoteCapabilityProbeSessionGate.Ticket, RemoteStallEvidence>?>(null)
    }
    var stallFailure by remember {
        mutableStateOf<Pair<RemoteCapabilityProbeSessionGate.Ticket, String>?>(null)
    }
    fun stillStall(ticket: RemoteCapabilityProbeSessionGate.Ticket?) =
        ticket != null && latestStallAllowed.value && stallGate.current(
            ticket, latestServer.value?.authenticatedSession,
            latestServerId.value, latestFocus.value
        )
    var pendingCapability by remember {
        mutableStateOf<RemoteCapabilityProbeSessionGate.Ticket?>(null)
    }
    var capabilityEvidence by remember {
        mutableStateOf<Pair<RemoteCapabilityProbeSessionGate.Ticket, EnvironmentCapabilitySnapshot>?>(null)
    }
    var capabilityFailure by remember {
        mutableStateOf<Pair<RemoteCapabilityProbeSessionGate.Ticket, String>?>(null)
    }
    fun stillCurrent(ticket: RemoteCapabilityProbeSessionGate.Ticket?) =
        ticket != null && capabilityGate.current(
            ticket, latestServer.value?.authenticatedSession,
            latestServerId.value, latestFocus.value
        )

    if (focusedKey != null) {
        // The same observed source collection is the only detail authority.
        // Saved servers remain read-only here, even when credentials enabled a
        // manual listing. Never switch sign-in context or reuse a matching hash.
        val snapshot = capabilityEvidence?.takeIf { stillCurrent(it.first) }?.second
        val details = UnifiedTransferDetails.resolve(
            focusedKey, sources,
            if (focusedKey.environment == EnvironmentSnapshots.LOCAL)
                EnvironmentCapabilities.local() else snapshot,
            System.currentTimeMillis()
        )
        val verifiedOwner = verifiedCombinedLifecycleOwner(server, serverId)
        val readOnlyProbe = server?.lifecycle?.probe?.takeIf {
            focusedKey.environment == serverId &&
                verifiedOwner != null &&
                serverPrivacy?.mayShow == true &&
                details.readState == TransferDetailReadState.CURRENT
        }
        val stallProbe = server?.lifecycle?.inspectStall?.takeIf {
            focusedKey.environment == serverId &&
                verifiedOwner != null &&
                serverPrivacy?.mayShow == true && !server.stale &&
                details.readState == TransferDetailReadState.CURRENT
        }
        // Only a genuinely current and complete inventory may even offer
        // lifecycle review. The *existing* batch controller owns the later
        // authorization, exact-account lease, readback and uncertain-write
        // refusal. Saved-only, locked, stale and disconnected rows stay read-only.
        val canReviewLifecycle = details.observation != null &&
            lifecycleEligible(focusedKey, details.readState == TransferDetailReadState.CURRENT)
        // Reuse the existing exact-account rTorrent Remove control inside
        // canonical Details. It owns separate destructive approval, session
        // readback, uncertain-write latch and non-force ruTorrent data cleanup.
        // This only moves presentation: no new mutation/executor/RPC authority.
        val activeRemoteForRemoval = server?.authenticatedSession as? RTorrentRemote
        val exactRemoteRow = authenticatedExactRemovalRow(focusedKey, server, serverId)
        val canShowExactRemoval = exactRemoteRow != null &&
            serverPrivacy?.mayShow == true && verifiedOwner != null
        val renameScope = aliasScopes[focusedKey.environment]
        UnifiedTransferDetailPanel(
            detail = details,
            rename = if (renameScope == null) null else ({ transfer ->
                val media by io.github.gamesbyian.chunks.media.rememberTransferMedia(
                    io.github.gamesbyian.chunks.media.MediaTransferKey.of(transfer.key), transfer.originalName)
                TransferAliasEditor(
                    originalName = transfer.originalName, hash = transfer.key.infoHash,
                    alias = transfer.displayName.takeIf { it != transfer.originalName },
                    onSave = { value ->
                        // Chunks display alias for this exact owner scope + hash only.
                        runCatching { TransferAliasStore(context).save(renameScope, transfer.key.infoHash, value) }
                            .getOrDefault(false)
                    },
                    identifiedSuggestions = identifiedMediaNameSuggestions(media.assignment))
                // Collection membership in the same authorized display-name scope.
                DetailsCollections(renameScope, transfer.key.infoHash, transfer.displayName)
            }),
            environmentLabel = labels[focusedKey.environment] ?: "Unconfigured environment",
            environmentHue = hueFor(focusedKey.environment),
            onBack = { savedDetailTarget = null },
            // A Remove's outcome is retained after its job leaves the list (incomplete or
            // uncertain deletions must stay visible), so Details keeps showing it.
            localOperations = if (focusedKey.environment == EnvironmentSnapshots.LOCAL &&
                localPrivacy?.mayShow != false &&
                (details.readState == TransferDetailReadState.CURRENT ||
                    LocalDetailsRuns.outcomes[focusedKey.infoHash] != null)) ({
                val current = details.readState == TransferDetailReadState.CURRENT
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (current && details.observation?.started == false) {
                        LocalStoppedRecheckControl(focusedKey.infoHash)
                        LocalForeignPartialStartRecovery(focusedKey.infoHash)
                    }
                    // Full Details parity with server jobs: files & priorities and Remove.
                    LocalDetailsOperations(focusedKey.infoHash, current = current, stale = localFrame.stale)
                }
            }) else null,
            exactServerOperations = if (canShowExactRemoval) ({
                val owner = requireNotNull(server)
                val mutation = requireNotNull(owner.lifecycle)
                val boundRemote = requireNotNull(activeRemoteForRemoval)
                val boundRow = requireNotNull(exactRemoteRow)
                val operationsStale = owner.stale ||
                    details.readState != TransferDetailReadState.CURRENT ||
                    sourcesByEnvironment[focusedKey.environment]
                        ?.canClaimCompleteLiveInventory != true
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RemoteHashRecheckControl(
                        remote = boundRemote, transfer = boundRow,
                        aliasScope = owner.aliasScope,
                        snapshotStale = operationsStale, mainView = owner.fullView,
                        onMutationStarted = mutation.begin, onSettled = mutation.settled
                    )
                    TransferSessionRemoveControl(
                        remote = boundRemote, transfer = boundRow,
                        snapshotStale = operationsStale,
                        mainView = owner.fullView,
                        onMutationStarted = mutation.begin,
                        onSettled = mutation.settled,
                        environmentLabel = labels[focusedKey.environment],
                        snapshotRevision = owner.snapshotRevision
                    )
                    // The SAME exact authenticated adapter and lease. File
                    // reads are explicit, bounded and privacy-gated; the
                    // already-reviewed existing priority/ordering components
                    // independently verify every consequential write.
                    CanonicalRemoteFilesControls(
                        remote = boundRemote, transfer = boundRow,
                        aliasScope = owner.aliasScope,
                        snapshotStale = operationsStale,
                        mainView = owner.fullView,
                        snapshotRevision = owner.snapshotRevision,
                        onMutationStarted = mutation.begin,
                        onSettled = mutation.settled,
                        retainDuringRefresh = details.readState == TransferDetailReadState.CURRENT ||
                            details.readState == TransferDetailReadState.STALE
                    )
                }
            }) else null,
            onOpenOwningList = openFor(focusedKey),
            onReviewStartStop = if (canReviewLifecycle) ({
                // Selection is always the one exact key just examined, not
                // another transfer sharing its infohash on a different server.
                selected = setOf(focusedKey)
                selecting = true
                showingBatchTools = true
                savedDetailTarget = null
                batchScope.launch {
                    androidx.compose.runtime.withFrameNanos { }
                    listState.scrollToItem(combinedBatchItemIndex(
                        addVisible = showNavigationFooter || pendingSharedMagnet || pendingSharedTorrent,
                        filterOptionsVisible = showingViewOptions,
                        sortOptionsVisible = showingViewOptions && showingSortOptions))
                }
            }) else null,
            onCheckCapabilities = readOnlyProbe?.let { probe ->
                {
                    val ticket = capabilityGate.begin()
                    // Do not dispatch a probe if the app changed sessions after
                    // composing the button but before the tap was delivered.
                    if (stillCurrent(ticket)) {
                        pendingCapability = ticket
                        capabilityEvidence = null
                        capabilityFailure = null
                        batchScope.launch {
                            try {
                                val observed = withContext(Dispatchers.IO) {
                                    withTimeout(15_000L) { probe() }
                                }
                                if (stillCurrent(ticket)) {
                                    capabilityEvidence = ticket!! to
                                        EnvironmentCapabilities.fromProbe(
                                            ticket.environment, observed, System.currentTimeMillis())
                                }
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                if (cancel !is kotlinx.coroutines.TimeoutCancellationException)
                                    throw cancel
                                if (stillCurrent(ticket)) capabilityFailure =
                                    ticket!! to "The server capability check timed out; no methods were verified."
                            } catch (_: Exception) {
                                if (stillCurrent(ticket)) capabilityFailure =
                                    ticket!! to "The signed-in server did not complete the read-only check."
                            } finally {
                                if (pendingCapability === ticket) pendingCapability = null
                            }
                        }
                    }
                }
            },
            capabilityCheckInProgress = stillCurrent(pendingCapability),
            capabilityCheckError = capabilityFailure?.takeIf { stillCurrent(it.first) }?.second,
            onInspectStall = stallProbe?.let { inspect ->
                {
                    val ticket = stallGate.begin()
                    if (stillStall(ticket)) {
                        pendingStall = ticket
                        stallEvidence = null
                        stallFailure = null
                        batchScope.launch {
                            try {
                                val observed = withContext(Dispatchers.IO) {
                                    withTimeout(15_000L) {
                                        inspect(ticket!!.focus.infoHash)
                                    }
                                }
                                if (stillStall(ticket)) stallEvidence = ticket!! to observed
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                if (cancel !is kotlinx.coroutines.TimeoutCancellationException)
                                    throw cancel
                                if (stillStall(ticket)) stallFailure = ticket!! to
                                    "The exact transfer check timed out. Its current state is unknown."
                            } catch (_: Exception) {
                                // Never display RPC fault bodies, d.message,
                                // tracker URLs, path names or credentials.
                                if (stillStall(ticket)) stallFailure = ticket!! to
                                    "The signed-in server could not finish the exact transfer check."
                            } finally {
                                if (pendingStall === ticket) pendingStall = null
                            }
                        }
                    }
                }
            },
            stallCheckInProgress = stillStall(pendingStall),
            stallReport = stallEvidence?.takeIf { stillStall(it.first) }?.second,
            stallCheckError = stallFailure?.takeIf { stillStall(it.first) }?.second,
            similar = { transfer ->
                SimilarTransfersHost(transfer, sources,
                    environmentLabel = { labels[it] },
                    environmentColor = { id -> hueFor(id)?.let { Color(it.argb) } ?: ChunksColors.Outline },
                    onOpen = { key -> savedDetailTarget = arrayListOf(key.environment.value, key.infoHash) })
            }
        )
    } else {
    // The same measured Transfers / Add / Options footer as the local device and
    // rTorrent lists. Each action only moves within this view: Add leads to
    // the explicit destination choices, never to an inferred environment.
    // Quick Start/Stop for one expanded row uses the SAME canonical command path as batch
    // tools (exact key, per-environment gate, readback, no blind retry) and reports truthfully.
    val quickControlsFor: @Composable (UnifiedTransferObservation) -> Unit = { row ->
        val key = row.key
        val owningSource = sourcesByEnvironment[key.environment]
        val known = row.started
        val live = owningSource?.availability == EnvironmentAvailability.LIVE
        val block = when {
            known == null -> "This transfer's running state is not known yet. Refresh before changing it."
            lifecycleEligible(key, live) -> null
            key.environment != EnvironmentSnapshots.LOCAL && key.environment != serverId ->
                "Connect to this server in Connections to control its transfers."
            key.environment == EnvironmentSnapshots.LOCAL && !localRunning ->
                "Open this device's transfer controls to start its engine first."
            else -> "This list is not current yet. Refresh or reconnect before changing this transfer."
        }
        val running = quickRunning == key
        val label = if (known == true) "Stop" else "Start"
        Column(verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.testTag(ChunksSemantics.TRANSFER_ROW_QUICK_CONTROLS)) {
            // Plain layout: rows measure intrinsically for their environment accent bar.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val start = known != true
                    quickRunning = key
                    quickResults = quickResults - key
                    val snapshot = sources
                    val owner = batchIdentity
                    val lifecycle = server?.lifecycle
                    val capturedServer = server
                    val targetServer = serverId
                    batchScope.launch {
                        if (latestBatchIdentity.value != owner) return@launch
                        try {
                            val report = describeCombinedBatchSafely({ labels[it] ?: it.value }) {
                                runCombinedLifecycle(setOf(key), start, snapshot, lifecycle, targetServer,
                                    serverSource = capturedServer,
                                    reviewStillCurrent = { latestBatchIdentity.value == owner })
                            }
                            if (latestBatchIdentity.value == owner) quickResults = quickResults + (key to report)
                        } finally {
                            if (latestBatchIdentity.value == owner) quickRunning = null
                        }
                    }
                }, enabled = block == null && quickRunning == null && !batchRunning,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                        contentDescription = "$label ${row.displayName}"
                    }) {
                    Text(if (running) (if (known == true) "Stopping…" else "Starting…") else label)
                }
            }
            block?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            quickResults[key]?.forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
    }
    Column(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f)) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)
                .onSizeChanged { viewportHeightPx = it.height },
            state = listState, contentPadding = PaddingValues(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The global wordmark and footer already supply location and navigation.
        if (showNavigationFooter) item {
            CombinedTransfersHeader(onBack)
        } else if (!firstRunVisible) item {
            // Keep one compact, spoken view identity without a duplicate Back.
            Text("Transfers", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(ChunksSemantics.TRANSFERS_HEADING).semantics { heading() })
        }
        if (firstRunVisible) {
            item(key = "canonical-fresh-welcome") {
                NoTransfersWelcome(
                    onAdd = onWelcomeAdd ?: onAddToLocal ?: onOpenLocal,
                    onConnections = onConnections
                )
            }
        } else if (checkingFreshLocal) {
            item(key = "canonical-fresh-probe") {
                Text("Checking transfers on this device…",
                    style = MaterialTheme.typography.bodyMedium)
            }
        } else {
        // Add lives in its own destination when this is the app's primary list.
        // A shared source still surfaces a target choice, never auto-submits.
        if (showNavigationFooter || pendingSharedMagnet || pendingSharedTorrent) item(key = COMBINED_ADD_ITEM_KEY) {
            CombinedAddActions(
                serverLabel = serverId?.let { labels[it] },
                hasOtherSavedServers = savedProfiles.any { EnvironmentId(it.id) != serverId },
                onOpenLocal = onAddToLocal ?: onOpenLocal,
                onAddToServer = onAddToServer,
                pendingSharedMagnet = pendingSharedMagnet,
                pendingSharedLink = pendingSharedLink,
                pendingSharedTorrent = pendingSharedTorrent,
                readingSharedTorrent = readingSharedTorrent,
                sharedTorrentError = sharedTorrentError,
                localHue = hueFor(EnvironmentSnapshots.LOCAL),
                serverHue = serverId?.let { hueFor(it) }
            )
        }
        // One persistent row of environment toggles, always visible above the list: no
        // Filters panel, Apply or Hide step. Each toggles only its own environment.
        if (!firstRunVisible) item(key = "environment-toggles") {
            val available = labels.keys.toSet()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .testTag(ChunksSemantics.TRANSFERS_ENVIRONMENT_TOGGLES),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // With up to three environments the individual chips fit; All
                // would duplicate their default selected state and waste width.
                if (shouldShowAllEnvironmentChip(labels.size)) FilterChip(
                    selected = query.scope == EnvironmentViewScope.All,
                    onClick = { update(query.copy(scope = EnvironmentViewScope.All)) },
                    label = { Text("All") }, modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { contentDescription = "Show all environments" })
                labels.forEach { (id, label) ->
                    val visible = environmentVisible(query.scope, id)
                    // A locked/unobserved inventory must say ?, never invent 0.
                    val source = sources.firstOrNull { it.environment == id }
                    val availability = source?.availability
                    val unavailable = availability == EnvironmentAvailability.OFFLINE ||
                        availability == EnvironmentAvailability.LOCKED
                    val shortName = environmentToggleName(id, rowLabels[id] ?: label)
                    val count = environmentScopeCount(source)
                    FilterChip(selected = visible,
                        onClick = { update(query.copy(scope =
                            toggleEnvironmentVisibility(query.scope, id, available))) },
                        label = { Text("$shortName ($count)" + when (availability) {
                            EnvironmentAvailability.OFFLINE -> " · offline"
                            EnvironmentAvailability.LOCKED -> " · locked"
                            else -> ""
                        }, maxLines = 1) },
                        leadingIcon = { EnvironmentDot(hueFor(id)) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = (if (visible) "Showing " else "Hidden: ") +
                                label + ", " + (if (count == "?") "count unknown" else "$count transfers") +
                                (if (unavailable) ", currently unavailable" else "")
                        })
                }
            }
            if (query.scope == EnvironmentViewScope.Only(emptySet())) Text(
                "No environments are selected. Tap one above to show its transfers.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp))
        }
        // One compact row: live search plus disclosure controls, following
        // the environment chips. Batch and filter panels are still the same
        // exact-account safety-gated sections; these icons only reveal them.
        item(key = "transfers-search-tools") {
            TransfersInlineToolbar(
                search = query.search,
                onSearchChange = {
                    queryTouched = true
                    query = query.copy(search = it)
                    searchState?.value = it
                },
                filtersExpanded = showingViewOptions,
                batchExpanded = showingBatchTools,
                filtersActive = query.filter != UnifiedTransferFilter.ALL ||
                    query.sortKey != UnifiedSortKey.NAME ||
                    query.direction != TransferSortOrdering.naturalDirection(query.sortKey.orderingKind()) ||
                    query.groupByEnvironment,
                onFilters = {
                    showingViewOptions = !showingViewOptions
                    if (!showingViewOptions) showingSortOptions = false
                },
                onBatch = { showingBatchTools = !showingBatchTools }
            )
        }
        // Explain a nondefault view and expose recovery inside its disclosure,
        // never as a banner or oversized CTA above every transfer card.
        if (showingViewOptions &&
            (query.scope != EnvironmentViewScope.All ||
                query.filter != UnifiedTransferFilter.ALL)) item {
            val scopeName = when (val scope = query.scope) {
                EnvironmentViewScope.All -> "All environments"
                is EnvironmentViewScope.Only ->
                    if (scope.ids.isEmpty()) "No environments"
                    else if (scope.ids.size == 1) labels[scope.ids.first()] ?: "One environment"
                    else "${scope.ids.size} environments"
            }
            val filterName = when (query.filter) {
                UnifiedTransferFilter.ALL -> "All transfers"
                UnifiedTransferFilter.STARTED -> "Started"
                UnifiedTransferFilter.STOPPED -> "Stopped"
                UnifiedTransferFilter.COMPLETE -> "Complete"
                UnifiedTransferFilter.TRANSFERRING -> "Transferring"
                UnifiedTransferFilter.ATTENTION -> "Needs attention"
            }
            Text("Viewing: " + scopeName + " · " + filterName,
                style = MaterialTheme.typography.bodySmall)
            if (!addedHidden && result.transfers.isNotEmpty()) {
                OutlinedButton(
                    onClick = { update(CombinedViewPreferences.showAll(query)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("Show all transfers") }
                Text("New downloads may be hidden by this view. Resetting filters never changes your Add destination.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (showingViewOptions && query.search.isNotBlank() &&
            query.filter == UnifiedTransferFilter.ALL &&
            query.scope == EnvironmentViewScope.All) item {
            Text("New downloads may not match this search. Clear search to see them.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (showingViewOptions) item {
            Text("Filter transfers", style = MaterialTheme.typography.labelLarge)
            FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    UnifiedTransferFilter.ALL to "All transfers",
                    UnifiedTransferFilter.STARTED to "Started",
                    UnifiedTransferFilter.STOPPED to "Stopped",
                    UnifiedTransferFilter.COMPLETE to "Complete",
                    UnifiedTransferFilter.TRANSFERRING to "Transferring",
                    UnifiedTransferFilter.ATTENTION to "Attention"
                ).forEach { (filter, label) ->
                    FilterChip(selected = query.filter == filter,
                        onClick = { if (query.filter != filter) update(query.copy(filter = filter)) },
                        label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        if (showingViewOptions) item {
            OutlinedButton(
                onClick = { showingSortOptions = !showingSortOptions },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                val name = CombinedSortChoices.firstOrNull { it.first == query.sortKey }?.second ?: "Name"
                Text((if (showingSortOptions) "Hide" else "Change") + " sort & grouping · " +
                    name + " · " + TransferSortOrdering.label(query.sortKey.orderingKind(), query.direction) +
                    (if (query.groupByEnvironment) " · By environment" else " · Together"))
            }
        }
        if (showingViewOptions && showingSortOptions) item {
            Text("Sort by", style = MaterialTheme.typography.labelLarge)
            FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                onReverse = {
                    val current = query
                    update(current.copy(direction = TransferSortOrdering.reversed(current.direction)))
                })
            // Stacked so the label can wrap at 320dp and 200% text without pushing the switch off-screen.
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Group by environment", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                Switch(checked = query.groupByEnvironment,
                    onCheckedChange = { update(query.copy(groupByEnvironment = it)) },
                    // The Switch itself, not only its enclosing Row, must have
                    // an accessible 48dp semantics/touch surface at large text.
                    modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { contentDescription = "Group by environment" })
            }
        }
        // Batch controls appear immediately upon request, ahead of rows only
        // for that explicit task. They never consume the initial first fold.
        if (showingBatchTools) {
        item {
            key(batchIdentity) {
            CombinedBatchSection(
                selecting = selecting,
                onSelecting = { selecting = it; if (!it) selected = emptySet(); batchResult = emptyList() },
                selected = selected,
                visible = result.transfers.map { it.key }.toSet(),
                labels = labels,
                running = batchRunning || quickRunning != null,
                result = batchResult,
                onClear = { selected = emptySet() },
                onRun = { start ->
                    batchRunning = true
                    batchResult = emptyList()
                    val chosen = selected
                    val snapshot = sources
                    val owner = batchIdentity
                    val lifecycle = server?.lifecycle
                    val capturedServer = server
                    val targetServer = serverId
                    batchScope.launch {
                        // Dispatch may be queued behind a server/account switch.
                        if (latestBatchIdentity.value != owner) return@launch
                        var unconfirmedLocalStops = 0
                        try {
                            val report = describeCombinedBatchSafely({ labels[it] ?: it.value }) {
                                runCombinedLifecycle(chosen, start, snapshot, lifecycle, targetServer,
                                    serverSource = capturedServer,
                                    reviewStillCurrent = { latestBatchIdentity.value == owner },
                                    onLocalStopCheckpointUnconfirmed = { unconfirmedLocalStops++ })
                            }
                            // A late completion is evidence about the OLD owner only.
                            if (latestBatchIdentity.value == owner) {
                                val warning = io.github.gamesbyian.chunks.model.LocalStoppedCheckpointBudget
                                    .warning(unconfirmedLocalStops)
                                batchResult = if (warning.isEmpty()) report else report + warning.trim()
                            }
                        } finally {
                            if (latestBatchIdentity.value == owner) {
                                selected = emptySet()
                                batchRunning = false
                            }
                        }
                    }
                }
            )
            }
        }
        }
        // Inventory count and aggregate live speeds belong together ABOVE the
        // transfer rows, not at the end of the list. Preserve incomplete-source
        // and unknown-rate caveats rather than inventing a precise total.
        item(key = "combined-compact-count") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(combinedShowingText(result.transfers.size,
                    result.sources.sumOf { it.transfers.size }) +
                    if (result.hasIncompleteOrStaleSource) " · some environments are missing or out of date" else "",
                    style = MaterialTheme.typography.bodySmall)
                Text(combinedTotalsText(io.github.gamesbyian.chunks.model.UnifiedTransferTotals.of(result)),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        // A local-only cold launch can have no running native engine, even
        // when saved jobs await explicit restoration. Never call that state
        // "zero transfers". Offer the existing source review without starting
        // an engine, choosing a Place or authorizing an Add.
        if (server == null && savedProfiles.isEmpty() && onAddToLocal != null &&
            result.transfers.isEmpty() && !CombinedViewPreferences.narrowingActive(query)) {
            item(key = "combined-first-local-intake") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Transfers on this device",
                        style = MaterialTheme.typography.titleMedium)
                    Text(localOnlyEmptyInventoryMessage(
                        sourcesByEnvironment[EnvironmentSnapshots.LOCAL]
                            ?.canClaimCompleteLiveInventory == true),
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onAddToLocal,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Review a new torrent on this device")
                    }
                }
            }
        }
        // Even when a saved subset or a filter hides every row, the way back
        // remains visible without finding an offscreen chip or opening Settings.
        // This changes presentation only, never an Add destination or command target.
        if (addedHidden) item(key = "combined-added-hidden") {
            CombinedAddedHiddenNotice(labels[recentlyAddedTransfer?.environment].orEmpty(),
                onShowAll = { update(CombinedViewPreferences.showAll(query)) })
        }
        if (!addedHidden && result.transfers.isEmpty() && CombinedViewPreferences.narrowingActive(query)) {
            item(key = "recover-empty-combined-view") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No transfers are visible with these view choices. " +
                        "Locked or offline environments may still need attention.",
                        style = MaterialTheme.typography.bodySmall)
                    if (query.filter != UnifiedTransferFilter.ALL ||
                        query.scope != EnvironmentViewScope.All) {
                        Text(
                            "New downloads may be hidden by this view. Show all transfers " +
                                "clears view filters, not your Add destination.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedButton(
                        onClick = { update(CombinedViewPreferences.showAll(query)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Show all transfers") }
                }
            }
        }
        if (query.groupByEnvironment) {
            result.groups.forEach { group ->
                item(key = "header-" + group.source.environment.value) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EnvironmentDot(hueFor(group.source.environment))
                        Text(labels[group.source.environment].orEmpty() + " · " + group.transfers.size +
                            " shown", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() })
                    }
                }
                items(group.transfers, key = { "row-" + it.key.environment.value + it.key.infoHash }) { row ->
                    CombinedTransferRow(row, labels[row.key.environment].orEmpty(), hueFor(row.key.environment),
                    visibleEnvironmentLabel = rowLabels[row.key.environment],
                    source = sourcesByEnvironment[row.key.environment],
                        selection = if (selecting) row.key in selected else null,
                        onSelect = { on -> selected = if (on) selected + row.key else selected - row.key },
                        onDetails = { savedDetailTarget = arrayListOf(row.key.environment.value, row.key.infoHash) },
                        expanded = rowKeyText(row.key) in expandedRows,
                        onToggleExpanded = if (selecting) null else ({
                            val id = rowKeyText(row.key)
                            expandedRows = if (id in expandedRows) expandedRows - id else expandedRows + id
                        }),
                        quickControls = if (selecting) null else ({ quickControlsFor(row) }))
                }
            }
        } else {
            items(result.transfers, key = { "row-" + it.key.environment.value + it.key.infoHash }) { row ->
                CombinedTransferRow(row, labels[row.key.environment].orEmpty(), hueFor(row.key.environment),
                    visibleEnvironmentLabel = rowLabels[row.key.environment],
                    source = sourcesByEnvironment[row.key.environment],
                    selection = if (selecting) row.key in selected else null,
                    onSelect = { on -> selected = if (on) selected + row.key else selected - row.key },
                        onDetails = { savedDetailTarget = arrayListOf(row.key.environment.value, row.key.infoHash) },
                        expanded = rowKeyText(row.key) in expandedRows,
                        onToggleExpanded = if (selecting) null else ({
                            val id = rowKeyText(row.key)
                            expandedRows = if (id in expandedRows) expandedRows - id else expandedRows + id
                        }),
                        quickControls = if (selecting) null else ({ quickControlsFor(row) }))
            }
        }
        // Detailed environment status is available after the inventory, rather
        // than charging every user a tall status panel before the first row.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showingSourceStatus || result.sources.size > 1 || result.hasIncompleteOrStaleSource ||
                    listings.values.any { it.error != null || it.loading }) {
                    OutlinedButton(
                        onClick = { showingSourceStatus = !showingSourceStatus },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text(if (showingSourceStatus) "Hide environment status"
                        else "Environment status · ${result.sources.size} sources") }
                }
                // The first-fold count is recycled when the user scrolls down
                // to investigate a failed server. Keep its freshness warning
                // beside the exact-source rows without moving diagnostics up.
                if (showingSourceStatus && result.hasIncompleteOrStaleSource) {
                    Text("some environments are missing or out of date · last-known rows are not current",
                        style = MaterialTheme.typography.bodySmall,
                        color = ChunksColors.Yellow)
                }
                // When the first-fold count has scrolled away, preserve an
                // explicit stale-source warning here with the detailed status.
                if (showingSourceStatus && result.hasIncompleteOrStaleSource) {
                    Text("Some environments are missing or out of date. Exact per-source status follows.",
                        style = MaterialTheme.typography.bodySmall, color = ChunksColors.Yellow)
                }
                if (showingSourceStatus) result.sources.forEach { source ->
                    val listing = activeSavedScopes[source.environment]?.let { witness ->
                        listings[source.environment]?.takeIf { it.sourceAliasScope == witness }
                    }
                    val status = when {
                        source.environment in invalidSavedProfiles ->
                            "Saved account identity could not be verified; reconnect this profile"
                        privacyEvidence == null ->
                            "Privacy policy not yet verified; transfers hidden"
                        listing?.loading == true -> "Loading list…"
                        listing?.error != null && listing.observedAtEpochMillis == null ->
                            "Could not load (" + listing.error + ")"
                        listing?.error != null ->
                            "Refresh failed (" + listing.error + "), showing last list"
                        else -> availabilityText(source)
                    }
                    Text(labels[source.environment].orEmpty() + ": " + status + " · " +
                        source.transfers.size + " transfers", style = MaterialTheme.typography.bodySmall)
                    loadable.firstOrNull { it.id == source.environment.value }?.let { profile ->
                        val label = labels[source.environment].orEmpty()
                        val verb = if (listing?.observedAtEpochMillis == null) "Load" else "Refresh"
                        OutlinedButton(
                            onClick = { loadSavedServers(listOf(profile)) },
                            enabled = listing?.loading != true,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                                contentDescription = "$verb transfers on $label; read only"
                                stateDescription = if (listing?.loading == true) "Loading" else "Ready"
                            }
                        ) { Text(if (listing?.loading == true) "Loading $label transfers…"
                            else "$verb $label transfers") }
                    }
                }
                if (loadable.isNotEmpty()) OutlinedButton(enabled = listings.none { (id, listing) ->
                    listing.loading && activeSavedScopes[id] == listing.sourceAliasScope
                },
                    onClick = {
                        loadSavedServers(loadable)
                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (listings.isEmpty()) "Load other saved servers (read-only)" else "Refresh other saved servers")
                }
                if (result.missingSelectedEnvironments.isNotEmpty()) Text(
                    (result.missingSelectedEnvironments.size.let { n -> if (n == 1) "1 selected environment is" else "$n selected environments are" }) + " no longer set up. " +
                        "Choose All or reselect environments.", style = MaterialTheme.typography.bodySmall,
                    color = ChunksColors.Yellow)
            }
        }
        // Colour choices live under Connections (each saved profile) and
        // Options > Appearance (Local and global display preference).
        // The client chips above are the only list-scope controls. Keep the
        // local-engine maintenance route under advanced Filters, not after rows.
        if (showingViewOptions) item {
            OutlinedButton(onClick = onOpenLocal,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Device transfer controls")
            }
        }
        } // genuine Welcome/probe has no management chrome or list actions
        }
        ReturnToTopControl(
            visible = showReturnToTop && !firstRunVisible && !checkingFreshLocal,
            onClick = {
            batchScope.launch {
                if (ReturnToTopPolicy.animationsEnabled(context)) listState.animateScrollToItem(0)
                else listState.scrollToItem(0)
            }
        }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp))
    } // scroll viewport
    if (showNavigationFooter) AuthenticatedTransfersFooter(
        optionsOpen = showingViewOptions,
        onTransfers = {
            batchScope.launch {
                if (ReturnToTopPolicy.animationsEnabled(context)) listState.animateScrollToItem(0)
                else listState.scrollToItem(0)
            }
        },
        onAdd = { batchScope.launch { listState.scrollToItem(COMBINED_ADD_ITEM_INDEX) } },
        onOptions = {
            showingViewOptions = !showingViewOptions
            batchScope.launch { listState.scrollToItem(COMBINED_VIEW_ITEM_INDEX) }
        },
        onConnections = onConnections,
        addDescription = "Choose where to add a torrent"
    )
    } // measured footer + viewport
    }
}

/** Locate the selected exact-job batch review after variable disclosure items.
 * Changes in the leading LazyColumn item order must update this helper and test.
 */
internal fun combinedBatchItemIndex(
    addVisible: Boolean,
    filterOptionsVisible: Boolean,
    sortOptionsVisible: Boolean
): Int = 3 + (if (addVisible) 1 else 0) +
    (if (filterOptionsVisible) 3 else 0) +
    (if (sortOptionsVisible) 1 else 0)

/** A confirmed empty engine is distinct from a pending or locked read. */
internal fun localOnlyEmptyInventoryMessage(confirmedCurrent: Boolean): String =
    if (confirmedCurrent) "No transfers on this device yet. Review a torrent to add one."
    else "No verified transfer list is available yet. Saved transfers may need " +
        "to be restored in device controls."

/** Unconditional leading items: header (0), Add destinations (1), batch (2), view (3). */
private const val COMBINED_ADD_ITEM_KEY = "combined-add-destinations"
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
private val META_PLACEHOLDER = Regex("(?i)^([0-9a-f]{40})\\.meta$")
private val V1_TRANSFER_HASH = Regex("[0-9a-f]{40}")

internal fun compactTransferTitle(name: String): String {
    val meta = META_PLACEHOLDER.matchEntire(name.trim()) ?: return name
    return "Magnet " + meta.groupValues[1].take(8).lowercase() + "… (fetching metadata)"
}

internal fun rowKeyText(key: UnifiedTransferKey): String = key.environment.value + "|" + key.infoHash

/** Compact toggle text: the device, or a server's short profile name without its prefix. */
internal fun environmentToggleName(id: EnvironmentId, label: String): String =
    if (id == EnvironmentSnapshots.LOCAL) "Local"
    else label.removePrefix("Server · ").trim().ifEmpty { label }

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
private fun CombinedTransferRow(
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
        Column(Modifier.weight(1f).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)
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
                TransferRowArtwork(row.key, row.originalName,
                    hidden = source?.availability == EnvironmentAvailability.LOCKED)
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
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = "Details for ${row.displayName} on $environmentLabel"
                    }) { Text("Details", style = MaterialTheme.typography.labelMedium) }
            }
            // A time estimate needs a live, complete source; last-known facts never get one.
            Text(combinedCompactRowFacts(row, fresh = source?.canClaimCompleteLiveInventory == true),
                style = MaterialTheme.typography.bodySmall)
            row.progressPpm?.let { ppm ->
                ChunkedProgressBar(
                    progress = ppm / 1_000_000f,
                    modifier = Modifier.fillMaxWidth(),
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
                else if (!serverLifecycle.begin()) refusals[environment] = "server is busy or refreshing"
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
            } else refusals[environment] = "only listed here; sign in to this server to change it"
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
        .background(hue?.let { Color(it.argb) } ?: ChunksColors.TextSecondary))
}

/** Live-only totals in words, saying what is unknown or left out. */
/** "Showing 4", or "Showing 1 of 4" when search or a filter narrows the selected environments. */
internal fun combinedShowingText(shown: Int, inSelectedEnvironments: Int): String =
    if (shown >= inSelectedEnvironments) "Showing $shown" else "Showing $shown of $inSelectedEnvironments"

internal fun combinedTotalsText(t: io.github.gamesbyian.chunks.model.UnifiedTransferTotals): String =
    "Live total: ↓ " + (if (t.downloadTotalBeyondRange) "exceeds reporting range"
                         else Units.bytes(t.downloadBytesPerSecond) + "/s") +
        " · ↑ " + (if (t.uploadTotalBeyondRange) "exceeds reporting range"
                    else Units.bytes(t.uploadBytesPerSecond) + "/s") +
        " across ${t.liveRows} transfers" +
        (if (t.rowsWithUnknownRate > 0) " · ${t.rowsWithUnknownRate} with rates not reported" else "") +
        (if (t.excludedEnvironments > 0) " · " + (if (t.excludedEnvironments == 1) "1 environment" else "${t.excludedEnvironments} environments") + " excluded (not fully observed)" else "")


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
