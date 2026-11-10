package io.github.gamesbyian.chunks

import android.app.Activity
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
    val phoneVisible: Boolean,
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

/** The connected server's lease, command and settle, exactly as its own list uses them. */
internal class CombinedServerLifecycle(
    val begin: () -> Boolean,
    val settled: () -> Unit,
    val setStarted: suspend (String, Boolean) -> LifecycleOutcome,
    /** Read-only method-list probe of the same signed-in server; null skips the pre-check. */
    val probe: (suspend () -> io.github.gamesbyian.chunks.rpc.RemoteProbe)? = null,
    /** Exact-identity, read-only diagnosis on the same live authenticated server. */
    val inspectStall: (suspend (String) -> RemoteStallEvidence)? = null
)

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

/** Shared sort keys. Date added is known on the phone only; server rows sort as unknown (last). */
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
    EnvironmentAvailability.OFFLINE -> if (source.environment == EnvironmentSnapshots.PHONE)
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
    onOpenPhone: () -> Unit,
    onOpenServer: (() -> Unit)?,
    /** Opens the connected, verified server's existing Add review. Never targets a saved-only profile. */
    onAddToServer: (() -> Unit)? = null,
    /** Opens this exact transfer in its own environment's list; only phone and the signed-in server. */
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
    /** The same app-wide Connections destination as phone and signed-in-server lists. */
    onConnections: (() -> Unit)? = null,
    /** A distinct Add callback: merely opening the phone inventory must not consume a shared source. */
    onAddToPhone: (() -> Unit)? = null,
    /** Source content remains in MainActivity process memory; only presence/status reach this list. */
    pendingSharedMagnet: Boolean = false,
    pendingSharedLink: Boolean = false,
    pendingSharedTorrent: Boolean = false,
    readingSharedTorrent: Boolean = false,
    sharedTorrentError: String? = null,
    /** One-shot deep link by stable saved profile ID, never display label. */
    scopeFocusRequest: String? = null,
    onScopeFocusConsumed: () -> Unit = {},
    /** Welcome always enters the existing Add destination, not a new executor. */
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
    // A saved profile tap requests a VIEW filter of this existing catalog only.
    // It cannot authenticate, select an Add target, or authorize a mutation.
    LaunchedEffect(scopeFocusRequest) {
        if (scopeFocusRequest != null) {
            savedProfileViewScope(scopeFocusRequest, savedProfiles.map { it.id }.toSet())?.let {
                update(query.copy(scope = it))
            }
            onScopeFocusConsumed()
        }
    }

    // Phone: read only an engine already running in this process. Opening this
    // view never starts the phone engine or its service (remote-only use).
    var phoneFrame by remember { mutableStateOf(LocalInventoryFrame<LocalTransferStatus>(emptyList(), stale = false)) }
    var phoneConfirmed by remember { mutableStateOf(false) }
    var phoneRunning by remember { mutableStateOf(LocalEngineHolder.current() != null) }
    // Read-only durable absence evidence. A missing native engine is otherwise
    // indistinguishable from a stopped process with restorable torrent jobs.
    var noPersistedPhoneJobs by remember(context) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(context) {
        noPersistedPhoneJobs = try {
            withContext(Dispatchers.IO) {
                !io.github.gamesbyian.chunks.engine.LocalRestoreDiscovery.shouldAttemptRestore(
                    java.io.File(context.filesDir, "local-engine"))
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) { false }
    }
    var phoneObservedAt by remember { mutableStateOf<Long?>(null) }
    // Restores only a read-only detail target, never an in-flight command or batch selection.
    var savedDetailTarget by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    val detailKey = restoreCombinedDetailTarget(savedDetailTarget)
    val batchScope = rememberCoroutineScope()
    var listings by remember { mutableStateOf<Map<EnvironmentId, SavedServerListing>>(emptyMap()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); now = System.currentTimeMillis() } }
    LaunchedEffect(Unit) {
        while (true) {
            when (val outcome = CombinedPhoneReader.pump.takeCompleted()) {
                is LocalScreenPollOutcome.Fresh -> {
                    val (list, sample) = outcome.value
                    phoneRunning = list != null
                    if (list == null) {
                        // The engine went away: nothing here is live any more.
                        phoneConfirmed = false
                        phoneFrame = LocalInventoryFrame(emptyList(), stale = false)
                        phoneObservedAt = null
                    }
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

    // Privacy policy storage and SHA-256 preference-key derivation must never
    // run during composition. Until a cancellable, exact-scope asynchronous
    // read completes, every environment is locked, including opt-out scopes.
    val privacyRevision = TransferPrivacyRuntime.lifecycleRevision
    val policyRevision = TransferPrivacyPolicyStore.revision
    val phonePrivacyScope = TransferCollectionStore.LOCAL_ENGINE_SCOPE
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
            add(phonePrivacyScope)
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
    val phonePrivacy = privacyEvidence?.decision(phonePrivacyScope, privacySession, deviceSecure)
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
        phoneVisible = phonePrivacy.mayShow,
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
    val labels = LinkedHashMap<EnvironmentId, String>().apply {
        put(EnvironmentSnapshots.PHONE, PHONE_ENVIRONMENT_LABEL)
        if (server != null && serverId != null)
            put(serverId, connectedServerFriendlyLabel(server.aliasScope, serverId, savedProfiles))
        savedProfiles.forEach { profile -> putIfAbsent(EnvironmentId(profile.id), "Server · " + profile.label) }
    }
    val rowLabels = remember(labels, savedProfiles, server?.aliasScope) {
        compactEnvironmentLabels(labels, buildMap {
        put(EnvironmentSnapshots.PHONE, PHONE_ENVIRONMENT_LABEL)
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
    LaunchedEffect(context, paletteIdentities, paletteRevision) {
        try {
            palette = withContext(Dispatchers.IO) {
                EnvironmentColourStore(context).ensure(paletteIdentities)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Keep the previous visible colours rather than presenting a failed
            // write as saved; controls and environment IDs are independent.
        }
    }
    var choosingColours by remember { mutableStateOf(false) }
    var showingViewOptions by rememberSaveable { mutableStateOf(false) }
    var showingBatchTools by rememberSaveable { mutableStateOf(false) }
    var showingSortOptions by rememberSaveable { mutableStateOf(false) }
    var showingSourceStatus by rememberSaveable { mutableStateOf(false) }
    // Only environments with a live list here can open a row there.
    fun openFor(key: UnifiedTransferKey): (() -> Unit)? =
        if (key.environment == EnvironmentSnapshots.PHONE || (serverId != null && key.environment == serverId))
            ({ onOpenTransfer(key) }) else null

    // Keyed on data only: MainActivity rebuilds the server source (and its lifecycle lambdas) each frame.
    val rawSources = remember(phoneFrame, phoneConfirmed, phoneRunning, phoneObservedAt, phonePrivacy,
        server?.aliasScope, server?.transfers, server?.stale, server?.fullView, server?.observedAtEpochMillis,
        serverPrivacy, savedPrivacy, activeSavedScopes, listings, now, batchRunning,
        PhoneMutationFence.revision.blocksMutations()) { buildList {
        // The ticker invalidates old reads; use the actual clock when a newer
        // observation arrives between ticks so it is not mistaken for a future read.
        val snapshotNow = System.currentTimeMillis()
        val phoneStale = phoneFrame.stale || PhoneMutationFence.revision.blocksMutations() || batchRunning
        val phone = if (phoneRunning || phoneConfirmed) EnvironmentSnapshots.phone(
            phoneFrame.copy(stale = phoneStale), phoneConfirmed,
            locked = !phonePrivacy.mayShow, observedAtEpochMillis = phoneObservedAt)
        else EnvironmentTransferSnapshot(EnvironmentSnapshots.PHONE,
            if (phonePrivacy.mayShow) EnvironmentAvailability.OFFLINE else EnvironmentAvailability.LOCKED,
            false, null, emptyList())
        add(phone)
        if (server != null && serverId != null) {
            add(EnvironmentSnapshots.server(serverId, server.transfers, connected = true,
                stale = server.stale || batchRunning,
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
        if (phonePrivacy.mayShow) put(EnvironmentSnapshots.PHONE, TransferCollectionStore.LOCAL_ENGINE_SCOPE)
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
    // Full authorized inventory, not just composed LazyColumn rows. This catches external
    // additions and reconnects to any accessible saved rTorrent environment.
    // Locked/stale/offline sources are never used for third-party identification.
    val mediaController = io.github.gamesbyian.chunks.media.LocalTransferMediaController.current
    val mediaInventory = remember(sources) {
        sources.filter { it.availability == EnvironmentAvailability.LIVE &&
            it.observedAtEpochMillis != null }
            .flatMap { source -> source.transfers.map { row ->
                io.github.gamesbyian.chunks.media.MediaTransferKey.of(row.key) to row.originalName
            } }
    }
    LaunchedEffect(mediaController, mediaInventory) {
        mediaController?.enrichInventory(mediaInventory)
    }
    val mediaTitles by io.github.gamesbyian.chunks.media.rememberMediaSearchTitles()
    val result = remember(sources, query, mediaTitles) {
        UnifiedTransferCatalog.query(sources, query) { key ->
            mediaTitles[io.github.gamesbyian.chunks.media.MediaTransferKey.of(key)]
        }
    }
    val addedHidden = combinedAddedTransferHidden(recentlyAddedTransfer, sources,
        result.transfers.map { it.key })
    val firstRunVisible = showCanonicalFirstRunWelcome(
        noPersistedJobs = noPersistedPhoneJobs,
        nativeRunning = phoneRunning,
        nativeConfirmed = phoneConfirmed,
        nativeStale = phoneFrame.stale || PhoneMutationFence.revision.blocksMutations(),
        nativeCount = phoneFrame.entries.size,
        hasRemoteProfile = server != null || savedProfiles.isNotEmpty(),
        restrictedView = CombinedViewPreferences.narrowingActive(query),
        privacyAllowed = phonePrivacy.mayShow,
        pendingExternalSource = pendingSharedMagnet || pendingSharedTorrent ||
            readingSharedTorrent || recentlyAddedTransfer != null
    )
    val checkingFreshPhone = noPersistedPhoneJobs == null && !phoneRunning &&
        !phoneConfirmed && savedProfiles.isEmpty() && server == null &&
        !CombinedViewPreferences.narrowingActive(query)
    val sourcesByEnvironment = result.sources.associateBy { it.environment }

    // Hide this window from screenshots/recents whenever a protected scope is shown.
    val secure = (phonePrivacy.needsSecureWindow && phonePrivacy.mayShow) ||
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
            if (focusedKey.environment == EnvironmentSnapshots.PHONE)
                EnvironmentCapabilities.phone() else snapshot,
            System.currentTimeMillis()
        )
        val readOnlyProbe = server?.lifecycle?.probe?.takeIf {
            focusedKey.environment == serverId &&
                server?.authenticatedSession != null &&
                serverPrivacy?.mayShow == true &&
                details.readState == TransferDetailReadState.CURRENT
        }
        val stallProbe = server?.lifecycle?.inspectStall?.takeIf {
            focusedKey.environment == serverId &&
                server?.authenticatedSession != null &&
                serverPrivacy?.mayShow == true && !server.stale &&
                details.readState == TransferDetailReadState.CURRENT
        }
        // Only a genuinely current and complete inventory may even offer
        // lifecycle review. The *existing* batch controller owns the later
        // authorization, exact-account lease, readback and uncertain-write
        // refusal. Saved-only, locked, stale and disconnected rows stay read-only.
        val canReviewLifecycle = details.observation != null &&
            details.readState == TransferDetailReadState.CURRENT &&
            sourcesByEnvironment[focusedKey.environment]?.canClaimCompleteLiveInventory == true &&
            ((focusedKey.environment == EnvironmentSnapshots.PHONE &&
                phonePrivacy.mayShow && phoneRunning) ||
                (focusedKey.environment == serverId && server?.lifecycle != null &&
                    server?.authenticatedSession != null && serverPrivacy?.mayShow == true &&
                    server?.stale == false &&
                    // rTorrent Start/Stop only supports exact v1 hashes. Keep
                    // unsupported remote identities read-only before review.
                    focusedKey.infoHash.matches(Regex("[0-9a-f]{40}"))))
        // Reuse the existing exact-account rTorrent Remove control inside
        // canonical Details. It owns separate destructive approval, session
        // readback, uncertain-write latch and non-force ruTorrent data cleanup.
        // This only moves presentation: no new mutation/executor/RPC authority.
        val activeRemoteForRemoval = server?.authenticatedSession as? RTorrentRemote
        val exactRemoteRow = server?.transfers?.singleOrNull {
            it.hash.equals(focusedKey.infoHash, ignoreCase = true)
        }
        val canShowExactRemoval = activeRemoteForRemoval != null &&
            focusedKey.environment == serverId &&
            server?.aliasScope?.let(EnvironmentSnapshots::serverIdOfAliasScope) == serverId &&
            serverPrivacy?.mayShow == true &&
            exactRemoteRow != null && server.lifecycle != null
        UnifiedTransferDetailPanel(
            detail = details,
            environmentLabel = labels[focusedKey.environment] ?: "Unconfigured environment",
            environmentHue = palette.hueOf(focusedKey.environment),
            onBack = { savedDetailTarget = null },
            exactServerOperations = if (canShowExactRemoval) ({
                val owner = requireNotNull(server)
                val mutation = requireNotNull(owner.lifecycle)
                TransferSessionRemoveControl(
                    remote = requireNotNull(activeRemoteForRemoval),
                    transfer = requireNotNull(exactRemoteRow),
                    snapshotStale = owner.stale ||
                        details.readState != TransferDetailReadState.CURRENT ||
                        sourcesByEnvironment[focusedKey.environment]
                            ?.canClaimCompleteLiveInventory != true,
                    mainView = owner.fullView,
                    onMutationStarted = mutation.begin,
                    onSettled = mutation.settled,
                    environmentLabel = labels[focusedKey.environment],
                    snapshotRevision = owner.snapshotRevision
                )
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
                    environmentColor = { id -> palette.hueOf(id)?.let { Color(it.argb) } ?: ChunksColors.Outline },
                    onOpen = { key -> savedDetailTarget = arrayListOf(key.environment.value, key.infoHash) })
            }
        )
    } else {
    // The same measured Transfers / Add / Options footer as the phone and
    // rTorrent lists. Each action only moves within this view: Add leads to
    // the explicit destination choices, never to an inferred environment.
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
                modifier = Modifier.semantics { heading() })
        }
        if (firstRunVisible) {
            item(key = "canonical-fresh-welcome") {
                NoTransfersWelcome(
                    onAdd = onWelcomeAdd ?: onAddToPhone ?: onOpenPhone,
                    onConnections = onConnections
                )
            }
        } else if (checkingFreshPhone) {
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
                onOpenPhone = onAddToPhone ?: onOpenPhone,
                onAddToServer = onAddToServer,
                pendingSharedMagnet = pendingSharedMagnet,
                pendingSharedLink = pendingSharedLink,
                pendingSharedTorrent = pendingSharedTorrent,
                readingSharedTorrent = readingSharedTorrent,
                sharedTorrentError = sharedTorrentError,
                phoneHue = palette.hueOf(EnvironmentSnapshots.PHONE),
                serverHue = serverId?.let { palette.hueOf(it) }
            )
        }
        item {
            val scopeName = when (val scope = query.scope) {
                EnvironmentViewScope.All -> "All environments"
                is EnvironmentViewScope.Only ->
                    if (scope.ids.size == 1) labels[scope.ids.first()] ?: "One environment"
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showingViewOptions = !showingViewOptions; if (!showingViewOptions) showingSortOptions = false },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text(if (showingViewOptions) "Hide filters" else "Filters") }
                OutlinedButton(onClick = { showingBatchTools = !showingBatchTools },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(if (showingBatchTools) "Hide batch tools" else "Batch tools")
                }
            }
            // A scoped Transfers list retains the same catalog and heading.
            // State the effective environment and filter even when the chip
            // panel is collapsed, so phone/server-only views are not mistaken
            // for an incomplete global inventory. All/default stays compact.
            if (query.scope != EnvironmentViewScope.All ||
                query.filter != UnifiedTransferFilter.ALL) {
                Text("Viewing: $scopeName · $filterName",
                    style = MaterialTheme.typography.bodySmall)
            }
            // Search has its own clear affordance; do not insert a second button
            // above the text field as the user types and shift its focus/position.
            if (!addedHidden && !showingViewOptions && result.transfers.isNotEmpty() &&
                (query.filter != UnifiedTransferFilter.ALL ||
                    query.scope != EnvironmentViewScope.All)) {
                OutlinedButton(onClick = { update(CombinedViewPreferences.showAll(query)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Show all transfers")
                }
            }
            if (!addedHidden && result.transfers.isNotEmpty() &&
                (query.filter != UnifiedTransferFilter.ALL ||
                    query.scope != EnvironmentViewScope.All)) {
                Text(
                    "New downloads may be hidden by this view. Show all transfers " +
                        "clears view filters, not your Add destination.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (showingViewOptions) item {
            Text("Show", style = MaterialTheme.typography.labelLarge)
            FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = query.scope == EnvironmentViewScope.All,
                    onClick = { update(query.copy(scope = EnvironmentViewScope.All)) },
                    label = { Text("All") }, modifier = Modifier.heightIn(min = 48.dp))
                labels.forEach { (id, label) ->
                    val scope = query.scope
                    val selected = scope is EnvironmentViewScope.Only && id in scope.ids
                    FilterChip(selected = selected, onClick = {
                        update(query.copy(scope = toggleScope(scope, id)))
                    }, label = { Text(rowLabels[id] ?: label) }, leadingIcon = { EnvironmentDot(palette.hueOf(id)) },
                        modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        item {
            ClearableTransferSearchField(
                value = query.search,
                onValueChange = {
                    queryTouched = true
                    query = query.copy(search = it)
                    searchState?.value = it
                },
                label = "Search names or hashes"
            )
            // The search affordance itself remains in place while typing.
            // This hint comes AFTER the field so it never displaces its focus.
            if (query.search.isNotBlank() &&
                query.filter == UnifiedTransferFilter.ALL &&
                query.scope == EnvironmentViewScope.All) {
                Text("New downloads may not match this search. Clear search to see them.",
                    style = MaterialTheme.typography.bodySmall)
            }
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
                running = batchRunning,
                result = batchResult,
                onClear = { selected = emptySet() },
                onRun = { start ->
                    batchRunning = true
                    batchResult = emptyList()
                    val chosen = selected
                    val snapshot = sources
                    val owner = batchIdentity
                    val lifecycle = server?.lifecycle
                    val targetServer = serverId
                    batchScope.launch {
                        // Dispatch may be queued behind a server/account switch.
                        if (latestBatchIdentity.value != owner) return@launch
                        var unconfirmedPhoneStops = 0
                        try {
                            val report = describeCombinedBatchSafely({ labels[it] ?: it.value }) {
                                runCombinedLifecycle(chosen, start, snapshot, lifecycle, targetServer,
                                    reviewStillCurrent = { latestBatchIdentity.value == owner },
                                    onPhoneStopCheckpointUnconfirmed = { unconfirmedPhoneStops++ })
                            }
                            // A late completion is evidence about the OLD owner only.
                            if (latestBatchIdentity.value == owner) {
                                val warning = io.github.gamesbyian.chunks.model.LocalStoppedCheckpointBudget
                                    .warning(unconfirmedPhoneStops)
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
        item(key = "combined-compact-count") {
            Text(combinedShowingText(result.transfers.size,
                result.sources.sumOf { it.transfers.size }) +
                if (result.hasIncompleteOrStaleSource) " · some environments are missing or out of date" else "",
                style = MaterialTheme.typography.bodySmall)
        }
        // A phone-only cold launch can have no running native engine, even
        // when saved jobs await explicit restoration. Never call that state
        // "zero transfers". Offer the existing source review without starting
        // an engine, choosing a Place or authorizing an Add.
        if (server == null && savedProfiles.isEmpty() && onAddToPhone != null &&
            result.transfers.isEmpty() && !CombinedViewPreferences.narrowingActive(query)) {
            item(key = "combined-first-phone-intake") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Transfers on this device",
                        style = MaterialTheme.typography.titleMedium)
                    Text(phoneOnlyEmptyInventoryMessage(
                        sourcesByEnvironment[EnvironmentSnapshots.PHONE]
                            ?.canClaimCompleteLiveInventory == true),
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onAddToPhone,
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
                        EnvironmentDot(palette.hueOf(group.source.environment))
                        Text(labels[group.source.environment].orEmpty() + " · " + group.transfers.size +
                            " shown", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() })
                    }
                }
                items(group.transfers, key = { "row-" + it.key.environment.value + it.key.infoHash }) { row ->
                    CombinedTransferRow(row, labels[row.key.environment].orEmpty(), palette.hueOf(row.key.environment),
                    visibleEnvironmentLabel = rowLabels[row.key.environment],
                    source = sourcesByEnvironment[row.key.environment],
                        selection = if (selecting) row.key in selected else null,
                        onSelect = { on -> selected = if (on) selected + row.key else selected - row.key },
                        onDetails = { savedDetailTarget = arrayListOf(row.key.environment.value, row.key.infoHash) })
                }
            }
        } else {
            items(result.transfers, key = { "row-" + it.key.environment.value + it.key.infoHash }) { row ->
                CombinedTransferRow(row, labels[row.key.environment].orEmpty(), palette.hueOf(row.key.environment),
                    visibleEnvironmentLabel = rowLabels[row.key.environment],
                    source = sourcesByEnvironment[row.key.environment],
                    selection = if (selecting) row.key in selected else null,
                    onSelect = { on -> selected = if (on) selected + row.key else selected - row.key },
                        onDetails = { savedDetailTarget = arrayListOf(row.key.environment.value, row.key.infoHash) })
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
                Text(combinedTotalsText(io.github.gamesbyian.chunks.model.UnifiedTransferTotals.of(result)),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            OutlinedButton(onClick = { choosingColours = !choosingColours },
                modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
                Text(if (choosingColours) "Done with colours" else "Environment colours…")
            }
            if (choosingColours) EnvironmentColourChooser(labels, palette,
                onChoose = { id, hue ->
                    batchScope.launch {
                        withContext(Dispatchers.IO) { EnvironmentColourStore(context).choose(id, hue) }
                    }
                },
                onRestore = { id ->
                    batchScope.launch {
                        withContext(Dispatchers.IO) { EnvironmentColourStore(context).restoreAutomatic(id) }
                    }
                })
        }
        // Switch view scopes here, never silently enter a different
        // environment-specific list or change the authenticated account.
        item {
            ResponsiveActionGroup(Modifier.padding(vertical = 12.dp)) { actionModifier ->
                OutlinedButton(onClick = {
                    update(query.copy(scope = EnvironmentViewScope.Only(
                        setOf(EnvironmentSnapshots.PHONE))))
                }, modifier = actionModifier) {
                    Text("Only this device")
                }
                if (onOpenServer != null && serverId != null) OutlinedButton(onClick = {
                    update(query.copy(scope = EnvironmentViewScope.Only(setOf(serverId))))
                }, modifier = actionModifier) {
                    Text("Only connected server")
                }
            }
            // The phone's explicit operational/restore controls must remain
            // reachable whether its engine is running or has not been restored.
            // Scoping the canonical catalog never grants native command authority.
            OutlinedButton(onClick = onOpenPhone,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Open device transfer controls")
            }
        }
        } // management controls are absent only from a genuine fresh welcome
        } // LazyColumn content builder
        ReturnToTopControl(visible = showReturnToTop && !firstRunVisible && !checkingFreshPhone, onClick = {
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
internal fun phoneOnlyEmptyInventoryMessage(confirmedCurrent: Boolean): String =
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
    // Same words as the phone and rTorrent rows, from observed facts only.
    if (row.metadataPending == true) "Fetching metadata" else when (row.started) {
        true -> when {
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
    onDetails: () -> Unit
) {
    // The row opens one full Details destination, with exact environment/hash
    // identity. No expanding card or implicit environment switch is retained.
    val accent = hue?.let { Color(it.argb) } ?: ChunksColors.Outline
    val labelColour = if (hue != null && EnvironmentPalette.labelIsReadable(
        hue, ChunksColors.Surface.toArgbLong())) accent else ChunksColors.Text
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(12.dp))
        .background(ChunksColors.Surface)) {
        // Colour is supplementary: the exact environment name is always present.
        Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
        if (selection != null) Checkbox(checked = selection, onCheckedChange = onSelect,
            modifier = Modifier.padding(start = 4.dp).align(Alignment.CenterVertically).semantics {
                contentDescription = "Select ${row.displayName} on $environmentLabel"
            })
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)
            .clickable(role = Role.Button,
                onClickLabel = "Open full transfer details",
                onClick = onDetails)
            .semantics {
                contentDescription = "Details for ${row.displayName} on $environmentLabel"
            },
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // The body of the transfer is its single expansion target. No permanent
            // Details/More button bank takes space away from actual progress.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(visibleEnvironmentLabel ?: environmentLabel, style = MaterialTheme.typography.labelMedium,
                    color = labelColour, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Details", style = MaterialTheme.typography.labelSmall,
                    color = ChunksColors.TextSecondary)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransferRowArtwork(row.key, row.originalName,
                    hidden = source?.availability == EnvironmentAvailability.LOCKED)
                Text(row.displayName, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = row.displayName
                    })
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
                    // Same colour meaning as the phone and rTorrent cards: green complete, paused stopped.
                    tone = combinedProgressTone(row)
                )
            }
            combinedRowFreshnessLabel(source)?.let { warning ->
                Text(warning, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (row.hasError) Text("Needs attention in its own list",
                color = ChunksColors.Red, style = MaterialTheme.typography.bodySmall)

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
    phoneEngine: () -> io.github.gamesbyian.chunks.engine.LocalTorrentEngine? = { LocalEngineHolder.current() },
    nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    /** Old session, privacy or saved-profile review cannot authorize another mutation. */
    reviewStillCurrent: () -> Boolean = { true },
    /** Phone Stop can be verified while its independent recovery save is still uncertain. */
    onPhoneStopCheckpointUnconfirmed: (String) -> Unit = {}
): UnifiedBatchLifecycle.Report {
    // Confirmation can arrive between clock ticks, after the displayed source
    // expired. Re-evaluate it before acquiring any command lane.
    val currentTime = nowEpochMillis()
    val currentSources = sources.map { source ->
        if (source.environment != EnvironmentSnapshots.PHONE &&
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
    var phoneLeased = false
    var serverLeased = false
    // Start this monotonic budget with the FIRST completed phone Stop, not
    // before slow remote capability checks or unrelated account operations.
    var phoneStopBudget: io.github.gamesbyian.chunks.model.LocalStoppedCheckpointBudget? = null
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
                                val outcome = (if (on) engine.resume(hash) else engine.pause(hash)).outcome
                                if (!on && outcome == io.github.gamesbyian.chunks.engine.LocalCommandOutcome.VERIFIED) {
                                    val budget = phoneStopBudget
                                        ?: io.github.gamesbyian.chunks.model.LocalStoppedCheckpointBudget()
                                            .also { phoneStopBudget = it }
                                    if (!budget.confirm(hash, engine::saveResumeDataFor))
                                        onPhoneStopCheckpointUnconfirmed(hash)
                                }
                                // Failed persistence never changes a VERIFIED Stop into an
                                // unknown command or invites replay of that command.
                                outcome.toLifecycleReply()
                            }
                        }
                    }
                }
            } else if (environment == serverId && serverLifecycle != null) {
                // Fresh read-only pre-check before leasing: a server that does not
                // advertise Start/Stop refuses here. No answer leaves it UNKNOWN,
                // and the command's own readback still decides.
                val capability = serverCapabilities(environment, serverLifecycle.probe)
                    .decide(EnvironmentOperation.START_STOP, EnvironmentAvailability.LIVE)
                val source = currentSources.first { it.environment == environment }
                // The read-only method probe can suspend while the user changes
                // accounts or locks privacy. Check again before taking a lease.
                if (!reviewStillCurrent())
                    refusals[environment] = expiredReview
                else if (EnvironmentSnapshots.serverInventoryIsOutOfDate(source.observedAtEpochMillis, nowEpochMillis()))
                    refusals[environment] = "Transfer list is out of date; refresh first"
                else if (!capability.allowed) refusals[environment] = capability.explanation
                else if (!serverLifecycle.begin()) refusals[environment] = "server is busy or refreshing"
                else {
                    serverLeased = true
                    adapters[environment] = UnifiedBatchLifecycle.Adapter { hash, on ->
                        // rTorrent identities are v1 only: refuse before any RPC rather than
                        // letting a local validation failure read as an uncertain mutation.
                        if (!hash.matches(Regex("[0-9a-f]{40}"))) LifecycleReply.REFUSED
                        else serverLifecycle.setStarted(hash, on).toLifecycleReply(on)
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
                (source.environment == EnvironmentSnapshots.PHONE ||
                    !EnvironmentSnapshots.serverInventoryIsOutOfDate(source.observedAtEpochMillis, nowEpochMillis())) },
            preflightRefusals = unsupportedServerTargets)
    } finally {
        if (phoneLeased) PhoneMutationFence.revision.settledMutation()
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
            FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
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


/** Progress colour shared with the phone and rTorrent cards; colour is never the only signal. */
internal fun combinedProgressTone(row: UnifiedTransferObservation): ChunksTone = when {
    row.hasError -> ChunksTone.ERROR
    (row.progressPpm ?: 0) >= 1_000_000 -> ChunksTone.COMPLETE
    row.started == false -> ChunksTone.PAUSED
    else -> ChunksTone.ACTIVE
}

/** A profile label is deliberately excluded: duplicate names cannot alter scope. */
internal fun savedProfileViewScope(
    profileId: String,
    knownProfileIds: Set<String>
): EnvironmentViewScope? = profileId.takeIf { it.isNotBlank() && it in knownProfileIds }
    ?.let { EnvironmentViewScope.Only(setOf(EnvironmentId(it))) }
