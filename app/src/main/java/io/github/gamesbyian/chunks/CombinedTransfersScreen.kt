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
    val snapshotRevision: Long = 0L,
    /** Read-only refresh. Unlike lifecycle.settled, it does not release a mutation lease. */
    val refresh: () -> Unit = {}
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

/**
 * Listings retained across a screen re-entry: a cancelled in-flight read keeps
 * its last-known rows/error (no longer loading) or is dropped if it had none.
 */
internal fun retainedSavedListings(
    listings: Map<EnvironmentId, SavedServerListing>
): Map<EnvironmentId, SavedServerListing> = listings.mapNotNull { (id, listing) ->
    when {
        !listing.loading -> id to listing
        listing.observedAtEpochMillis != null || listing.error != null -> id to listing.copy(loading = false)
        else -> null
    }
}.toMap()

/** A listing older than this is shown as out of date until a newer read succeeds. */
internal const val SAVED_LISTING_FRESH_MS = EnvironmentSnapshots.SERVER_INVENTORY_FRESH_MS

/**
 * Restore-connections policy for saved, non-active profiles (UX "Restore
 * connections, not merely credentials"): their `main` inventory is READ
 * automatically while Transfers is visible, so chips and rows reflect every
 * configured client without a bottom-of-list tap. This only governs *when* the
 * existing fenced, privacy-gated, read-only loader runs; it never signs in,
 * switches the active server, sends a command or retries a write.
 *
 * Healthy reads refresh inside the freshness window so a working source is not
 * shown as out of date; a failed read backs off to a slower cadence, and an
 * in-flight read is never duplicated. Attempts are keyed by exact profile ID
 * plus verified account scope, so a reconfigured account is read afresh rather
 * than inheriting the previous account's schedule or rows.
 */
internal object SavedProfileAutoRead {
    const val HEALTHY_REFRESH_MS = 60_000L
    const val FAILED_RETRY_MS = 300_000L
    const val TICK_MS = 5_000L

    data class Key(val environment: EnvironmentId, val aliasScope: String)

    fun due(listing: SavedServerListing?, lastAttemptAt: Long?, now: Long): Boolean {
        if (listing?.loading == true) return false
        // Never read under this exact identity, or its rows were retired
        // (privacy lock / account change) and must be restored afresh.
        if (lastAttemptAt == null || listing == null) return true
        // A device clock moved backwards must not suppress reads indefinitely.
        if (now < lastAttemptAt) return true
        val wait = if (listing.error != null) FAILED_RETRY_MS else HEALTHY_REFRESH_MS
        return now - lastAttemptAt >= wait
    }
}

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
    initialAddSearch: String? = null,
    onManualSearchEdited: () -> Unit = {},
    addReceipts: List<UniversalAddTransferReceipt> = emptyList(),
    onReviewAdd: (EnvironmentId) -> Unit = {},
    /**
     * Caller-owned last read-only listings of other saved profiles, so returning
     * from Add/Connections/Options shows last-known counts and rows instead of
     * `?` while the next automatic read runs. Rows are still shown only when the
     * exact profile scope and its privacy gate currently allow them; the caller
     * must drop this state on any privacy lifecycle change.
     */
    savedListingsState: androidx.compose.runtime.MutableState<Map<EnvironmentId, SavedServerListing>>? = null,
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
    onDetailVisibilityChanged: (Boolean) -> Unit = {},
    /** Explicit All environments navigation is distinct from a saved profile link. */
    showAllEnvironmentsRevision: Long = 0L,
    onShowAllEnvironmentsConsumed: (Long) -> Unit = {},
    /** Host opt-in: genuine first-run omits all inventory-management chrome. */
    enableFirstRunWelcome: Boolean = false,
    /** True for an existing verified vault login not yet represented by a roster. */
    hasRememberedRemoteLogin: Boolean = false,
    onWelcomeAdd: (() -> Unit)? = null,
    /** Authenticated read-only profiles for additional remote protocols. */
    protocolProfiles: List<CombinedReadOnlyProtocolProfile> = emptyList(),
    /** The host checks the captured saved record or transient session against live ownership. */
    protocolOwnerCurrent: (CombinedReadOnlyProtocolProfile) -> Boolean = { true }
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    // Persistent preference reads run off the composition thread. A saved
    // snapshot cannot replace a scope/filter/search chosen while it loads.
    var query by remember {
        mutableStateOf(UnifiedTransferQuery(search = initialAddSearch ?: searchState?.value.orEmpty()))
    }
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
        if (next.search != query.search) {
            searchState?.value = next.search
            onManualSearchEdited()
        }
        query = next
        // Save at the explicit event, not during composition; no queued write
        // can reorder a newer choice behind a late preference read.
        (loadedPreferences ?: CombinedViewPreferences(context)).save(next.copy(search = ""))
    }

    // Only an explicit "All clients" action may clear the narrower
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
        val eligible = (savedProfiles.map { it.id } + protocolProfiles.map { it.id }).toSet() +
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

    // Local: observe a running engine or restore an authorized saved session
    // stopped, as the device controls do. No saved session state means no start.
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
    LaunchedEffect(detailKey) { onDetailVisibilityChanged(detailKey != null) }
    androidx.activity.compose.BackHandler(detailKey != null) { savedDetailTarget = null }
    val batchScope = rememberCoroutineScope()
    val listingsHolder = savedListingsState
        ?: remember { mutableStateOf<Map<EnvironmentId, SavedServerListing>>(emptyMap()) }
    var listings by listingsHolder
    // A read that was in flight when an earlier screen left composition was
    // cancelled with it; never let its loading flag block the next read.
    LaunchedEffect(listingsHolder) { listings = retainedSavedListings(listings) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); now = System.currentTimeMillis() } }

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
    val protocolIdentityScopes = protocolProfiles.map { it.id to it.backend }
    val protocolPrivacyScopes = remember(protocolIdentityScopes) {
        protocolProfiles.associate { profile ->
            profile.environment to CombinedReadOnlyProtocolAdmission.privacyScope(profile)
        }
    }
    val savedPrivacyScopes = remember(savedScopeIdentities) {
        savedProfiles.associate { profile ->
            EnvironmentId(profile.id) to profile.aliasScope
                ?.takeIf { EnvironmentSnapshots.serverIdOfAliasScope(it)?.value == profile.id }
                ?.let(RemoteTransferPrivacyScope::fromAuthenticatedAliasScope)
        }
    }
    val requestedPrivacyScopes = remember(serverPrivacyScope, savedPrivacyScopes, protocolPrivacyScopes) {
        buildSet {
            add(localPrivacyScope)
            serverPrivacyScope?.let { add(it) }
            savedPrivacyScopes.values.filterNotNull().forEach { add(it) }
            protocolPrivacyScopes.values.filterNotNull().forEach { add(it) }
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
    val localReaderMounted = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    DisposableEffect(Unit) {
        localReaderMounted.set(true)
        onDispose { localReaderMounted.set(false) }
    }
    val mayRestoreLocal = rememberUpdatedState {
        // The worker may run after a lock, policy/lifecycle revision, or route
        // disposal but before another composition. Never borrow its old grant.
        localReaderMounted.get() && localPrivacy.mayShow &&
            privacyEvidence?.current(requestedPrivacyScopes,
                TransferPrivacyPolicyStore.revision, TransferPrivacyRuntime.lifecycleRevision) == true &&
            privacySession.mayAct(localPrivacyScope)
    }
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
            CombinedLocalReader.pump.tryObserve {
                // Reuse the existing stopped-only restore path. Fresh/remote-only
                // installs with no saved local session never open a native engine.
                // Unknown/locked privacy cannot initiate restoration.
                val engine = LocalEngineHolder.current() ?: if (mayRestoreLocal.value())
                    LocalEngineHolder.restoreIfSaved(context) else null
                engine?.list() to sample
            }
            delay(1_000)
        }
    }
    val serverPrivacy = serverPrivacyScope?.let {
        privacyEvidence?.decision(it, privacySession, deviceSecure) ?: CombinedPrivacyEvidence.LOCKED
    }
    val savedPrivacy = savedPrivacyScopes.mapValues { (_, scope) ->
        privacyEvidence?.decision(scope, privacySession, deviceSecure) ?: CombinedPrivacyEvidence.LOCKED
    }
    val protocolAllowed = protocolPrivacyScopes.filter { (_, privacyScope) ->
        privacyScope != null &&
            privacyEvidence?.decision(privacyScope, privacySession, deviceSecure)?.mayShow == true
    }.keys
    val protocolSources = rememberCombinedProtocolSources(protocolProfiles, protocolAllowed, now)
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
    // Privacy evidence is re-read asynchronously on every (re)entry. Until it
    // arrives nothing saved is shown (sources require activeSavedScopes), but a
    // retained listing for an unchanged, verifiable profile is not discarded.
    val privacyPending = privacyEvidence == null
    LaunchedEffect(activeSavedScopes, privacyPending) {
        savedListingFence.retireOutside(activeSavedScopes)
        // A profile with changed credentials/endpoint or a locked gate must not
        // leave its old rows or network status in this view.
        listings = listings.filter { (id, listing) ->
            activeSavedScopes[id]?.let { it == listing.sourceAliasScope }
                ?: (privacyPending && savedProfiles.any { profile ->
                    profile.id == id.value && profile.aliasScope != null &&
                        profile.aliasScope == listing.sourceAliasScope &&
                        SavedProfileIdentity.matches(profile.id, profile.aliasScope)
                })
        }
    }
    val loadable = savedProfiles.filter { profile ->
        val id = EnvironmentId(profile.id)
        id != serverId && profile.listOnly != null && profile.aliasScope != null &&
            activeSavedScopes[id] == profile.aliasScope
    }
    // Last read attempt per exact profile + account scope (automatic or tapped).
    val autoReadAttempts = remember { mutableMapOf<SavedProfileAutoRead.Key, Long>() }
    // Automatic restore, the all-server and exact-server buttons share the same fenced, read-only path.
    fun loadSavedServers(profiles: List<CombinedSavedProfile>) {
        profiles.forEach { profile ->
            val id = EnvironmentId(profile.id)
            val load = profile.listOnly ?: return@forEach
            val identity = profile.aliasScope ?: return@forEach
            if (activeSavedScopes[id] != identity || listings[id]?.loading == true) return@forEach
            autoReadAttempts[SavedProfileAutoRead.Key(id, identity)] = System.currentTimeMillis()
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

    // Restore every other saved profile's read-only inventory without a tap.
    // The loader re-checks exact identity and privacy itself, and drops any
    // response whose account scope changed while it was in flight.
    val currentLoadable = rememberUpdatedState(loadable)
    val currentLoader = rememberUpdatedState<(List<CombinedSavedProfile>) -> Unit> {
        loadSavedServers(it)
    }
    LaunchedEffect(Unit) {
        while (true) {
            val nowMs = System.currentTimeMillis()
            val due = currentLoadable.value.filter { profile ->
                val scope = profile.aliasScope ?: return@filter false
                val key = SavedProfileAutoRead.Key(EnvironmentId(profile.id), scope)
                val listing = listings[key.environment]?.takeIf { it.sourceAliasScope == scope }
                SavedProfileAutoRead.due(listing, autoReadAttempts[key], nowMs)
            }
            if (due.isNotEmpty()) currentLoader.value(due)
            delay(SavedProfileAutoRead.TICK_MS)
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
        savedScopes = (savedProfiles.map { it.id to it.aliasScope } +
            protocolProfiles.map { it.id to it.identityWitness }).sortedBy { it.first },
        privacyEpoch = privacyRevision.toString(),
        localVisible = localPrivacy.mayShow,
        serverVisible = serverPrivacy?.mayShow,
        savedVisibility = (savedPrivacy.map { (id, decision) -> id.value to decision.mayShow } +
            protocolProfiles.map { it.id to (it.environment in protocolAllowed) })
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
        protocolProfiles.forEach { profile ->
            putIfAbsent(profile.environment,
                profile.backend.name.lowercase().replaceFirstChar { it.uppercase() } + " · " + profile.label)
        }
    }
    val rowLabels = remember(labels, savedProfiles, server?.aliasScope) {
        compactEnvironmentLabels(labels, buildMap {
        put(EnvironmentSnapshots.LOCAL, LOCAL_ENVIRONMENT_LABEL)
        savedProfiles.forEach { profile ->
            profile.shortLabel?.let { put(EnvironmentId(profile.id), "Server · $it") }
        }
        protocolProfiles.forEach { profile -> put(profile.environment, profile.label) }
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
        serverPrivacy, savedPrivacy, activeSavedScopes, listings,
        protocolSources.sources, now, batchRunning, quickRunning,
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
        addAll(protocolSources.sources)
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
        // Transmission/qBittorrent/Deluge: only while this exact profile's privacy gate allows it.
        protocolProfiles.forEach { profile ->
            if (profile.environment in protocolAllowed) put(profile.environment, profile.aliasScope)
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
    fun lifecycleEligible(key: UnifiedTransferKey, readCurrent: Boolean): Boolean {
        val isServer = key.environment == serverId
        return CombinedLifecycleEligibility.eligible(CombinedLifecycleEligibility.Facts(
            readCurrent = readCurrent,
            completeLiveInventory = sourcesByEnvironment[key.environment]?.canClaimCompleteLiveInventory == true,
            isLocal = key.environment == EnvironmentSnapshots.LOCAL,
            localPrivacyMayShow = localPrivacy.mayShow,
            localRunning = localRunning,
            isAuthenticatedServer = isServer,
            serverLifecyclePresent = server?.lifecycle != null,
            verifiedServerOwner = isServer && verifiedCombinedLifecycleOwner(server, serverId) != null,
            serverPrivacyMayShow = serverPrivacy?.mayShow == true,
            serverStale = server?.stale,
            infoHash = key.infoHash))
    }
    val firstRunVisible = enableFirstRunWelcome && showCanonicalFirstRunWelcome(
        noPersistedJobs = noPersistedLocalJobs,
        nativeRunning = localRunning,
        nativeConfirmed = localConfirmed,
        nativeStale = localFrame.stale || LocalMutationFence.revision.blocksMutations(),
        nativeCount = localFrame.entries.size,
        hasRemoteProfile = configuredRemoteForWelcome(
            server != null, savedProfiles.isNotEmpty() || protocolProfiles.isNotEmpty(),
            hasRememberedRemoteLogin),
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
        !pendingSharedMagnet && !pendingSharedTorrent && !readingSharedTorrent &&
        sharedTorrentError == null

    // Hide this window from screenshots/recents whenever a protected scope is shown.
    // Typed protocol clients count too: their rows are shown here once allowed.
    val secure = CombinedSecureWindow.required(
        local = localPrivacy,
        server = serverPrivacy,
        savedShown = savedPrivacy.filter { (id, _) -> listings[id]?.observedAtEpochMillis != null }.values,
        protocol = protocolPrivacyScopes.values.mapNotNull { privacyScope ->
            privacyScope?.let { privacyEvidence?.decision(it, privacySession, deviceSecure) }
        })
    DisposableEffect(activity, secure) {
        if (secure) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (secure) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    val listState = listState ?: rememberLazyListState()
    // Read-only receipt-check outcomes outlive the receipts they released (#2026).
    val absentReceiptResults = remember { androidx.compose.runtime.mutableStateMapOf<ProtocolOperationReceiptOwner.Key, String>() }
    // A removed profile's outcomes are dropped rather than kept hidden forever.
    val configuredReceiptProfiles = protocolProfiles.map { it.id }.toSet()
    LaunchedEffect(configuredReceiptProfiles) {
        absentReceiptResults.keys.filter { it.profileId !in configuredReceiptProfiles }
            .forEach { absentReceiptResults.remove(it) }
    }
    // A shared Android magnet must expose its exact destination choices even
    // when the Combined list was last visited far below its Add card. This
    // changes only the view position, never authorizes an Add or remote call.
    LaunchedEffect(pendingSharedMagnet, pendingSharedTorrent, readingSharedTorrent, sharedTorrentError) {
        // A process-restored external .torrent has no surviving URI, draft or
        // pending source. Its interruption notice is still essential: reveal
        // the same source/destination panel without replaying the old Intent.
        if (pendingSharedMagnet || pendingSharedTorrent || readingSharedTorrent ||
            sharedTorrentError != null) listState.scrollToItem(COMBINED_ADD_ITEM_INDEX)
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
    val latestMoveFocus = rememberUpdatedState(focusedKey)
    val latestLocalPrivacy = rememberUpdatedState(localPrivacy.mayShow)
    val latestServerPrivacy = rememberUpdatedState(serverPrivacy?.mayShow == true)
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
        val localSource = remember(localRunning) { LocalEngineHolder.current() }
        val localMoveSession = localSource != null && localPrivacy.mayShow
        val localMoveReadable = localMoveSession &&
            sourcesByEnvironment[EnvironmentSnapshots.LOCAL]?.canClaimCompleteLiveInventory == true
        val serverMoveSession = focusedKey.environment == serverId && verifiedOwner != null && serverPrivacy?.mayShow == true
        val serverMoveReadable = serverMoveSession &&
            sourcesByEnvironment[focusedKey.environment]?.canClaimCompleteLiveInventory == true
        val moveReceipt = if (focusedKey.infoHash.matches(V1_TRANSFER_HASH))
            ProtocolOperationReceipts.owner.receipt(
                ProtocolOperationReceiptOwner.Key(focusedKey.environment.value, focusedKey.infoHash.lowercase(java.util.Locale.ROOT))
            ) else null
        val renameScope = aliasScopes[focusedKey.environment]
        // Copy/Move uses the shared job-only port, including the device's own
        // engine. The explicit Copy action checks the destination independently.
        val localClientOperations: () -> io.github.gamesbyian.chunks.rpc.ClientOperations = remember(context) {
            { io.github.gamesbyian.chunks.rpc.ClientOperations(LocalTorrentClient.forApp(context)) }
        }
        // As a Move TARGET the device may start its engine: tapping "Copy job to
        // This device" is as explicit as Add. A running engine's list must be live.
        val localTargetOperations: () -> io.github.gamesbyian.chunks.rpc.ClientOperations = remember(context) {
            { io.github.gamesbyian.chunks.rpc.ClientOperations(
                LocalTorrentClient.forApp(context, startForExplicitUse = true)) }
        }
        val localMoveConnection = if (localPrivacy.mayShow)
            ProtocolMoveTarget(EnvironmentSnapshots.LOCAL.value, "This device",
                aliasScopes[EnvironmentSnapshots.LOCAL], ownerCurrent = { latestLocalPrivacy.value },
                createOperations = localTargetOperations) else null
        val localMoveTarget = localMoveConnection?.takeUnless { localRunning && localFrame.stale }
        val rtorrentMoveConnection = (server?.authenticatedSession as? RTorrentRemote)?.takeIf {
            serverId != null && serverPrivacy?.mayShow == true && it.addAccountScope() == server?.aliasScope
        }?.let { remote ->
            ProtocolMoveTarget(requireNotNull(serverId).value,
                labels[serverId]?.removePrefix("Server · ") ?: "rTorrent", aliasScopes[serverId],
                connectionWitness = remote, ownerCurrent = {
                    latestServer.value?.authenticatedSession === remote &&
                        latestServerId.value == serverId && latestServerPrivacy.value
                }) {
                io.github.gamesbyian.chunks.rpc.ClientOperations(
                    io.github.gamesbyian.chunks.rpc.RTorrentClientAdapter(remote))
            }
        }
        val rtorrentMoveTarget = rtorrentMoveConnection?.takeIf { server?.stale == false }
        // Metadata-late Smart Routing Ask: an advisory Move suggestion once the
        // job's real name is known. Read-only; never moves anything by itself.
        val routingPolicy by androidx.compose.runtime.produceState<
            io.github.gamesbyian.chunks.model.SmartRoutingPolicyStore.Policy?>(null, context) {
            value = withContext(Dispatchers.IO) {
                (runCatching { io.github.gamesbyian.chunks.model.SmartRoutingPolicyStore(context).read() }.getOrNull()
                    as? io.github.gamesbyian.chunks.model.SmartRoutingPolicyStore.Read.Ready)?.policy
            }
        }
        // A Transfers filter does not change which destination was authenticated.
        val routingSnapshots = sources.associateBy { it.environment }
        fun moveSuggestionFor(targets: List<ProtocolMoveTarget>, fileNames: List<String> = emptyList()): Pair<String, String>? =
            io.github.gamesbyian.chunks.model.SmartRoutingAddSuggestion.moveSuggestion(
                routingPolicy, details.observation?.originalName, details.observation?.metadataPending,
                focusedKey.environment.value,
                targets.map { target ->
                    // Catalog snapshots already fence old reads by exact profile,
                    // authenticated identity, privacy and freshness.
                    io.github.gamesbyian.chunks.model.SmartRoutingAddSuggestion.Candidate(
                        target.id, target.label,
                        live = routingSnapshots[EnvironmentId(target.id)]
                            ?.canClaimCompleteLiveInventory == true,
                        acceptsAdd = true, placeKnown = true
                    )
                },
                fileNames
            )?.let { it.environmentId to (io.github.gamesbyian.chunks.model.SmartRoutingAddSuggestion.kindLabel(it.kind).lowercase() + " rule") }
        val protocolMoveTargets = protocolProfiles.filter {
            it.operations != null && it.environment in protocolAllowed
        }.distinctBy { it.id }.map {
            ProtocolMoveTarget(it.id, it.label, aliasScopes[it.environment],
                connectionWitness = it.identityWitness, ownerCurrent = { protocolOwnerCurrent(it) },
                createOperations = requireNotNull(it.operations))
        }
        val moveConnections = (protocolMoveTargets + listOfNotNull(localMoveConnection, rtorrentMoveConnection))
            .associate { it.id to it.connectionWitness }
        // Transmission / qBittorrent / Deluge: the exact typed profile's own
        // write-capable session, through the ONE shared operation workflow.
        val focusedProtocol = protocolProfiles.firstOrNull { it.environment == focusedKey.environment }
            ?.takeIf { it.operations != null && it.environment in protocolAllowed }
        val focusedProtocolListing = focusedProtocol?.let { profile ->
            protocolSources.listings[profile.environment]?.takeIf { it.witness == profile.identityWitness }
        }
        val protocolOperations: (@Composable () -> Unit)? = focusedProtocol?.let { profile ->
            {
                // Read progress regardless of identity witness, so a read in
                // flight is never shown as an idle refresh control.
                val rawProtocolListing = protocolSources.listings[profile.environment]
                val protocolTargets = protocolMoveTargets.filter { it.id != profile.id } +
                    listOfNotNull(rtorrentMoveTarget, localMoveTarget)
                ProtocolClientOperationsPanel(
                    hash = focusedKey.infoHash,
                    clientLabel = profile.label,
                    sourceClientId = profile.id,
                    observedStarted = details.observation?.started,
                    current = details.readState == TransferDetailReadState.CURRENT &&
                        focusedProtocolListing?.snapshot?.canClaimCompleteLiveInventory == true &&
                        focusedKey.infoHash.matches(V1_TRANSFER_HASH),
                    createOperations = requireNotNull(profile.operations),
                    onChanged = { protocolSources.reload(profile.environment) },
                    // Only where a fresh list can make these controls current again:
                    // an out-of-date, offline, failed or still-loading read. Never for
                    // a job that a complete list no longer shows, a locked client or
                    // an inventory the client cannot report completely.
                    onRefreshList = if (focusedKey.infoHash.matches(V1_TRANSFER_HASH) &&
                        (details.readState == TransferDetailReadState.STALE ||
                            details.readState == TransferDetailReadState.OFFLINE ||
                            rawProtocolListing?.failed == true || rawProtocolListing?.loading == true))
                        ({ protocolSources.reload(profile.environment) }) else null,
                    listRefreshing = rawProtocolListing?.loading == true,
                    listRefreshFailed = rawProtocolListing?.failed == true,
                    moveTargets = protocolTargets,
                    moveConnections = moveConnections,
                    suggestedTarget = moveSuggestionFor(protocolTargets),
                    routingMetadataPending = details.observation?.metadataPending,
                    suggestForFiles = { names -> moveSuggestionFor(protocolTargets, names) },
                    sourceAliasScope = aliasScopes[focusedKey.environment],
                    sourceGeneration = profile.identityWitness,
                    canReconcile = focusedProtocolListing?.snapshot?.canClaimCompleteLiveInventory == true,
                    observedDownloadDirectory = details.observation?.reportedSaveFolder,
                    isOwnerCurrent = { protocolOwnerCurrent(profile) }
                )
            }
        }
        Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
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
            }),
            collections = if (renameScope == null) null else ({ transfer ->
                DetailsCollections(renameScope, transfer.key.infoHash, transfer.displayName)
            }),
            environmentLabel = if (focusedKey.environment == EnvironmentSnapshots.LOCAL) "Local"
                else savedProfiles.firstOrNull { it.id == focusedKey.environment.value }?.shortLabel
                    ?: rowLabels[focusedKey.environment]?.removePrefix("Server · ") ?: "Remote client",
            environmentHue = hueFor(focusedKey.environment),
            exactEnvironmentLabel = labels[focusedKey.environment]?.removePrefix("Server · "),
            onBack = { savedDetailTarget = null },
            // A Remove's outcome is retained after its job leaves the list (incomplete or
            // uncertain deletions must stay visible), so Details keeps showing it.
            localOperations = if (focusedKey.environment == EnvironmentSnapshots.LOCAL &&
                localPrivacy?.mayShow != false &&
                (details.readState == TransferDetailReadState.CURRENT || (moveReceipt != null && localMoveSession) ||
                    LocalDetailsRuns.outcomes[focusedKey.infoHash] != null)) ({
                val current = details.readState == TransferDetailReadState.CURRENT
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (current && details.observation?.started == false) {
                        LocalStoppedRecheckControl(focusedKey.infoHash)
                        LocalForeignPartialStartRecovery(focusedKey.infoHash)
                    }
                    // Full Details parity with server jobs: files & priorities and Remove.
                    LocalDetailsOperations(focusedKey.infoHash, current = current, stale = localFrame.stale)
                    // Job-only Copy/Move to another connected client through the same
                    // shared receipt; Local lifecycle/files/Remove above stay authoritative.
                    val outbound = protocolMoveTargets + listOfNotNull(rtorrentMoveTarget)
                    if ((localMoveReadable && current && outbound.isNotEmpty()) ||
                        (moveReceipt != null && localMoveSession)) {
                        ProtocolClientOperationsPanel(
                            hash = focusedKey.infoHash,
                            clientLabel = "this device",
                            sourceClientId = EnvironmentSnapshots.LOCAL.value,
                            observedStarted = details.observation?.started,
                            current = current && localMoveReadable && focusedKey.infoHash.matches(V1_TRANSFER_HASH),
                            createOperations = localClientOperations,
                            onChanged = {},
                            moveTargets = outbound,
                            moveConnections = moveConnections,
                            moveOnly = true,
                            suggestedTarget = moveSuggestionFor(outbound),
                            routingMetadataPending = details.observation?.metadataPending,
                            suggestForFiles = { names -> moveSuggestionFor(outbound, names) },
                            sourceAliasScope = aliasScopes[focusedKey.environment],
                            sourceGeneration = EnvironmentSnapshots.LOCAL.value,
                            canReconcile = localMoveReadable,
                            isOwnerCurrent = {
                                latestMoveFocus.value == focusedKey && latestLocalPrivacy.value &&
                                    LocalEngineHolder.current() === localSource
                            }
                        )
                    }
                }
            }) else null,
            exactServerOperations = if (canShowExactRemoval ||
                (serverMoveSession && moveReceipt != null)) ({
                val owner = requireNotNull(server)
                val mutation = requireNotNull(owner.lifecycle)
                val boundRemote = requireNotNull(activeRemoteForRemoval)
                val boundRow = exactRemoteRow
                val operationsStale = owner.stale ||
                    details.readState != TransferDetailReadState.CURRENT ||
                    sourcesByEnvironment[focusedKey.environment]
                        ?.canClaimCompleteLiveInventory != true
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (canShowExactRemoval && boundRow != null) {
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
                    // Use the exact same Copy/Move receipt controller as the
                    // other protocols. Existing rTorrent lifecycle/Remove/Files
                    // controls remain authoritative and are never duplicated.
                    val connectedTargets = protocolMoveTargets.filter { it.id != requireNotNull(serverId).value } +
                        listOfNotNull(localMoveTarget)
                    if ((connectedTargets.isNotEmpty() && !operationsStale) ||
                        (moveReceipt != null && serverMoveSession)) {
                        ProtocolClientOperationsPanel(
                            hash = focusedKey.infoHash,
                            clientLabel = labels[requireNotNull(serverId)]?.removePrefix("Server · ") ?: "rTorrent",
                            sourceClientId = serverId.value,
                            observedStarted = details.observation?.started,
                            current = details.readState == TransferDetailReadState.CURRENT &&
                                !operationsStale && focusedKey.infoHash.matches(V1_TRANSFER_HASH),
                            // Stable per bound session: a new adapter per recomposition
                            // would re-read the server session on every list refresh.
                            createOperations = remember(boundRemote) {
                                {
                                    io.github.gamesbyian.chunks.rpc.ClientOperations(
                                        io.github.gamesbyian.chunks.rpc.RTorrentClientAdapter(boundRemote))
                                }
                            },
                            onChanged = owner.refresh,
                            moveTargets = connectedTargets,
                            moveConnections = moveConnections,
                            moveOnly = true,
                            suggestedTarget = moveSuggestionFor(connectedTargets),
                            routingMetadataPending = details.observation?.metadataPending,
                            suggestForFiles = { names -> moveSuggestionFor(connectedTargets, names) },
                            sourceAliasScope = aliasScopes[focusedKey.environment],
                            sourceGeneration = boundRemote.commandGeneration,
                            canReconcile = serverMoveReadable,
                            isOwnerCurrent = {
                                latestMoveFocus.value == focusedKey && latestServerId.value == serverId &&
                                    latestServerPrivacy.value &&
                                    verifiedCombinedLifecycleOwner(latestServer.value, serverId) === boundRemote
                            }
                        )
                    }
                }
            }) else protocolOperations,
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
                        headerVisible = showNavigationFooter,
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
        }
        if (showNavigationFooter) AuthenticatedTransfersFooter(
            optionsOpen = null,
            onTransfers = { savedDetailTarget = null },
            onAdd = onAddToLocal ?: onOpenLocal,
            onOptions = { savedDetailTarget = null; showingViewOptions = true },
            onConnections = onConnections,
            activeDestination = FooterDestination.TRANSFERS
        )
        }
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
                "Open transfers on this device to start the local client."
            else -> "This transfer list is out of date. Refresh or reconnect before making changes."
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
    val visibleAddReceipts = addReceipts.filter { receipt ->
        val environment = receipt.key.environment
        environmentVisible(query.scope, environment) && when (environment) {
            EnvironmentSnapshots.LOCAL -> localPrivacy.mayShow
            serverId -> serverPrivacy?.mayShow == true
            else -> environment in protocolAllowed
        }
    }
    Column(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f)) {
        LazyColumn(Modifier.fillMaxSize().testTag(ChunksSemantics.TRANSFERS_SCREEN).semantics { paneTitle = "Transfers" }.padding(horizontal = 16.dp)
                .onSizeChanged { viewportHeightPx = it.height },
            state = listState, contentPadding = PaddingValues(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The global wordmark and footer already supply location and navigation.
        if (showNavigationFooter) item {
            CombinedTransfersHeader(onBack)
        }

        visibleAddReceipts.forEach { receipt ->
            item(key = "add-receipt-" + receipt.key.environment.value) {
                UniversalAddReceiptCard(receipt.display,
                    modifier = Modifier.testTag(ChunksSemantics.TRANSFERS_ADD_RECEIPT)) {
                    UniversalAddContinuationAction(
                        ChunksSemantics.TRANSFERS_ADD_REVIEW + "." + receipt.key.environment.value,
                        "Review Add on ${receipt.clientLabel}"
                    ) {
                        onReviewAdd(receipt.key.environment)
                    }
                }
            }
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
        if (showNavigationFooter || pendingSharedMagnet || pendingSharedTorrent ||
            readingSharedTorrent || sharedTorrentError != null) item(key = COMBINED_ADD_ITEM_KEY) {
            CombinedAddActions(
                serverLabel = serverId?.let { labels[it] },
                hasOtherSavedServers = savedProfiles.any { EnvironmentId(it.id) != serverId } ||
                    protocolProfiles.isNotEmpty(),
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
                        .semantics { contentDescription = "Show all clients" })
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
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag(ChunksSemantics.TRANSFERS_ENVIRONMENT_TOGGLES + "." + id.value)
                            .semantics {
                            contentDescription = (if (visible) "Showing " else "Hidden: ") +
                                label + ", " + (if (count == "?") "count unknown" else "$count transfers") +
                                (if (unavailable) ", currently unavailable" else "")
                        })
                }
            }
            if (query.scope == EnvironmentViewScope.Only(emptySet())) Text(
                "Choose a client above to see its transfers.",
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
                    onManualSearchEdited()
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
                EnvironmentViewScope.All -> "All clients"
                is EnvironmentViewScope.Only ->
                    if (scope.ids.isEmpty()) "No clients"
                    else if (scope.ids.size == 1) labels[scope.ids.first()] ?: "One client"
                    else "${scope.ids.size} clients"
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
                Text("A new download may be hidden by your filters. Resetting filters won't change where downloads are saved.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (showingViewOptions && query.search.isNotBlank() &&
            query.filter == UnifiedTransferFilter.ALL &&
            query.scope == EnvironmentViewScope.All) item {
            Text("A new download may be hidden by this search. Clear it to see all transfers.",
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
                    (if (query.groupByEnvironment) " · By client" else " · Together"))
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
                Text("Group by client", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                Switch(checked = query.groupByEnvironment,
                    onCheckedChange = { update(query.copy(groupByEnvironment = it)) },
                    // The Switch itself, not only its enclosing Row, must have
                    // an accessible 48dp semantics/touch surface at large text.
                    modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { contentDescription = "Group by client" })
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
            Text(combinedCompactSummaryText(
                shown = result.transfers.size,
                inSelectedEnvironments = result.sources.sumOf { it.transfers.size },
                totals = io.github.gamesbyian.chunks.model.UnifiedTransferTotals.of(result),
                incompleteOrStale = result.hasIncompleteOrStaleSource,
                includedClients = includedClientCount(result)
            ), style = MaterialTheme.typography.bodySmall)
        }
        // A local-only cold launch can have no running native engine, even
        // when saved jobs await explicit restoration. Never call that state
        // "zero transfers". Offer the existing source review without starting
        // an engine, choosing a Place or authorizing an Add.
        if (server == null && savedProfiles.isEmpty() && protocolProfiles.isEmpty() &&
            onAddToLocal != null &&
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
                    Text("No transfers to show in this view. " +
                        "Locked or offline clients may still need attention.",
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
        // #2026: retained typed-client receipts whose job is no longer listed
        // cannot be reached from Details. Offer only their read-only check,
        // and only from a privacy-visible, complete, current inventory.
        val receiptSources = protocolProfiles.mapNotNull { profile ->
            val create = profile.operations ?: return@mapNotNull null
            if (profile.environment !in protocolAllowed) return@mapNotNull null
            val snapshot = protocolSources.listings[profile.environment]
                ?.takeIf { it.witness == profile.identityWitness }?.snapshot
                ?.takeIf { it.canClaimCompleteLiveInventory } ?: return@mapNotNull null
            ProtocolReceiptCheckSource(profile.id, profile.label, listedHashes(snapshot.transfers), create) {
                protocolOwnerCurrent(profile)
            }
        }
        // A privacy-locked profile's earlier outcomes stay retained, never shown.
        val visibleReceiptProfiles = protocolProfiles.filter { it.environment in protocolAllowed }.map { it.id }.toSet()
        val receiptResultVisible: (ProtocolOperationReceiptOwner.Key) -> Boolean =
            { it.profileId in visibleReceiptProfiles }
        if (showAbsentReceiptsCard(ProtocolOperationReceipts.owner, receiptSources, absentReceiptResults,
                receiptResultVisible))
            item(key = "protocol-absent-receipts") {
                ProtocolAbsentReceiptsCard(receiptSources, results = absentReceiptResults,
                    onChecked = { profileId ->
                        protocolProfiles.firstOrNull { it.id == profileId }?.let { protocolSources.reload(it.environment) }
                    }, resultVisible = receiptResultVisible)
            }
        // Detailed environment status is available after the inventory, rather
        // than charging every user a tall status panel before the first row.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showingSourceStatus || result.sources.size > 1 || result.hasIncompleteOrStaleSource ||
                    listings.values.any { it.error != null || it.loading } ||
                    protocolSources.listings.values.any { it.failed || it.loading }) {
                    OutlinedButton(
                        onClick = { showingSourceStatus = !showingSourceStatus },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text(if (showingSourceStatus) "Hide client status"
                        else "Client status · ${result.sources.size} sources") }
                }
                // The first-fold count is recycled when the user scrolls down
                // to investigate a failed server. Keep ONE freshness warning
                // beside the exact-source rows without moving diagnostics up.
                if (showingSourceStatus && result.hasIncompleteOrStaleSource) {
                    Text("Some clients are offline or out of date. Saved transfer lists may have changed. " +
                        "Check each client's status below.",
                        style = MaterialTheme.typography.bodySmall, color = ChunksColors.Yellow)
                }
                if (showingSourceStatus) result.sources.forEach { source ->
                    val listing = activeSavedScopes[source.environment]?.let { witness ->
                        listings[source.environment]?.takeIf { it.sourceAliasScope == witness }
                    }
                    val protocolProfile = protocolProfiles.firstOrNull {
                        it.environment == source.environment
                    }
                    val protocolListing = protocolProfile?.let { profile ->
                        protocolSources.listings[source.environment]
                            ?.takeIf { it.witness == profile.identityWitness }
                    }
                    val status = when {
                        source.environment in invalidSavedProfiles ->
                            "Saved account identity could not be verified; reconnect this profile"
                        privacyEvidence == null ->
                            "Privacy policy not yet verified; transfers hidden"
                        protocolListing?.loading == true -> "Loading read-only list…"
                        protocolListing?.failed == true && protocolListing.snapshot == null ->
                            "Could not load this client (read-only)"
                        protocolListing?.failed == true ->
                            "Refresh failed; showing last read-only list"
                        listing?.loading == true -> "Loading list…"
                        listing?.error != null && listing.observedAtEpochMillis == null ->
                            "Could not load (" + listing.error + ")"
                        listing?.error != null ->
                            "Refresh failed (" + listing.error + "), showing last list"
                        else -> availabilityText(source)
                    }
                    Text(labels[source.environment].orEmpty() + ": " + status + " · " +
                        source.transfers.size + " transfers", style = MaterialTheme.typography.bodySmall)
                    if (protocolProfile != null && source.environment in protocolAllowed) {
                        val label = labels[source.environment].orEmpty()
                        OutlinedButton(
                            onClick = { protocolSources.reload(source.environment) },
                            enabled = protocolListing?.loading != true,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                                contentDescription = "Refresh transfers on $label; read only"
                            }
                        ) { Text(if (protocolListing?.loading == true) "Loading $label transfers…"
                            else "Refresh $label transfers") }
                    }
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
                    // Reads already happen automatically; this is an immediate
                    // read-only refresh, never a sign-in or command.
                    Text("Refresh other saved servers")
                }
                if (result.missingSelectedEnvironments.isNotEmpty()) Text(
                    (result.missingSelectedEnvironments.size.let { n -> if (n == 1) "1 selected client is" else "$n selected clients are" }) + " no longer set up. " +
                        "Choose All or reselect clients.", style = MaterialTheme.typography.bodySmall,
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
