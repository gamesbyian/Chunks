package io.github.gamesbyian.chunks

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import io.github.gamesbyian.chunks.privacy.TransferPrivacyVisibility
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
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
import io.github.gamesbyian.chunks.model.EnvironmentHue
import io.github.gamesbyian.chunks.model.EnvironmentPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

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
    val authenticatedSession: Any? = null
)

/** The connected server's lease, command and settle, exactly as its own list uses them. */
internal class CombinedServerLifecycle(
    val begin: () -> Boolean,
    val settled: () -> Unit,
    val setStarted: suspend (String, Boolean) -> LifecycleOutcome,
    /** Read-only method-list probe of the same signed-in server; null skips the pre-check. */
    val probe: (suspend () -> io.github.gamesbyian.chunks.rpc.RemoteProbe)? = null
)

/** A saved profile that is not signed in during this session. */
internal data class CombinedSavedProfile(
    val id: String,
    val label: String,
    /** Authenticated endpoint + NUL + username, for its own privacy gate and aliases. */
    val aliasScope: String? = null,
    /** Read-only list of its "main" view with its own saved credentials; null when unavailable. */
    val listOnly: (suspend () -> List<RemoteTransfer>)? = null
)

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
    is java.net.UnknownHostException -> "host name not found"
    is java.net.ConnectException -> "connection refused"
    is javax.net.ssl.SSLException -> "secure connection failed"
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
    /** Opens this exact transfer in its own environment's list; only phone and the signed-in server. */
    onOpenTransfer: (UnifiedTransferKey) -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val preferences = remember(context) { CombinedViewPreferences(context) }
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

    // Keyed on identities, not the profile objects (their loader lambdas change every recomposition).
    val savedPrivacy = remember(privacyRevision, savedProfiles.map { it.id to it.aliasScope }) {
        savedProfiles.associate { profile ->
            EnvironmentId(profile.id) to (profile.aliasScope
                ?.let { RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(it) }
                ?.let { TransferPrivacyVisibility.current(context, activity, it) }
                ?: TransferPrivacyVisibility.Decision(mayShow = false, needsSecureWindow = true))
        }
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
            id != serverId && profile.listOnly != null && savedPrivacy[id]?.mayShow == true
        }?.let { id to it }
    }.toMap()
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
        if (server != null && serverId != null) put(serverId, serverEnvironmentLabel(server.aliasScope))
        savedProfiles.forEach { profile -> putIfAbsent(EnvironmentId(profile.id), "Server · " + profile.label) }
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
    val result = remember(sources, query) { UnifiedTransferCatalog.query(sources, query) }

    // Hide this window from screenshots/recents whenever a protected scope is shown.
    val secure = (phonePrivacy.needsSecureWindow && phonePrivacy.mayShow) ||
        (serverPrivacy?.needsSecureWindow == true && serverPrivacy.mayShow) ||
        savedPrivacy.any { (id, d) -> d.needsSecureWindow && d.mayShow && listings[id]?.observedAtEpochMillis != null }
    DisposableEffect(activity, secure) {
        if (secure) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (secure) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    val listState = rememberLazyListState()
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
    val latestServer = rememberUpdatedState(server)
    val latestServerId = rememberUpdatedState(serverId)
    val latestFocus = rememberUpdatedState(signedInFocus)
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
        UnifiedTransferDetailPanel(
            detail = details,
            environmentLabel = labels[focusedKey.environment] ?: "Unconfigured environment",
            onBack = { savedDetailTarget = null },
            onOpenOwningList = openFor(focusedKey),
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
            capabilityCheckError = capabilityFailure?.takeIf { stillCurrent(it.first) }?.second
        )
    } else {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)
                .onSizeChanged { viewportHeightPx = it.height },
            state = listState, contentPadding = PaddingValues(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            CombinedTransfersHeader(onBack)
            Text("Browse every environment together. Start and Stop can be applied to a selection " +
                "here; every other change happens in each environment's own list.",
                style = MaterialTheme.typography.bodySmall)
        }
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
                        try {
                            val report = describeCombinedBatchSafely({ labels[it] ?: it.value }) {
                                runCombinedLifecycle(chosen, start, snapshot, lifecycle, targetServer,
                                    reviewStillCurrent = { latestBatchIdentity.value == owner })
                            }
                            // A late completion is evidence about the OLD owner only.
                            if (latestBatchIdentity.value == owner) batchResult = report
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
            Text("Filter transfers", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    onCheckedChange = { update(query.copy(groupByEnvironment = it)) },
                    modifier = Modifier.semantics { contentDescription = "Group by environment" })
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                result.sources.forEach { source ->
                    val listing = activeSavedScopes[source.environment]?.let { witness ->
                        listings[source.environment]?.takeIf { it.sourceAliasScope == witness }
                    }
                    val status = when {
                        listing?.loading == true -> "Loading list…"
                        listing?.error != null && listing.observedAtEpochMillis == null ->
                            "Could not load (" + listing.error + ")"
                        listing?.error != null ->
                            "Refresh failed (" + listing.error + "), showing last list"
                        else -> availabilityText(source)
                    }
                    Text(labels[source.environment].orEmpty() + ": " + status + " · " +
                        source.transfers.size + " transfers", style = MaterialTheme.typography.bodySmall)
                }
                val loadable = savedProfiles.filter { profile ->
                    val id = EnvironmentId(profile.id)
                    id != serverId && profile.listOnly != null && profile.aliasScope != null &&
                        activeSavedScopes[id] == profile.aliasScope
                }
                if (loadable.isNotEmpty()) OutlinedButton(enabled = listings.none { (id, listing) ->
                    listing.loading && activeSavedScopes[id] == listing.sourceAliasScope
                },
                    onClick = {
                        loadable.forEach { profile ->
                            val id = EnvironmentId(profile.id)
                            val load = profile.listOnly ?: return@forEach
                            val identity = profile.aliasScope ?: return@forEach
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
                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (listings.isEmpty()) "Load other saved servers (read-only)" else "Refresh other saved servers")
                }
                if (result.missingSelectedEnvironments.isNotEmpty()) Text(
                    "${result.missingSelectedEnvironments.size} selected environment(s) are no longer set up. " +
                        "Choose All or reselect environments.", style = MaterialTheme.typography.bodySmall,
                    color = ChunksColors.Yellow)
                Text(combinedTotalsText(io.github.gamesbyian.chunks.model.UnifiedTransferTotals.of(result)),
                    style = MaterialTheme.typography.bodySmall)
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
                        onSelect = { on -> selected = if (on) selected + row.key else selected - row.key },
                        onOpen = openFor(row.key),
                        onDetails = { savedDetailTarget = arrayListOf(row.key.environment.value, row.key.infoHash) })
                }
            }
        } else {
            items(result.transfers, key = { "row-" + it.key.environment.value + it.key.infoHash }) { row ->
                CombinedTransferRow(row, labels[row.key.environment].orEmpty(), palette.hueOf(row.key.environment),
                    selection = if (selecting) row.key in selected else null,
                    onSelect = { on -> selected = if (on) selected + row.key else selected - row.key },
                    onOpen = openFor(row.key),
                        onDetails = { savedDetailTarget = arrayListOf(row.key.environment.value, row.key.infoHash) })
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
        item {
            ResponsiveActionGroup(Modifier.padding(vertical = 12.dp)) { actionModifier ->
                OutlinedButton(onClick = onOpenPhone, modifier = actionModifier) {
                    Text("Open On this phone")
                }
                if (onOpenServer != null) OutlinedButton(onClick = onOpenServer,
                    modifier = actionModifier) { Text("Open server list") }
            }
        }
        }
        ReturnToTopControl(visible = showReturnToTop, onClick = {
            batchScope.launch {
                if (ReturnToTopPolicy.animationsEnabled(context)) listState.animateScrollToItem(0)
                else listState.scrollToItem(0)
            }
        }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp))
    }
    }
}

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
    onSelect: (Boolean) -> Unit = {},
    /** Null for environments only listed here (no live list to open). */
    onOpen: (() -> Unit)? = null,
    /** Read-only details are available even for saved, unsigned-in profiles. */
    onDetails: () -> Unit
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
        Column(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(environmentLabel, style = MaterialTheme.typography.labelMedium, color = labelColour,
                fontWeight = FontWeight.Bold)
            Text(row.displayName, style = MaterialTheme.typography.titleSmall)
            if (row.displayName != row.originalName) Text("Torrent name: " + row.originalName,
                style = MaterialTheme.typography.bodySmall)
            Text(combinedRowFacts(row), style = MaterialTheme.typography.bodySmall)
            if (row.hasError) Text("Needs attention in its own list", color = ChunksColors.Red,
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onDetails,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = "Details for ${row.displayName} on $environmentLabel"
                }) { Text("Details") }
            if (onOpen != null) OutlinedButton(onClick = onOpen,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { contentDescription = "Open ${row.displayName} in $environmentLabel" }) {
                Text("Open in its list")
            }
            // Say why no action is offered rather than leaving the user to hunt for one.
            else if (row.key.environment != EnvironmentSnapshots.PHONE) Text(
                "Listed only. Sign in to this server to open or change it.",
                style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary)
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
    reviewStillCurrent: () -> Boolean = { true }
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
                                (if (on) engine.resume(hash) else engine.pause(hash)).outcome.toLifecycleReply()
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

/** Live-only totals in words, saying what is unknown or left out. */
internal fun combinedTotalsText(t: io.github.gamesbyian.chunks.model.UnifiedTransferTotals): String =
    "Live total: ↓ " + (if (t.downloadTotalBeyondRange) "exceeds reporting range"
                         else Units.bytes(t.downloadBytesPerSecond) + "/s") +
        " · ↑ " + (if (t.uploadTotalBeyondRange) "exceeds reporting range"
                    else Units.bytes(t.uploadBytesPerSecond) + "/s") +
        " across ${t.liveRows} transfers" +
        (if (t.rowsWithUnknownRate > 0) " · ${t.rowsWithUnknownRate} with rates not reported" else "") +
        (if (t.excludedEnvironments > 0) " · ${t.excludedEnvironments} environment(s) excluded (not fully observed)" else "")
