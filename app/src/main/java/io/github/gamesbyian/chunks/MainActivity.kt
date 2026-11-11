package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.TransferCatalog
import io.github.gamesbyian.chunks.model.TorrentIdentityHandoff
import io.github.gamesbyian.chunks.model.CollectionMembershipFilter
import io.github.gamesbyian.chunks.model.CollectionNameRegistryRules
import io.github.gamesbyian.chunks.model.CollectionMembershipRules
import io.github.gamesbyian.chunks.model.ManualCollectionPager
import io.github.gamesbyian.chunks.model.TransferFilter
import io.github.gamesbyian.chunks.model.TransferOrder
import io.github.gamesbyian.chunks.model.TransferSortOrdering
import io.github.gamesbyian.chunks.model.UnifiedSortDirection
import io.github.gamesbyian.chunks.model.TransferMove
import io.github.gamesbyian.chunks.model.PublicTrackerHandoff
import io.github.gamesbyian.chunks.engine.FinishInOrderRunner
import io.github.gamesbyian.chunks.model.Units
import io.github.gamesbyian.chunks.organize.Classifier
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteSmartConnect
import io.github.gamesbyian.chunks.rpc.requireRemoteConnectIdentityEvidence
import io.github.gamesbyian.chunks.rpc.verifiedSavedProfileMainInventory
import io.github.gamesbyian.chunks.rpc.RemoteProviderCatalog
import io.github.gamesbyian.chunks.rpc.RemoteFile
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.rpc.RemoteQueueActivityContext
import io.github.gamesbyian.chunks.snapshots.SnapshotInventoryExportScreen
import io.github.gamesbyian.chunks.snapshots.SnapshotDocumentInspectScreen
import io.github.gamesbyian.chunks.snapshots.SnapshotRestorePreviewPanel
import io.github.gamesbyian.chunks.snapshots.SnapshotRestoreTarget
import io.github.gamesbyian.chunks.privacy.LocalTransferPrivacyGate
import io.github.gamesbyian.chunks.privacy.RemoteTransferPrivacyGate
import io.github.gamesbyian.chunks.privacy.RemoteTransferPrivacyScope
import io.github.gamesbyian.chunks.privacy.TransferPrivacyRuntime
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Local aliases for the shared ChunksColors tokens (see ChunksTheme.kt).
// Panels filled with an accent always carry Ink (near-black) text.
private val Ink = ChunksColors.OnAccent
private val Cream = ChunksColors.Background
private val Lilac = ChunksColors.Purple
private val Lime = ChunksColors.Green
private val Coral = ChunksColors.Orange
private val Blue = ChunksColors.Cyan

internal class PhoneAddAttemptOwner : androidx.lifecycle.ViewModel() {
    val session = PhoneAddAttemptSession()
}

class MainActivity : ComponentActivity() {
    // Retained across Activity recreation, never persisted on process death.
    private val phoneAddAttemptOwner: PhoneAddAttemptOwner by viewModels()
    private var incomingMagnet by mutableStateOf<String?>(null)
    private var incomingTorrent by mutableStateOf<Uri?>(null)
    private var incomingCreate by mutableStateOf<io.github.gamesbyian.chunks.create.SharedCreateDocument.Request?>(null)

    private fun accept(intent: Intent?) {
        // A separately addressed Create-torrent Share stays out of Add.
        val create = io.github.gamesbyian.chunks.create.SharedCreateDocument.fromIntent(intent)
        if (create != null) {
            // A different explicit Create target supersedes an unconsumed Add
            // handoff. Never hold two competing external source authorities.
            incomingCreate = create
            incomingTorrent = null
            incomingMagnet = null
            return
        }
        // Invalid, malformed and unrelated Intents confer no source authority.
        // In particular, they cannot erase a valid source still awaiting the
        // exact phone/server chooser or a privacy gate.
        val candidate = ExternalAddSource.fromIntent(intent) ?: return
        incomingCreate = null
        val pending = PendingExternalAddSource(incomingMagnet, incomingTorrent)
            .withIncoming(candidate)
        incomingTorrent = pending.torrentDocument
        incomingMagnet = pending.text
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The near-black Compose surface extends behind the system status bar,
        // so the status bar icons must be light.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        // An Android share is a one-shot event. After an Activity recreation,
        // the original launch Intent is still attached to this Activity, but
        // its source may already have been consumed or superseded. Replaying
        // it would re-open an old Add review and overwrite a newer draft.
        // Genuine new shares still enter through onNewIntent().
        if (savedInstanceState == null) accept(intent)
        setContent {
            ChunksTheme {
                // One process-wide media controller for rows, details and settings alike.
                val mediaController = androidx.compose.runtime.remember {
                    io.github.gamesbyian.chunks.media.ChunksMedia.get(applicationContext).controller
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    io.github.gamesbyian.chunks.media.LocalTransferMediaController provides mediaController
                ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChunksApp(
                        sharedMagnet = incomingMagnet,
                        incomingTorrent = incomingTorrent,
                        onIncomingMagnetConsumed = { incomingMagnet = null },
                        onIncomingTorrentConsumed = { incomingTorrent = null },
                        incomingCreate = incomingCreate,
                        onIncomingCreateConsumed = { incomingCreate = null },
                        phoneAddAttemptSession = phoneAddAttemptOwner.session
                    )
                }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TransferPrivacyRuntime.onForeground()
    }

    override fun onStop() {
        TransferPrivacyRuntime.onBackground()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }


}

private data class VerifiedConnectionNotice(val label: String, val saved: Boolean?)

private enum class Section(val label: String) {
    TRANSFERS("Transfers"), COMBINED("All environments"), PLACES("Places"), ORGANIZE("Organize"), SERVER("Connections"), ARCHIVES("ZIP review"), CREATE("Create"), SEEDING("Seeding rules"), MEDIA("Artwork & info"), LOCAL("Transfers on this device"), LOCAL_ADD("Add on this device"), OPTIONS("Options"), ADD_CHOOSE("Add"), ADD("Add"), NAMES("Names"), SNAPSHOT("Inventory backup"), SNAPSHOT_INSPECT("Inspect backup")
}

/** "12 shown", or "3 of 12 shown" when a search or filter narrows the list (as on the phone). */
internal fun remoteShownCount(shown: Int, loaded: Int): String =
    if (shown == loaded) "$shown shown" else "$shown of $loaded shown"

/** Search the existing connected-server Options routes without new authority. */
internal fun optionsDestinationMatches(label: String, rawQuery: String): Boolean {
    val needle = rawQuery.trim().lowercase(java.util.Locale.ROOT)
    if (needle.isEmpty()) return true
    val aliases = when (label) {
        "Transfers" -> "torrent downloads active list search"
        "On this phone" -> "local phone downloads storage files"
        "Places" -> "folder destination location save path"
        "Organize" -> "collections categories organize grouping"
        "Server" -> "connection endpoint profile account login settings server"
        "ZIP review" -> "zip archives compressed inspect"
        "Create" -> "create torrent publish share new"
        "Artwork & info" -> "artwork art poster cover image media metadata identify match provider tmdb lookup"
        else -> ""
    }
    val haystack = (label + " " + aliases).lowercase(java.util.Locale.ROOT)
    return needle.split(Regex("\\s+")).all { it in haystack }
}

// Only privacy-gated, non-mutating navigation is kept through Activity/process
// recreation. No authenticated server, command, selected hash or Add draft is saved.
private val SectionRestoreSaver = Saver<androidx.compose.runtime.MutableState<Section>, String>(
    save = { NavigationRestorePolicy.restorableSection(it.value.name) },
    restore = { saved ->
        mutableStateOf(Section.valueOf(NavigationRestorePolicy.restorableSection(saved)))
    }
)

@Composable
internal fun ChunksApp(
    sharedMagnet: String?,
    incomingTorrent: Uri?,
    onIncomingMagnetConsumed: () -> Unit,
    onIncomingTorrentConsumed: () -> Unit,
    incomingCreate: io.github.gamesbyian.chunks.create.SharedCreateDocument.Request? = null,
    onIncomingCreateConsumed: () -> Unit = {},
    phoneAddAttemptSession: PhoneAddAttemptSession? = null
) {
    val context = LocalContext.current
    val vault = remember(context) { RememberedRemoteLogin(context) }
    val profileRoster = remember(context) { RememberedRemoteProfileRoster(context) }
    val remembered = remember(vault, profileRoster) {
        vault.load() ?: profileRoster.list().firstOrNull()?.credentials
    }
    var savedProfiles by remember(profileRoster) {
        mutableStateOf(if (vault.rememberingEnabled()) profileRoster.list() else emptyList())
    }
    var endpoint by remember { mutableStateOf(remembered?.endpoint.orEmpty()) }
    // Advanced manual endpoint path; joined with a hostname-only address.
    var rpcPath by remember { mutableStateOf("") }
    var username by remember { mutableStateOf(remembered?.username.orEmpty()) }
    var password by remember { mutableStateOf(remembered?.password.orEmpty()) }
    // Remembering is the default. Every user can opt out before Connect.
    var rememberLogin by remember { mutableStateOf(vault.rememberingEnabled()) }
    // A cold launch may attempt ONE read-only authenticated connection using the last
    // verified saved login. A failed 401/timeout does not start a retry loop, and
    // Disconnect/Cancel never automatically reconnect within the same app session.
    var startupReconnectAttempted by remember { mutableStateOf(false) }
    // Startup reconnect is one read-only probe; manual connection creation
    // returns to Connections with an explicit verified-result acknowledgement.
    var returnFromStartupConnectToCanonical by remember { mutableStateOf(false) }
    var verifiedConnectionNotice by remember { mutableStateOf<VerifiedConnectionNotice?>(null) }
    var savedLoginPresent by remember {
        mutableStateOf(remembered != null || savedProfiles.isNotEmpty())
    }
    var credentialNotice by remember { mutableStateOf<String?>(null) }
    var server by remember { mutableStateOf<RTorrentRemote?>(null) }
    var version by remember { mutableStateOf("") }
    var transfers by remember { mutableStateOf<List<RemoteTransfer>>(emptyList()) }
    // Wall-clock time of the last accepted server list, for honest freshness in combined views.
    var transfersObservedAt by remember { mutableStateOf<Long?>(null) }
    // Advances ONLY when the server has delivered a fresh, accepted queue read.
    // The operation UI can then allow another separately confirmed lifecycle command.
    var snapshotRevision by remember { mutableLongStateOf(0L) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The current refresh error is an observed login refusal (HTTP 401/403).
    // Derived per message, so any later error or success clears it.
    var loginRefusedError by remember { mutableStateOf<String?>(null) }
    var connectDiagnostics by remember { mutableStateOf(ConnectionDiagnostics()) }
    var connectJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var stale by remember { mutableStateOf(false) }
    var currentView by remember { mutableStateOf("main") }
    var requestedView by remember { mutableStateOf("main") }
    // A fresh install with no remembered authenticated server opens the useful
    // phone transfer inventory. Saved/verified remote users still enter their
    // existing single-attempt reconnect journey. Restored routes keep their
    // explicit prior section; this default grants no server mutation authority.
    var section by rememberSaveable(saver = SectionRestoreSaver) {
        // A saved login needs the existing one-shot authenticated reconnect
        // owner to mount. Combined is a read-only list, not a reconnect executor.
        mutableStateOf(if (remembered == null) Section.COMBINED else Section.SERVER)
    }
    // Navigation only: no session, command, draft or selected transfer survives restore.
    // A restored remote route safely falls back to the phone when no server exists.
    var combinedFromPhone by rememberSaveable { mutableStateOf(false) }
    var combinedFromConnections by rememberSaveable { mutableStateOf(false) }
    var returnToCatalogRevision by remember { mutableLongStateOf(0L) }
    var showAllEnvironmentsRevision by remember { mutableLongStateOf(0L) }
    var consumedShowAllEnvironmentsRevision by remember { mutableLongStateOf(0L) }
    // View-only one-shot canonical catalog focus; never a login, Add target,
    // RPC account or authority. The browser consumes this exact stable ID.
    var savedProfileCatalogFocus by remember { mutableStateOf<String?>(null) }
    // One safe navigation return edge only, never an Add draft or authority.
    // When entering phone from Combined, the phone's own Back returns there.
    var localFromCombined by rememberSaveable { mutableStateOf(false) }
    // A tool (Seeding rules, Create, ZIP review) returns to the screen that
    // opened it, e.g. the phone or Server, instead of always to the
    // server/connection entry. Navigation only; no command or draft.
    var toolOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    var phoneReturnFocus by remember { mutableStateOf<String?>(null) }
    fun openTool(tool: Section, from: Section) {
        toolOrigin = from.name
        section = tool
    }
    fun openFromPhone(tool: Section) = openTool(tool, Section.LOCAL)
    fun leaveTool() {
        // Returning to the phone: the button that opened this tool regains focus.
        if (toolOrigin == Section.LOCAL.name || toolOrigin == Section.OPTIONS.name)
            phoneReturnFocus = section.name
        section = toolOrigin?.let { origin -> Section.entries.firstOrNull { it.name == origin } }
            ?: Section.LOCAL
        toolOrigin = null
    }
    fun leavePhone() {
        val returnToCombined = localFromCombined
        localFromCombined = false
        section = if (returnToCombined) Section.COMBINED else Section.LOCAL
    }
    var query by remember { mutableStateOf("") }
    // Secondary destinations appear only when requested on the ordinary list.
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    // Navigation-only list state that survives moving between routes
    // (phone ↔ All environments ↔ server ↔ settings) and rotation. It holds
    // no selection, confirmation or command state.
    val phoneListScroll = rememberScrollState()
    val phoneAddScroll = rememberScrollState()
    val phoneOptionsScroll = rememberScrollState()
    val phoneSearch = rememberSaveable { mutableStateOf("") }
    val phoneOptionsSearch = rememberSaveable { mutableStateOf("") }
    // The single Add source draft belongs to the app, never to the currently
    // displayed phone/server Add form. Both adapters edit the same source;
    // neither browsing filters nor opening a screen silently authorizes Add.
    // Process memory only, never SavedState/preferences: links can hold passkeys.
    val addSourceDraft = remember { mutableStateOf("") }
    val phoneAddPlace = rememberSaveable { mutableStateOf<String?>(null) }
    val phoneAddFolder = rememberSaveable { mutableStateOf("") }
    val phoneAddStart = rememberSaveable { mutableStateOf(true) }
    // A reviewed .torrent can include private tracker tokens. Keep its bytes above
    // the disposable phone route for navigation only; never use SavedState/Bundle.
    val phoneReviewedTorrent = remember { mutableStateOf<PendingLocalAdd?>(null) }
    // Production passes a configuration-retained attempt owner; test-only
    // standalone Compose callers have a process-only local fallback.
    val activePhoneAddAttempt = phoneAddAttemptSession ?: remember { PhoneAddAttemptSession() }
    // A phone opt-out must not silently become an opt-out on another server
    // (or the same server endpoint's different principal/numbered route).
    // No raw source, account identity or private tracker enters SavedState.
    val addArtworkChoices = remember { AddArtworkDraftChoices() }
    val combinedListState = rememberLazyListState()
    // Combined search text, like the phone's: navigation state, not a preference.
    val combinedSearch = rememberSaveable { mutableStateOf("") }
    // Per exact rTorrent account: list position and search survive route
    // switches (phone, All environments, Places) and Activity recreation.
    val remoteContinuity = rememberSaveable(saver = RemoteListContinuity.Saver) {
        RemoteListContinuity()
    }
    // A view-only filter; no hidden configuration or account scope is indexed.
    var optionsSearch by rememberSaveable { mutableStateOf("") }
    var filter by remember { mutableStateOf(TransferFilter.ALL) }
    var order by remember { mutableStateOf(TransferOrder.NAME) }
    var expandedHash by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var filesError by remember { mutableStateOf<String?>(null) }
    var filesLoading by remember { mutableStateOf(false) }
    // Remote Add and phone Add share the same editable source, but retain
    // independent capability/Place/Start checks until their submit coordinator
    // is consolidated. No second magnet string is allowed to diverge here.
    var magnet by addSourceDraft
    // Process-memory-only Android Share source awaiting an explicit exact destination.
    // A magnet can contain tracker passkeys: never save/log/render its raw value.
    var pendingSharedMagnet by remember { mutableStateOf<String?>(null) }
    // Shared content:// documents are never persisted or sent to a remote server
    // as a URI. A selected remote receives only locally validated metainfo bytes.
    var sharedTorrentReading by remember { mutableStateOf(false) }
    var sharedTorrentError by remember { mutableStateOf<String?>(null) }
    // The selected phone review URI is separate from the still-unselected
    // Android source: opening the phone inventory alone never consumes it.
    var phoneHandoffTorrentUri by remember { mutableStateOf<Uri?>(null) }
    var phoneHandoffMagnet by remember { mutableStateOf<String?>(null) }
    // A transfer opened from All environments: searched once on the phone list.
    var phoneSearchPrefill by remember { mutableStateOf<String?>(null) }
    // A phone transfer's exported .torrent waiting for the server Add review.
    // Process memory only; consumed by that review, never auto-submitted.
    var serverHandoffTorrent by remember { mutableStateOf<ByteArray?>(null) }
    // Account-scoped recovery outlives Add's composition, including privacy gates
    // and reconnects. Process memory only; Activity/process restoration is separate.
    val remoteAddSessions = remember { RemoteAddSessions() }
    val scope = rememberCoroutineScope()
    val credentialChanges = remember { RemoteCredentialActionQueue() }
    val aliasStore = remember(context) { TransferAliasStore(context) }
    val recentPlaceStore = remember(context) { RecentRemotePlaceStore(context) }
    var recentPlaceRevision by remember { mutableStateOf(0) }
    // Owned by the connected app, not the recyclable LazyColumn card.
    // A plain remembered map prevents getOrPut writes during composition from
    // being rolled back; each value has observable Compose state fields.
    val aliasSessions = remember { mutableMapOf<String, TransferAliasEditSession>() }
    var aliasScope by remember { mutableStateOf("") }
    fun prepareRemoteMagnet(source: String) {
        magnet = source
        remoteAddSessions.selectMagnet(aliasScope)
    }
    var aliasRevision by remember { mutableStateOf(0) }
    // Purely derive the configured identities during composition. SharedPreferences
    // writes must happen after composition, off the main thread, and only when
    // the identity set changes. A malformed legacy profile cannot block phone.
    val paletteIdentities = remember(savedProfiles, aliasScope) {
        buildList {
            add(io.github.gamesbyian.chunks.model.EnvironmentSnapshots.PHONE)
            io.github.gamesbyian.chunks.model.EnvironmentSnapshots
                .serverIdOfAliasScope(aliasScope)?.let { add(it) }
            savedProfiles.forEach { profile ->
                runCatching { io.github.gamesbyian.chunks.model.EnvironmentId(profile.id) }
                    .getOrNull()?.let { add(it) }
            }
        }.distinct()
    }
    LaunchedEffect(context, paletteIdentities) {
        try {
            withContext(Dispatchers.IO) {
                EnvironmentColourStore(context).ensure(paletteIdentities)
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // The combined view has its own read/repair path. A failed preference
            // write must never change server identity or block transfer controls.
        }
    }
    val sortStore = remember(context) { TransferListSortStore(context) }
    val remoteFilterStore = remember(context) { RemoteTransferFilterStore(context) }
    var orderDirection by remember { mutableStateOf(order.naturalDirection) }
    // Each authenticated server profile keeps its own list order; a profile
    // without a saved order starts at Name A–Z rather than inheriting another's.
    val sortEnvironment = remember(aliasScope) {
        aliasScope.takeIf { it.isNotBlank() }?.let {
            RememberedRemoteProfileRoster.identityOf(it.substringBefore('\u0000'),
                it.substringAfter('\u0000', ""))
        }
    }
    LaunchedEffect(sortEnvironment) {
        val saved = sortEnvironment?.let { sortStore.get(it, TransferOrder.entries.toTypedArray()) }
        order = saved?.first ?: TransferOrder.NAME
        orderDirection = saved?.second ?: order.naturalDirection
        // A different authenticated account cannot inherit another server's
        // search/filter. Search is transient; only this exact profile's filter
        // is restored from durable view-only preferences.
        filter = remoteFilterStore.load(sortEnvironment)
        // Restores only this exact account's own search; another account
        // (or no account) starts empty.
        query = if (sortEnvironment == null) "" else remoteContinuity.query(aliasScope)
    }
    // Process memory only: an exact verified hash, its account and the view
    // in force at Add. It never selects, expands or commands a transfer.
    var remoteAdded by remember { mutableStateOf<RemoteAddedRecord?>(null) }
    // The most recent acknowledged Add from the phone or a server, for Combined.
    // A server key is re-checked against the current account when it is used.
    var latestAdded by remember { mutableStateOf<io.github.gamesbyian.chunks.model.UnifiedTransferKey?>(null) }
    fun setRemoteQuery(value: String) {
        query = value
        remoteContinuity.rememberQuery(aliasScope, value)
    }
    val remoteFence = remember { RemoteUiRequestFence() }
    val inspectionFence = remember { RemoteUiRequestFence() }
    val mutationGate = remember { RemoteMutationReadbackGate() }

    // An Android content document supplies a source, not an implicit Add target.
    // With an authenticated server available, require explicit phone/server
    // selection. The phone route retains its existing bounded read/review.
    LaunchedEffect(incomingTorrent) {
        if (incomingTorrent != null) {
            remoteAddSessions.clearPendingSourceSelection()
            sharedTorrentError = null
            pendingSharedMagnet = null
            phoneHandoffTorrentUri = null
            // A privacy gate may have delayed the previous handoff. A new
            // explicit source must not revive it when that gate opens later.
            phoneHandoffMagnet = null
            serverHandoffTorrent = null
            localFromCombined = false
            if (server == null) {
                // Phone is the only currently verified target; still require
                // its normal explicit Add confirmation, never start here.
                phoneHandoffTorrentUri = incomingTorrent
                onIncomingTorrentConsumed()
                section = Section.LOCAL_ADD
            } else {
                // Connected remote can be chosen, but saved/offline profiles
                // confer no execution authority until authenticated.
                section = Section.COMBINED
            }
        }
    }
    // A file shared to "Create torrent" opens the Create review; nothing is read yet.
    val seedingEnvironments = remember(savedProfiles, aliasScope, server) {
        buildList {
            add(SeedingEnvironment.phone())
            savedProfiles.forEach { add(SeedingEnvironment.server(it.credentials.endpoint, it.credentials.username)) }
            if (server != null && aliasScope.isNotBlank())
                add(SeedingEnvironment.server(aliasScope.substringBefore('\u0000'), aliasScope.substringAfter('\u0000', "")))
        }.distinctBy { it.id }
    }
    var createRequest by remember { mutableStateOf<io.github.gamesbyian.chunks.create.SharedCreateDocument.Request?>(null) }
    LaunchedEffect(incomingCreate) {
        if (incomingCreate != null) {
            createRequest = incomingCreate
            toolOrigin = null
            section = Section.CREATE
            onIncomingCreateConsumed()
        }
    }

    // Android Share/Open With is source authority, never target authority. A
    // live server must not silently make its own Add form the default: choose
    // the exact destination first. Keep passkey-bearing source process-only;
    // this temporary selector is replaced by the single Universal Add form.
    LaunchedEffect(sharedMagnet) {
        if (sharedMagnet != null) {
            remoteAddSessions.clearPendingSourceSelection()
            // New explicit share replaces any older, unconsumed document review.
            phoneHandoffTorrentUri = null
            phoneHandoffMagnet = null
            serverHandoffTorrent = null
            if (server == null) {
                pendingSharedMagnet = null
                phoneHandoffMagnet = sharedMagnet
                localFromCombined = false
                section = Section.LOCAL_ADD
            } else {
                pendingSharedMagnet = sharedMagnet
                section = Section.COMBINED
            }
            onIncomingMagnetConsumed()
        }
    }

    val refresh: () -> Unit = {
        val remote = server
        if (remote != null) {
            if (loading) {
                // A mutation might settle during a manual refresh. Queue one
                // additional read rather than silently dropping its readback.
                remoteFence.queueRefresh()
            } else {
                loading = true
                scope.launch {
                    var again: Boolean
                    do {
                        val ticket = remoteFence.begin(requestedView)
                        val readbackTicket = mutationGate.ticket()
                        if (server === remote) error = null
                        try {
                            val snapshot = remote.listTransfersWithMetadata(ticket.view)
                            if (server === remote && remoteFence.current(ticket) &&
                                mutationGate.acceptFreshRead(readbackTicket)) {
                                transfers = snapshot
                                transfersObservedAt = System.currentTimeMillis()
                                snapshotRevision++
                                currentView = ticket.view
                                stale = false
                                // Previous file inspections belong to the OLD
                                // snapshot and must not arrive after this one.
                                inspectionFence.invalidate()
                                expandedHash = null
                                files = null
                                filesError = null
                                filesLoading = false
                            } else if (server === remote && remoteFence.current(ticket)) {
                                // A response from before/during an uncertain write
                                // cannot clear its safety latch or replace the queue.
                                stale = true
                            }
                        } catch (failure: Exception) {
                            if (server === remote && remoteFence.current(ticket)) {
                                stale = true
                                error = readableFailure(failure)
                                loginRefusedError = if (isLoginRefused(failure)) error else null
                            }
                        }
                        again = server === remote && remoteFence.current(ticket) &&
                            remoteFence.consumeQueuedRefresh()
                    } while (again)
                    if (server === remote) loading = false
                }
            }
        }
    }

    /** Disconnect does not forget credentials, transfer bytes or Add draft. */
    fun disconnectCurrentRemote() {
        remoteFence.invalidate()
        RemoteCredentialLock.invalidate()
        inspectionFence.invalidate()
        mutationGate.reset()
        server = null
        aliasScope = ""
        aliasSessions.clear()
        version = ""
        transfers = emptyList()
        transfersObservedAt = null
        expandedHash = null
        files = null
        filesError = null
        filesLoading = false
        loading = false
        error = null
        stale = false
        currentView = "main"
        requestedView = "main"
        verifiedConnectionNotice = null
        returnFromStartupConnectToCanonical = false
        section = Section.SERVER
        val oldEndpoint = endpoint
        val oldUsername = username
        val oldPassword = password
        val credentialRevision = credentialChanges.snapshotRevision()
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                try { vault.load() } catch (_: Exception) { null }
            }
            // A stale vault read must never replace a new connection, Forget,
            // updated login draft or a newer authenticated principal.
            if (server == null && !loading && section == Section.SERVER &&
                rememberLogin && saved != null &&
                credentialChanges.isLatest(credentialRevision) &&
                endpoint == oldEndpoint && username == oldUsername && password == oldPassword) {
                endpoint = saved.endpoint
                username = saved.username
                password = saved.password
            }
        }
    }

    verifiedConnectionNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = {
                verifiedConnectionNotice = null
                section = Section.SERVER
            },
            title = { Text("Connection verified") },
            text = {
                Text(when (notice.saved) {
                    true -> "${notice.label} is connected and saved. Open its transfers from Connections."
                    false -> "${notice.label} is connected, but a saved profile was not confirmed. Check the Remember setting before restarting."
                    null -> "${notice.label} is connected. Credential storage is still being confirmed; you can continue to Connections."
                })
            },
            confirmButton = {
                TextButton(onClick = {
                    verifiedConnectionNotice = null
                    section = Section.SERVER
                }) { Text("Continue to Connections") }
            }
        )
    }

    val paneTitle = ScreenPaneTitles.of(section.name, section.label,
        if (server != null && aliasScope.isNotBlank()) serverEnvironmentLabel(aliasScope) else null)
    Column(
        modifier = Modifier.fillMaxSize().background(Cream).safeDrawingPadding()
            .semantics { this.paneTitle = paneTitle },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (section == Section.TRANSFERS && server != null) {
            CompactChunksHeader()
        } else {
            ChunksBrandHeader()
        }
        // One measured viewport and one shared footer for every destination.
        // Child routes must not each own competing bottom navigation bars.
        Box(Modifier.weight(1f)) {

        if (section == Section.ARCHIVES) {
            LocalTransferPrivacyGate(
                scope = TransferCollectionStore.LOCAL_ENGINE_SCOPE,
                onBack = { leaveTool() }
            ) {
                LocalZipReviewScreen(onBack = { leaveTool() })
            }
        } else if (section == Section.SNAPSHOT_INSPECT) {
            LocalTransferPrivacyGate(
                scope = TransferCollectionStore.LOCAL_ENGINE_SCOPE,
                onBack = { section = Section.TRANSFERS }
            ) {
                SnapshotDocumentInspectScreen(onBack = { section = Section.TRANSFERS })
            }
        } else if (section == Section.ADD_CHOOSE) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add a transfer", style = MaterialTheme.typography.headlineSmall)
                Text("Choose its destination. The source and Start choice remain yours to review; opening a destination never submits a torrent.")
                OutlinedButton(onClick = { section = Section.LOCAL_ADD },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("On this device")
                }
                if (server != null && aliasScope.isNotBlank()) {
                    OutlinedButton(onClick = { section = Section.ADD },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        // Avoid disclosing a saved account name in this local
                        // chooser before the independent remote privacy gate.
                        Text("On the connected server")
                    }
                }
                if (savedProfiles.isNotEmpty() && server == null) {
                    OutlinedButton(onClick = { section = Section.SERVER },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Connect to a saved server")
                    }
                }
            }
        } else if (section == Section.COMBINED) {
            CombinedTransfersScreen(
                showNavigationFooter = false,
                scopeFocusRequest = savedProfileCatalogFocus,
                onScopeFocusConsumed = { savedProfileCatalogFocus = null },
                returnToCatalogRevision = returnToCatalogRevision,
                showAllEnvironmentsRevision =
                    showAllEnvironmentsRevision.takeIf { it > consumedShowAllEnvironmentsRevision } ?: 0L,
                onShowAllEnvironmentsConsumed = { consumed ->
                    // A slower old completion cannot consume a newer explicit tap.
                    consumedShowAllEnvironmentsRevision =
                        maxOf(consumedShowAllEnvironmentsRevision, consumed)
                },
                enableFirstRunWelcome = true,
                // A legacy saved vault login may precede roster migration. Its
                // existence alone forbids a misleading fresh-install Welcome.
                // Do not hand credentials to the catalog or trigger a new RPC.
                hasRememberedRemoteLogin = remembered != null,
                // The existing Add destination remains the sole admission
                // coordinator until source-first Universal Add is integrated.
                onWelcomeAdd = { section = Section.ADD_CHOOSE },
                listState = combinedListState,
                searchState = combinedSearch,
                // Only this session's positively verified Add on the current account.
                recentlyAddedTransfer = latestAdded?.takeIf { key ->
                    key.environment == io.github.gamesbyian.chunks.model.EnvironmentSnapshots.PHONE ||
                        (server != null && remoteAdded?.combinedKey(aliasScope) == key)
                },
                server = server?.let { remote ->
                    CombinedServerSource(aliasScope, transfers, stale = stale || loading,
                        fullView = currentView == "main", observedAtEpochMillis = transfersObservedAt,
                        // The same lease, readback gate and refresh the server list itself uses.
                        lifecycle = CombinedServerLifecycle(
                            begin = {
                                if (server === remote && !loading && !stale && mutationGate.tryStart()) {
                                    stale = true
                                    true
                                } else false
                            },
                            settled = {
                                if (server === remote) {
                                    mutationGate.settled()
                                    stale = true
                                    refresh()
                                }
                            },
                            setStarted = remote::setStarted,
                            probe = remote::probe,
                            inspectStall = { hash ->
                                io.github.gamesbyian.chunks.rpc.RemoteStallInspector.inspect(remote, hash)
                            }),
                        authenticatedSession = remote)
                },
                savedProfiles = savedProfiles.map { profile ->
                    CombinedSavedProfile(profile.id, profile.label + " · " + profile.address,
                        aliasScope = RemoteVerifiedUiIdentity.aliasScopeOf(profile.credentials),
                        // Read-only "main" list with this profile's own saved credentials (HTTPS enforced).
                        listOnly = { verifiedSavedProfileMainInventory(profile.credentials) },
                        shortLabel = profile.label)
                },
                onOpenPhone = { localFromCombined = true; section = Section.LOCAL },
                // Opening the phone inventory is non-mutating. Only the explicit
                // Add action hands off the pending magnet to its review.
                onAddToPhone = {
                    val selectedDocument = incomingTorrent
                    if (selectedDocument != null) {
                        phoneHandoffTorrentUri = selectedDocument
                        onIncomingTorrentConsumed()
                    } else {
                        pendingSharedMagnet?.let { source ->
                            phoneHandoffMagnet = source
                            pendingSharedMagnet = null
                        }
                    }
                    // The existing phone review reads only an explicitly
                    // selected URI, under its own independent privacy gate.
                    localFromCombined = true
                    section = Section.LOCAL_ADD
                },
                pendingSharedMagnet = pendingSharedMagnet != null && incomingTorrent == null,
                pendingSharedLink = pendingSharedMagnet?.let {
                    TorrentLinkCheck.inspect(it) is TorrentLinkCheck.Fetchable
                } == true && incomingTorrent == null,
                pendingSharedTorrent = incomingTorrent != null,
                readingSharedTorrent = sharedTorrentReading,
                sharedTorrentError = sharedTorrentError,
                onConnections = { section = Section.SERVER },
                onOpenServer = if (server != null) ({ section = Section.TRANSFERS }) else null,
                onAddToServer = if (server != null) ({
                    val sharedDocument = incomingTorrent
                    if (sharedDocument != null && !sharedTorrentReading &&
                        server != null && aliasScope.isNotBlank()) {
                        // Selecting a remote authorizes only a bounded, read-only
                        // local document parse. No rTorrent operation is attempted.
                        val selectedServer = server
                        val selectedScope = aliasScope
                        sharedTorrentReading = true
                        sharedTorrentError = null
                        scope.launch {
                            try {
                                val reviewed = withContext(Dispatchers.IO) {
                                    SharedTorrentDocument.read(context.contentResolver, sharedDocument)
                                }
                                if (server === selectedServer && aliasScope == selectedScope &&
                                    incomingTorrent == sharedDocument && section == Section.COMBINED) {
                                    // A newer, explicit document supersedes an older
                                    // text draft; the remote Add form reviews these bytes.
                                    magnet = ""
                                    remoteAddSessions.clearPendingSourceSelection()
                                    serverHandoffTorrent = reviewed.bytes
                                    onIncomingTorrentConsumed()
                                    section = Section.ADD
                                } else {
                                    sharedTorrentError =
                                        "The shared source or signed-in server changed. Choose again; nothing was added."
                                }
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                sharedTorrentError =
                                    "The shared .torrent could not be read or verified (v1 metainfo, maximum 2 MiB). Nothing was added."
                            } finally {
                                sharedTorrentReading = false
                            }
                        }
                    } else if (sharedDocument != null && !sharedTorrentReading) {
                        sharedTorrentError =
                            "This rTorrent account is no longer verified. Reconnect before reviewing this .torrent."
                    } else if (sharedDocument == null) {
                        pendingSharedMagnet?.let { source ->
                            prepareRemoteMagnet(source)
                            pendingSharedMagnet = null
                        }
                        section = Section.ADD
                    }
                }) else null,
                onOpenTransfer = { key ->
                    // Resolve an exact owner. A saved-only server, a disconnected
                    // profile or the same hash on a different server must never
                    // inherit the currently authenticated server's controls.
                    val authenticatedId = if (server != null)
                        io.github.gamesbyian.chunks.model.EnvironmentSnapshots.serverIdOfAliasScope(aliasScope)
                        else null
                    when (ExactOwningListNavigation.route(key, authenticatedId)) {
                        ExactOwningListNavigation.Route.PHONE -> {
                            phoneSearchPrefill = key.infoHash
                            localFromCombined = true
                            section = Section.LOCAL
                        }
                        ExactOwningListNavigation.Route.AUTHENTICATED_SERVER -> {
                            setRemoteQuery(key.infoHash)
                            filter = TransferFilter.ALL
                            // Combined may show a full "main" inventory while
                            // the signed-in screen is still on a filtered view.
                            // The safe read-only refresh is queued if a read is
                            // already active; no mutation or implicit retry.
                            ExactOwningListNavigation.requestMainIfNeeded(
                                currentView, requestedView,
                                setRequestedView = { requestedView = it },
                                refresh = refresh
                            )
                            section = Section.TRANSFERS
                        }
                        ExactOwningListNavigation.Route.UNAVAILABLE -> Unit
                    }
                },
                onBack = {
                    // An explicit saved-profile deep link must return to
                    // Connections, not the old phone/server-specific lists.
                    val returnToConnections = combinedFromConnections
                    combinedFromConnections = false
                    val returnToPhone = combinedFromPhone || server == null
                    localFromCombined = false
                    section = when {
                        returnToConnections -> Section.SERVER
                        returnToPhone -> Section.LOCAL
                        else -> Section.TRANSFERS
                    }
                }
            )
        } else if (section == Section.SEEDING) {
            SeedingRulesScreen(seedingEnvironments, onBack = { leaveTool() })
        } else if (section == Section.MEDIA) {
            MediaSettingsScreen(onBack = { leaveTool() })
        } else if (section == Section.CREATE && server == null) {
            io.github.gamesbyian.chunks.create.CreateTorrentScreen(onBack = { leaveTool() },
                shared = createRequest, onSharedConsumed = { createRequest = null })
        } else if (section in setOf(Section.LOCAL, Section.LOCAL_ADD, Section.OPTIONS) && server == null) {
            LocalTransferPrivacyGate(
                scope = TransferCollectionStore.LOCAL_ENGINE_SCOPE,
                onBack = { leavePhone() },
                controlsInContent = true
            ) {
                LocalEngineScreen(
                    pane = when (section) {
                        Section.LOCAL_ADD -> PhonePane.ADD
                        Section.OPTIONS -> PhonePane.OPTIONS
                        else -> PhonePane.TRANSFERS
                    },
                    showNavigationFooter = false,
                    listScroll = when (section) {
                        Section.LOCAL_ADD -> phoneAddScroll
                        Section.OPTIONS -> phoneOptionsScroll
                        else -> phoneListScroll
                    }, searchState = phoneSearch, optionsSearchState = phoneOptionsSearch,
                    magnetDraftState = addSourceDraft,
                    addPlaceState = phoneAddPlace, addFolderState = phoneAddFolder, addStartState = phoneAddStart,
                    addArtworkState = addArtworkChoices.onPhone(),
                    pendingAddState = phoneReviewedTorrent,
                    phoneAddAttemptSession = activePhoneAddAttempt,
                    onBack = { leavePhone() },
                    onSeedingRules = { openTool(Section.SEEDING,
                        if (section == Section.OPTIONS) Section.OPTIONS else Section.LOCAL) },
                    onMediaSettings = { openTool(Section.MEDIA,
                        if (section == Section.OPTIONS) Section.OPTIONS else Section.LOCAL) },
                    onCreateTorrent = { openTool(Section.CREATE,
                        if (section == Section.OPTIONS) Section.OPTIONS else Section.LOCAL) },
                    onZipReview = { openTool(Section.ARCHIVES,
                        if (section == Section.OPTIONS) Section.OPTIONS else Section.LOCAL) },
                    onCombined = {
                        localFromCombined = false
                        combinedFromPhone = true
                        savedProfileCatalogFocus = null
                        showAllEnvironmentsRevision++
                        section = Section.COMBINED
                    },
                    showFirstRunWelcome = savedProfiles.isEmpty() && server == null,
                    onConnections = { section = Section.SERVER },
                    onOpenAdd = { section = Section.LOCAL_ADD },
                    onOpenOptions = { section = Section.OPTIONS },
                    remoteEnvironmentLabel = aliasScope.takeIf {
                        server != null && RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(it) != null
                    }?.let(::serverEnvironmentLabel),
                    onRemotePlaces = if (server != null &&
                        RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(aliasScope) != null)
                        ({ section = Section.PLACES }) else null,
                    onRemoteOrganize = if (server != null &&
                        RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(aliasScope) != null)
                        ({ section = Section.ORGANIZE }) else null,
                    onRemoteNames = if (server != null &&
                        RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(aliasScope) != null)
                        ({ section = Section.NAMES }) else null,
                    onRemoteSnapshot = if (server != null &&
                        RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(aliasScope) != null)
                        ({ section = Section.SNAPSHOT }) else null,
                    initialMagnet = phoneHandoffMagnet,
                    initialSearch = phoneSearchPrefill,
                    onSearchConsumed = { phoneSearchPrefill = null },
                    onAcknowledgedAdd = { hash -> latestAdded = phoneAddedKey(hash) },
                    returnFocusTool = phoneReturnFocus, onReturnFocusConsumed = { phoneReturnFocus = null },
                    initialTorrentUri = phoneHandoffTorrentUri,
                    onTorrentConsumed = { consumed ->
                        if (phoneHandoffTorrentUri == consumed) phoneHandoffTorrentUri = null
                    },
                    onPrefillConsumed = { phoneHandoffMagnet = null },
                    onPrepareRemote = { draft ->
                        prepareRemoteMagnet(draft)
                        section = Section.TRANSFERS
                    },
                    onPrepareRemoteTorrent = { document ->
                        remoteAddSessions.clearPendingSourceSelection()
                        serverHandoffTorrent = document
                        section = Section.TRANSFERS
                    }
                )
            }
        } else if (server == null || section == Section.SERVER) {
            ConnectionScreen(
                connectedSessionName = server?.let {
                    savedProfiles.firstOrNull { profile ->
                        profile.id == io.github.gamesbyian.chunks.model.EnvironmentSnapshots
                            .serverIdOfAliasScope(aliasScope)?.value
                    }?.label ?: "Connected rTorrent server"
                },
                connectedVersion = version,
                connectedRequestedView = requestedView,
                onSelectConnectedView = { requestedView = it },
                onLoadConnectedView = refresh,
                onConnectedSeedingRules = { openTool(Section.SEEDING, Section.SERVER) },
                onViewConnectedTransfers = {
                    io.github.gamesbyian.chunks.model.EnvironmentSnapshots
                        .serverIdOfAliasScope(aliasScope)?.value?.let { id ->
                            consumedShowAllEnvironmentsRevision = showAllEnvironmentsRevision
                            savedProfileCatalogFocus = id
                            combinedFromConnections = true
                            section = Section.COMBINED
                        }
                },
                onDisconnectCurrent = { disconnectCurrentRemote() },
                startupReconnectEligible = RemoteStartupReconnectPolicy.shouldAttempt(
                    rememberingEnabled = rememberLogin,
                    hasSavedVerifiedLogin = remembered != null,
                    alreadyAttempted = startupReconnectAttempted
                ),
                onStartupReconnectConsumed = {
                    startupReconnectAttempted = true
                    returnFromStartupConnectToCanonical = section == Section.SERVER && server == null
                },
                endpoint = endpoint, username = username, password = password,
                rpcPath = rpcPath, onRpcPath = { rpcPath = it },
                error = error, loading = loading,
                diagnostics = connectDiagnostics,
                onCancelConnect = {
                    // Discovery is read-only, so abandoning it changes nothing.
                    connectJob?.cancel()
                    remoteFence.invalidate()
                    RemoteCredentialLock.invalidate()
                    returnFromStartupConnectToCanonical = false
                    loading = false
                    connectDiagnostics = ConnectionDiagnostics()
                    error = "Connection check cancelled. Nothing was changed on the server."
                },
                rememberLogin = rememberLogin, savedLoginPresent = savedLoginPresent,
                savedProfiles = savedProfiles, credentialNotice = credentialNotice,
                onSelectProfile = { profile ->
                    endpoint = profile.credentials.endpoint
                    rpcPath = ""
                    username = profile.credentials.username
                    password = profile.credentials.password
                    error = null
                    credentialNotice = "Selected saved profile. Connect to authenticate."
                },
                onRenameProfile = { profile, displayLabel ->
                    val saved = withContext(Dispatchers.IO) {
                        try { profileRoster.rename(profile.id, displayLabel) }
                        catch (_: Exception) { false }
                    }
                    if (saved) savedProfiles = withContext(Dispatchers.IO) { profileRoster.list() }
                    saved
                },
                onForgetProfile = { profile ->
                    if (!loading) {
                        // Revoke pending Connect saves at the moment Forget
                        // is requested, not after its IO coroutine starts.
                        RemoteCredentialLock.invalidate()
                        credentialChanges.submit(scope) { revision ->
                            val removed = withContext(Dispatchers.IO) {
                                try {
                                    RemoteProfileForget.forget(profileRoster, vault, profile)
                                } catch (_: Exception) { false }
                            }
                            if (credentialChanges.isLatest(revision)) {
                                val refreshed = withContext(Dispatchers.IO) { profileRoster.list() }
                                // A newer tap may occur while this IO read is suspended.
                                if (!credentialChanges.isLatest(revision)) return@submit
                                savedProfiles = refreshed
                                savedLoginPresent = vault.hasSavedLogin() || refreshed.isNotEmpty()
                                if (removed && endpoint == profile.credentials.endpoint &&
                                    username == profile.credentials.username) password = ""
                                credentialNotice = if (removed) "Saved profile forgotten on this device."
                                    else "Could not confirm full credential removal. Review saved profiles."
                            }
                        }
                    }
                },
                onRememberLogin = { enabled ->
                    // A pending Connect must never persist under an obsolete
                    // remember preference while the durable edit is queued.
                    RemoteCredentialLock.invalidate()
                    rememberLogin = enabled
                    credentialChanges.submit(scope) { revision ->
                        val persisted = withContext(Dispatchers.IO) {
                            try {
                                if (enabled) {
                                    // Never re-expose ciphertext left after a failed opt-out.
                                    val safelyCleared = if (!vault.rememberingEnabled())
                                        profileRoster.clearAll() else true
                                    safelyCleared && vault.setRememberingEnabled(true)
                                } else {
                                    val disabled = vault.setRememberingEnabled(false)
                                    val cleared = profileRoster.clearAll()
                                    disabled && cleared
                                }
                            } catch (_: Exception) { false }
                        }
                        if (credentialChanges.isLatest(revision)) {
                            val refreshed = withContext(Dispatchers.IO) {
                                val profiles = profileRoster.list()
                                Triple(vault.rememberingEnabled(), profiles,
                                    vault.hasSavedLogin() || profiles.isNotEmpty())
                            }
                            if (!credentialChanges.isLatest(revision)) return@submit
                            rememberLogin = refreshed.first
                            savedProfiles = refreshed.second
                            savedLoginPresent = refreshed.third
                            credentialNotice = when {
                                !persisted -> "Could not save the credential remembering preference. Retry this change."
                                !enabled -> "Login remembering disabled across restarts; saved credentials erased."
                                else -> "Login remembering enabled. Credentials are saved after your next successful Connect."
                            }
                        }
                    }
                },
                onForgetLogin = {
                    RemoteCredentialLock.invalidate()
                    rememberLogin = false
                    credentialChanges.submit(scope) { revision ->
                        val cleared = withContext(Dispatchers.IO) {
                            try {
                                val disabled = vault.setRememberingEnabled(false)
                                val rosterErased = profileRoster.clearAll()
                                disabled && rosterErased
                            } catch (_: Exception) { false }
                        }
                        if (credentialChanges.isLatest(revision)) {
                            val refreshed = withContext(Dispatchers.IO) { profileRoster.list() }
                            if (!credentialChanges.isLatest(revision)) return@submit
                            savedProfiles = refreshed
                            savedLoginPresent = vault.hasSavedLogin() || refreshed.isNotEmpty()
                            if (cleared) {
                                password = ""
                                credentialNotice = "Saved login erased. This session's password field was cleared."
                            } else credentialNotice = "Could not erase saved login. Retry before leaving the app."
                        }
                    }
                },
                onOpenProfileTransfers = { profile ->
                    // This is a view filter, never an implicit Connect or a write.
                    // The encrypted roster's immutable ID is the only selector.
                    consumedShowAllEnvironmentsRevision = showAllEnvironmentsRevision
                    savedProfileCatalogFocus = profile.id
                    combinedFromConnections = true
                    section = Section.COMBINED
                },
                onLocalZipReview = { openTool(Section.ARCHIVES, Section.SERVER) },
                onInspectSnapshot = { section = Section.SNAPSHOT_INSPECT },
                onLocalEngine = { localFromCombined = false; section = Section.LOCAL },
                onCreateTorrent = { openTool(Section.CREATE, Section.SERVER) },
                onEndpoint = { endpoint = it }, onUsername = { username = it },
                onPassword = { password = it },
                onConnect = {
                    // An advanced RPC path makes this one exact address: no discovery.
                    val target = try { RemoteSmartConnect.withExactPath(endpoint, rpcPath) }
                        catch (invalid: IllegalArgumentException) {
                            error = invalid.message
                            connectDiagnostics = ConnectionDiagnostics()
                            null
                        }
                    if (!loading && target != null && server == null) {
                        loading = true
                        error = null
                        val ticket = remoteFence.begin("main")
                        // Invalidate every earlier pending Connect's right to
                        // remember credentials, before any network suspension.
                        RemoteCredentialLock.invalidate()
                        // Freeze credentials for this one connection attempt.
                        // A later edit or lifecycle reset cannot redirect it.
                        val settings = RemoteCredentials(target, username, password)
                        connectDiagnostics = ConnectionDiagnostics()
                        connectJob = scope.launch {
                            try {
                                // A previously requested OFF→ON/Forget must
                                // commit in tap order before this Connect
                                // claims fresh credential-saving authority.
                                credentialChanges.awaitSettled()
                                if (!remoteFence.current(ticket)) return@launch
                                // Human Forget/Remember taps occurring during the
                                // ensuing RPC or saved-profile IO must own the
                                // notice and profile UI, even on a successful Connect.
                                val connectedUiRevision = credentialChanges.snapshotRevision()
                                val saveThisLogin = rememberLogin
                                val credentialTicket = RemoteCredentialLock.beginConnection()
                                val discovered = RemoteSmartConnect.connect(
                                    settings.endpoint, settings.username, settings.password,
                                    onAttempt = { index, total, candidate ->
                                        if (remoteFence.current(ticket)) connectDiagnostics =
                                            ConnectionDiagnostics(ConnectionDiagnostics.progress(index, total, candidate))
                                    }
                                ) { credentials ->
                                    val connection = RTorrentRemote(credentials)
                                    val verifiedVersion = connection.version()
                                    val verifiedList = connection.listTransfersWithMetadata(ticket.view)
                                    // Public version/inventory must not verify a typed login or
                                    // authorize remembering it. Anonymous connections remain opt-in
                                    // by leaving both credential fields empty.
                                    requireRemoteConnectIdentityEvidence(credentials, connection)
                                    Triple(connection, verifiedVersion, verifiedList)
                                }
                                val (candidate, v, list) = discovered.response
                                // The verified RPC endpoint may differ from
                                // the pasted browser URL or a hostname with a
                                // single candidate. Never scope a verified
                                // principal to an unverified draft address.
                                val verifiedSettings = discovered.credentials
                                if (remoteFence.current(ticket) && server == null) {
                                    inspectionFence.invalidate()
                                    mutationGate.reset()
                                    transfers = list
                                    transfersObservedAt = System.currentTimeMillis()
                                    snapshotRevision++
                                    version = v
                                    aliasScope = RemoteVerifiedUiIdentity.aliasScopeOf(verifiedSettings)
                                    // A phone-prepared magnet may have waited through login.
                                    // Apply its source-mode selection once to this exact account.
                                    remoteAddSessions.forAccount(aliasScope)
                                    endpoint = verifiedSettings.endpoint
                                    // The fields stay aligned with the authenticated
                                    // probe even if edited while Connect was running.
                                    // Otherwise Disconnect could offer a reconnect
                                    // to an unverified different account.
                                    username = verifiedSettings.username
                                    // The full address now carries the path.
                                    rpcPath = ""
                                    server = candidate
                                    stale = false
                                    // A phone-origin .torrent can be prepared before a
                                    // remote login. Keep its reviewed Add draft visible
                                    // after connecting, without submitting any RPC.
                                    val startupReturn = returnFromStartupConnectToCanonical &&
                                        section == Section.SERVER
                                    returnFromStartupConnectToCanonical = false
                                    // Preserve any source draft without implicitly
                                    // submitting it when the Connect probe finishes.
                                    section = if (startupReturn) Section.COMBINED else Section.SERVER
                                    val verifiedId = io.github.gamesbyian.chunks.model
                                        .EnvironmentSnapshots.serverIdOfAliasScope(aliasScope)?.value
                                    if (startupReturn) {
                                        // Read-only startup catalog focus. No Add target.
                                        consumedShowAllEnvironmentsRevision = showAllEnvironmentsRevision
                                        savedProfileCatalogFocus = verifiedId
                                        combinedFromConnections = true
                                    } else {
                                        verifiedConnectionNotice = VerifiedConnectionNotice(
                                            savedProfiles.firstOrNull { it.id == verifiedId }?.label
                                                ?: RememberedRemoteProfileRoster.addressOf(verifiedSettings.endpoint),
                                            saved = null // persistence has not been observed yet
                                        )
                                    }
                                    currentView = ticket.view
                                    requestedView = ticket.view
                                    // A successful authenticated Connect is the only time
                                    // the default-on vault may save fresh credentials.
                                    // Failures never persist an unverified password.
                                    val retained = withContext(Dispatchers.IO) {
                                        try {
                                            if (saveThisLogin) {
                                                RemoteCredentialLock.saveVerifiedPairIfCurrent(
                                                    vault, profileRoster, verifiedSettings,
                                                    credentialTicket
                                                )
                                            } else {
                                                // A superseded or canceled Connect must not
                                                // erase a newer account's saved credentials.
                                                // Hold the same monitor across the epoch
                                                // check and the destructive opt-out.
                                                RemoteRememberChoice.declineIfCurrent(
                                                    vault, credentialTicket
                                                )
                                            }
                                        } catch (_: Exception) { false }
                                    }
                                    // IO can outlive a UI session change. Never write a
                                    // stale Connect's notice/profile state over a new
                                    // authenticated session or a disconnected form.
                                    if (remoteFence.current(ticket) && server === candidate &&
                                        credentialChanges.isLatest(connectedUiRevision)) {
                                        val refreshed = withContext(Dispatchers.IO) { profileRoster.list() }
                                        // Either IO may suspend; do not let this
                                        // Connect overwrite a subsequent Forget or
                                        // another authenticated screen's state.
                                        if (remoteFence.current(ticket) && server === candidate &&
                                            credentialChanges.isLatest(connectedUiRevision)) {
                                            savedProfiles = refreshed
                                            savedLoginPresent = vault.hasSavedLogin() || refreshed.isNotEmpty()
                                            credentialNotice = when {
                                                !retained -> "Connected, but login remembering settings could not be saved. " +
                                                    "Confirm the preference before restarting."
                                                !saveThisLogin -> "Connected without remembering credentials. Your opt-out persists across restarts."
                                                else -> null
                                            }
                                            // Do not retain another copy in the screen field.
                                            password = ""
                                            val verifiedId = io.github.gamesbyian.chunks.model
                                                .EnvironmentSnapshots.serverIdOfAliasScope(aliasScope)?.value
                                            val savedName = refreshed.firstOrNull {
                                                it.id == verifiedId
                                            }?.label
                                            // Never resurrect a dismissed acknowledgement,
                                            // and never call a pending save "saved".
                                            verifiedConnectionNotice?.let { existing ->
                                                verifiedConnectionNotice = existing.copy(
                                                    label = savedName ?: existing.label,
                                                    saved = retained && saveThisLogin && savedName != null
                                                )
                                            }
                                        }
                                    }
                                }
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                throw cancel
                            } catch (failure: Exception) {
                                if (remoteFence.current(ticket) && server == null) {
                                    returnFromStartupConnectToCanonical = false
                                    error = readableFailure(failure)
                                    connectDiagnostics = if (failure is RemoteSmartConnect.ConnectionFailed)
                                        ConnectionDiagnostics(attempts = failure.attempts,
                                            advice = RemoteSmartConnect.repairAdvice(failure, settings.endpoint))
                                    else ConnectionDiagnostics()
                                }
                            } finally {
                                if (remoteFence.current(ticket) && server == null) loading = false
                                // On success, the session is no longer in the connection UI,
                                // but the busy indicator must also return to idle.
                                if (remoteFence.current(ticket) && server != null) loading = false
                            }
                        }
                    }
                }
            )
        } else {
            val connectedRemote = requireNotNull(server)
            val remotePrivacyScope = remember(aliasScope) {
                RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(aliasScope)
            }
            if (section in setOf(Section.LOCAL, Section.LOCAL_ADD, Section.OPTIONS)) {
                // Phone and rTorrent privacy are independent. A locked remote
                // profile can still navigate to the separately gated phone.
                LocalTransferPrivacyGate(
                    scope = TransferCollectionStore.LOCAL_ENGINE_SCOPE,
                    onBack = { leavePhone() },
                    controlsInContent = true
                ) {
                    LocalEngineScreen(
                        pane = when (section) {
                        Section.LOCAL_ADD -> PhonePane.ADD
                        Section.OPTIONS -> PhonePane.OPTIONS
                        else -> PhonePane.TRANSFERS
                    },
                    showNavigationFooter = false,
                    listScroll = when (section) {
                        Section.LOCAL_ADD -> phoneAddScroll
                        Section.OPTIONS -> phoneOptionsScroll
                        else -> phoneListScroll
                    }, searchState = phoneSearch, optionsSearchState = phoneOptionsSearch,
                        magnetDraftState = addSourceDraft,
                        addPlaceState = phoneAddPlace, addFolderState = phoneAddFolder, addStartState = phoneAddStart,
                        addArtworkState = addArtworkChoices.onPhone(),
                        pendingAddState = phoneReviewedTorrent,
                        phoneAddAttemptSession = activePhoneAddAttempt,
                        onBack = { leavePhone() },
                        onSeedingRules = { openTool(Section.SEEDING,
                        if (section == Section.OPTIONS) Section.OPTIONS else Section.LOCAL) },
                        onMediaSettings = { openTool(Section.MEDIA,
                        if (section == Section.OPTIONS) Section.OPTIONS else Section.LOCAL) },
                        onCreateTorrent = { openTool(Section.CREATE,
                        if (section == Section.OPTIONS) Section.OPTIONS else Section.LOCAL) },
                        onZipReview = { openTool(Section.ARCHIVES,
                        if (section == Section.OPTIONS) Section.OPTIONS else Section.LOCAL) },
                        onCombined = {
                        localFromCombined = false
                        combinedFromPhone = true
                        savedProfileCatalogFocus = null
                        showAllEnvironmentsRevision++
                        section = Section.COMBINED
                    },
                        onConnections = { section = Section.SERVER },
                        onOpenAdd = { section = Section.LOCAL_ADD },
                        onOpenOptions = { section = Section.OPTIONS },
                        remoteEnvironmentLabel = aliasScope.takeIf {
                            RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(it) != null
                        }?.let(::serverEnvironmentLabel),
                        onRemotePlaces = if (remotePrivacyScope != null) ({ section = Section.PLACES }) else null,
                        onRemoteOrganize = if (remotePrivacyScope != null) ({ section = Section.ORGANIZE }) else null,
                        onRemoteNames = if (remotePrivacyScope != null) ({ section = Section.NAMES }) else null,
                        onRemoteSnapshot = if (remotePrivacyScope != null) ({ section = Section.SNAPSHOT }) else null,
                        initialMagnet = phoneHandoffMagnet,
                        initialSearch = phoneSearchPrefill,
                        onSearchConsumed = { phoneSearchPrefill = null },
                        onAcknowledgedAdd = { hash -> latestAdded = phoneAddedKey(hash) },
                        returnFocusTool = phoneReturnFocus, onReturnFocusConsumed = { phoneReturnFocus = null },
                        initialTorrentUri = phoneHandoffTorrentUri,
                        canReviewOnConnectedServer = true,
                        serverCopy = if (remotePrivacyScope == null) null
                            else LocalServerCopyLink(runCatching {
                                java.net.URI(aliasScope.substringBefore('\u0000').trim()).host
                            }.getOrNull() ?: "your server") { bytes ->
                                // Re-read the default for each Add; the adapter checks it again.
                                val place = connectedRemote.defaultDirectory()
                                    ?: error("The server reports no default download location")
                                connectedRemote.loadStoppedTorrentBytes(bytes, place, useServerDefault = true)
                            },
                        // Only an unambiguous authenticated account can own a Move.
                        moveServer = if (remotePrivacyScope == null) null
                            else LocalMoveServer(aliasScope) { hash ->
                                TransferMoveObservations.server(connectedRemote, hash)
                            },
                        onTorrentConsumed = { consumed ->
                            if (phoneHandoffTorrentUri == consumed) phoneHandoffTorrentUri = null
                        },
                        onPrefillConsumed = { phoneHandoffMagnet = null },
                        onPrepareRemote = { draft ->
                            prepareRemoteMagnet(draft)
                            section = Section.ADD
                        },
                        onPrepareRemoteTorrent = { document ->
                            remoteAddSessions.clearPendingSourceSelection()
                            serverHandoffTorrent = document
                            section = Section.ADD
                        }
                    )
                }
            } else if (remotePrivacyScope == null) {
                // Never present a connected rTorrent queue with an ambiguous
                // account identity; scope policies could otherwise be bypassed.
                Text("Cannot verify which rTorrent profile owns these transfers. " +
                    "Go to the phone client or reconnect securely.",
                    color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { localFromCombined = false; section = Section.LOCAL },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Transfers on this device")
                }
            } else {
                RemoteTransferPrivacyGate(
                    scope = remotePrivacyScope,
                    onBack = { localFromCombined = false; section = Section.LOCAL },
                    controlsInContent = true
                ) {
            val beginRemoteMutation: () -> Boolean = {
                if (server === connectedRemote && !loading && !stale && mutationGate.tryStart()) {
                    stale = true
                    true
                } else false
            }
            val settledRemoteMutation: () -> Unit = {
                if (server === connectedRemote) {
                    mutationGate.settled()
                    stale = true
                    refresh()
                }
            }
            // Finish in Order on rTorrent advances only while connected here:
            // at most one guarded step per policy every 30 s, under the same
            // mutation gate as every other server change.
            LaunchedEffect(connectedRemote, aliasScope) {
                val store = FinishInOrderStore(context, aliasScope)
                while (true) {
                    kotlinx.coroutines.delay(30_000L)
                    if (remotePrivacyScope == null) continue
                    for (transfer in transfers.filter { it.started }) {
                        val record = withContext(Dispatchers.IO) { store.get(transfer.hash) } ?: continue
                        if (record.suspendedReason != null || (record.finished && record.pending.isEmpty())) continue
                        if (server !== connectedRemote || loading || stale || !mutationGate.tryStart()) break
                        // Only a positively Idle step is known to have sent
                        // no remote priority write. It must not consume the
                        // readback latch and block every later lifecycle action.
                        var confirmedNoWrite = false
                        try {
                            val step = try { stepRemoteFinishInOrder(connectedRemote, store, transfer.hash) }
                                catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                                catch (_: Exception) { null }
                            confirmedNoWrite = step is FinishInOrderRunner.Step.Idle
                        } finally {
                            if (confirmedNoWrite) {
                                mutationGate.abandonKnownNoWrite()
                            } else {
                                // Null/fault/cancellation may follow an applied
                                // priority write: retain the gate until a fresh
                                // read, without blindly dispatching another RPC.
                                mutationGate.settled()
                                if (server === connectedRemote) { stale = true; refresh() }
                            }
                        }
                    }
                }
            }
            // Reuse the already-authorized destinations. No search-created
            // settings screen, saved login or implicit server Add target.
            val optionsDestinations = Section.entries.filter {
                it != Section.ADD && it != Section.NAMES && it != Section.SNAPSHOT &&
                    it != Section.SEEDING && it != Section.SNAPSHOT_INSPECT &&
                    it != Section.COMBINED
            }
            val visibleDestinations = if (section == Section.TRANSFERS && optionsOpen)
                optionsDestinations.filter { optionsDestinationMatches(it.label, optionsSearch) }
            else optionsDestinations
            // Options (and every non-Transfers screen's destination list) is
            // height-bounded and scrolls vertically, so wrapped destinations and
            // expanded privacy controls can never push the screen and its footer
            // out of reach at 320dp and 200% type.
            // On other routes (Add, Places…) the destinations are a compact,
            // two-row-high strip so the screen itself (and its keyboard) keeps
            // its space; the open Transfers Options panel may use more.
            // A legacy remote options disclosure is kept only if explicitly
            // open. Other screens use the root footer and central Options.
            if (section == Section.TRANSFERS && optionsOpen) Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = if (section == Section.TRANSFERS)
                        (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.45f).dp
                        else 112.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (section == Section.TRANSFERS) {
                    ClearableTransferSearchField(
                        value = optionsSearch, onValueChange = { optionsSearch = it },
                        label = "Find a screen or tool"
                    )
                    val shortcuts = OptionsShortcut.search(optionsSearch)
                    if (visibleDestinations.isEmpty() && shortcuts.isEmpty()) {
                        Text("No matching Options screen. Clear search to see all destinations.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    // Settings that live inside existing screens. Each result only
                    // opens that screen; nothing is changed by searching or tapping.
                    shortcuts.forEach { shortcut ->
                        OutlinedButton(
                            onClick = {
                                optionsSearch = ""
                                when (shortcut) {
                                    OptionsShortcut.SEEDING_RULES -> openTool(Section.SEEDING, Section.TRANSFERS)
                                    OptionsShortcut.MEDIA -> openTool(Section.MEDIA, Section.TRANSFERS)
                                    OptionsShortcut.PHONE_SETTINGS -> { localFromCombined = false; section = Section.LOCAL }
                                    OptionsShortcut.ENVIRONMENT_COLOURS -> { combinedFromPhone = false; section = Section.COMBINED }
                                    OptionsShortcut.NAMES -> section = Section.NAMES
                                    OptionsShortcut.BACKUP -> section = Section.SNAPSHOT
                                    OptionsShortcut.INSPECT_BACKUP -> section = Section.SNAPSHOT_INSPECT
                                    // Already in this panel: clearing the search shows the
                                    // whole panel, which ends with Privacy & authentication.
                                    OptionsShortcut.PRIVACY -> Unit
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(shortcut.title, fontWeight = FontWeight.Bold)
                                Text(shortcut.where, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                // Wrap rather than scroll sideways: every destination stays reachable
                // by vertical layout, keyboard and TalkBack at 320dp and 200% type.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (tab in visibleDestinations) {
                        FilterChip(
                            selected = section == tab,
                            onClick = {
                                optionsSearch = ""
                                if (tab == Section.LOCAL) localFromCombined = false
                                if (tab == Section.MEDIA) openTool(Section.MEDIA, Section.TRANSFERS) else
                                section = tab
                            },
                            label = { Text(tab.label) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
                // Privacy & authentication belongs with Transfers Options, not pinned
                // above every screen; Lock now stays on top while a policy is active.
                if (section == Section.TRANSFERS)
                    io.github.gamesbyian.chunks.privacy.LocalPrivacyControls.current?.invoke()
            }
            if (error != null) {
                RefreshProblemNotice(error.orEmpty(), refreshing = loading, onRefresh = refresh,
                    // Signing in again is the real repair for a refused login.
                    onOpenServer = if (loginRefusedError != null && loginRefusedError == error)
                        ({ section = Section.SERVER }) else null)
            }
            // Per-account list position survives visiting Places, Server or
            // other routes; a different account starts at the top.
            val remoteListState = remember(aliasScope) { remoteContinuity.listState(aliasScope) }
            when (section) {
                Section.TRANSFERS -> RemoteEnvironmentCompositionBoundary(aliasScope, connectedRemote) {
                    TransfersScreen(
                    listState = remoteListState,
                    remote = connectedRemote, onLifecycleSettled = settledRemoteMutation,
                    onMutationStarted = beginRemoteMutation,
                    aliasStore = aliasStore, aliasScope = aliasScope,
                    aliasSessions = aliasSessions,
                    aliasRevision = aliasRevision, onAliasSaved = { aliasRevision++ },
                    list = transfers, view = currentView, loading = loading, stale = stale,
                    environmentLabel = savedProfiles.firstOrNull {
                        RemoteVerifiedUiIdentity.aliasScopeOf(it.credentials) == aliasScope
                    }?.label?.takeIf { it.isNotBlank() } ?: "rTorrent",
                    snapshotRevision = snapshotRevision,
                    progressObservedAt = transfersObservedAt,
                    addedRecord = remoteAdded,
                    query = query, onQuery = { setRemoteQuery(it) },
                    filter = filter, onFilter = { next ->
                        filter = next
                        remoteFilterStore.save(sortEnvironment, next)
                    },
                    onClearView = {
                        setRemoteQuery("")
                        filter = TransferFilter.ALL
                        remoteFilterStore.save(sortEnvironment, TransferFilter.ALL)
                    },
                    order = order, direction = orderDirection,
                    onSort = { key, direction ->
                        order = key
                        orderDirection = direction
                        sortEnvironment?.let { sortStore.set(it, key, direction) }
                    },
                    onCombined = {
                        combinedFromPhone = false
                        savedProfileCatalogFocus = null
                        showAllEnvironmentsRevision++
                        section = Section.COMBINED
                    },
                    onRefresh = refresh, onAdd = { section = Section.ADD },
                    onConnections = { section = Section.SERVER },
                    showNavigationFooter = false,
                    optionsOpen = optionsOpen,
                    onOptions = { optionsOpen = !optionsOpen; if (!optionsOpen) optionsSearch = "" },
                    onReviewNames = { section = Section.NAMES },
                    onExportSnapshot = { section = Section.SNAPSHOT },
                    onInspectSnapshot = { section = Section.SNAPSHOT_INSPECT },
                    onPreparePhone = { source ->
                        val prepared = TorrentIdentityHandoff.prepare(source.hash, source.name)
                        if (prepared != null) scope.launch {
                            // Public trackers let the phone find peers even before the
                            // server finishes. Private torrents: identity only, and their
                            // passkey-bearing tracker URLs are never read.
                            val trackers = try {
                                (connectedRemote.readPublicTrackers(source.hash)
                                    as? RTorrentRemote.PublicTrackers.Urls)?.urls.orEmpty()
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                throw cancel
                            } catch (_: Exception) { emptyList() }
                            if (server === connectedRemote) {
                                phoneHandoffMagnet = PublicTrackerHandoff.withTrackers(prepared, trackers)
                                localFromCombined = false
                                section = Section.LOCAL
                            }
                        }
                    },
                    onCopyToPhone = { magnet ->
                        phoneHandoffMagnet = magnet
                        localFromCombined = false
                        section = Section.LOCAL
                    },
                    selectedHash = expandedHash, files = files, filesError = filesError,
                    filesLoading = filesLoading,
                    onInspect = { transfer ->
                        if (expandedHash == transfer.hash) {
                            inspectionFence.invalidate()
                            expandedHash = null
                            filesLoading = false
                        } else {
                            val remote = server
                            val targetHash = transfer.hash
                            val ticket = inspectionFence.begin(currentView)
                            expandedHash = targetHash
                            files = null
                            filesError = null
                            filesLoading = true
                            scope.launch {
                                try {
                                    val loaded = remote?.listFiles(targetHash)
                                    if (remote != null && server === remote &&
                                        inspectionFence.current(ticket) &&
                                        currentView == ticket.view && expandedHash == targetHash) {
                                        files = loaded
                                    }
                                } catch (failure: Exception) {
                                    if (remote != null && server === remote &&
                                        inspectionFence.current(ticket) &&
                                        currentView == ticket.view && expandedHash == targetHash) {
                                        filesError = readableFailure(failure)
                                    }
                                } finally {
                                    if (remote != null && server === remote &&
                                        inspectionFence.current(ticket) &&
                                        currentView == ticket.view && expandedHash == targetHash) {
                                        filesLoading = false
                                    }
                                }
                            }
                        }
                    }
                )
                }
                Section.SNAPSHOT -> SnapshotInventoryExportScreen(
                    transfers = transfers, view = currentView,
                    snapshotRevision = snapshotRevision,
                    stale = stale, loading = loading,
                    onBack = { section = Section.TRANSFERS },
                    restorePanel = {
                        // Only the complete "main" view is a whole-server
                        // inventory; filtered views cannot prove absence.
                        SnapshotRestorePreviewPanel(
                            targetHashes = transfers.map { it.hash },
                            target = SnapshotRestoreTarget.SERVER,
                            inventoryConfirmed = currentView == "main" && snapshotRevision > 0,
                            inventoryStale = stale,
                            busyEngine = loading,
                            mutationGeneration = snapshotRevision,
                            onPrepareAdd = { document ->
                                remoteAddSessions.clearPendingSourceSelection()
                                serverHandoffTorrent = document
                                section = Section.ADD
                            },
                            onPrepareMagnet = { link ->
                                prepareRemoteMagnet(link)
                                section = Section.ADD
                            }
                        )
                    }
                )
                Section.SNAPSHOT_INSPECT -> LocalTransferPrivacyGate(
                    scope = TransferCollectionStore.LOCAL_ENGINE_SCOPE,
                    onBack = { section = Section.TRANSFERS }
                ) {
                    SnapshotDocumentInspectScreen(
                        onBack = { section = Section.TRANSFERS })
                }
                Section.ARCHIVES -> LocalTransferPrivacyGate(
                    scope = TransferCollectionStore.LOCAL_ENGINE_SCOPE,
                    onBack = { leaveTool() }
                ) {
                    LocalZipReviewScreen(onBack = { leaveTool() })
                }
                Section.LOCAL -> Unit // Composed by the independent local gate above.
                Section.SEEDING -> Unit // Composed by the top-level route above.
                Section.MEDIA -> Unit // Composed by the top-level route above.
                Section.COMBINED -> Unit // Composed by the top-level route above.
                Section.CREATE -> io.github.gamesbyian.chunks.create.CreateTorrentScreen(
                    onBack = { leaveTool() },
                    shared = createRequest, onSharedConsumed = { createRequest = null })
                Section.PLACES -> PlacesScreen(transfers) { path ->
                    remoteAddSessions.forAccount(aliasScope)
                        .takeUnless { it.alreadyAttempted }?.destination = path
                    section = Section.ADD
                }
                Section.NAMES -> AliasBatchReviewScreen(
                    transfers = transfers, aliasStore = aliasStore,
                    aliasScope = aliasScope, aliasRevision = aliasRevision,
                    onAliasesChanged = { aliasRevision++ },
                    onBack = { section = Section.TRANSFERS }
                )
                Section.ORGANIZE -> OrganizeScreen(transfers, aliasScope, aliasStore, aliasRevision)
                Section.SERVER -> ServerScreen(
                    version = version, displayedView = currentView,
                    requestedView = requestedView, onRequestedView = { requestedView = it },
                    loading = loading, stale = stale, count = transfers.size,
                    credentialNotice = credentialNotice,
                    savedLoginPresent = savedLoginPresent,
                    onForgetLogin = {
                        // The connected screen shares the same credential epoch
                        // and ordered mutation queue as the Connect screen.
                        // Revoke in-flight saves at TAP time, before IO can wait
                        // or a quick Disconnect + new Connect can interleave.
                        RemoteCredentialLock.invalidate()
                        rememberLogin = false
                        // Forget removes any lingering copy of a verified
                        // password even when a previous UI refresh is pending.
                        password = ""
                        credentialChanges.submit(scope) { revision ->
                            val cleared = withContext(Dispatchers.IO) {
                                try {
                                    val disabled = vault.setRememberingEnabled(false)
                                    val rosterErased = profileRoster.clearAll()
                                    disabled && rosterErased
                                } catch (_: Exception) { false }
                            }
                            // Older queued Forget jobs still erase as requested,
                            // but only the latest user command controls the UI.
                            if (credentialChanges.isLatest(revision)) {
                                val refreshed = withContext(Dispatchers.IO) { profileRoster.list() }
                                // A newer tap may occur while this IO read is suspended.
                                if (!credentialChanges.isLatest(revision)) return@submit
                                savedProfiles = refreshed
                                savedLoginPresent = vault.hasSavedLogin() || refreshed.isNotEmpty()
                                credentialNotice = if (cleared)
                                    "Saved login erased. Current connection remains active."
                                else "Could not erase saved login."
                            }
                        }
                    },
                    onRefresh = refresh,
                    onSeedingRules = { openTool(Section.SEEDING, Section.SERVER) },
                    environmentLabel = serverEnvironmentLabel(aliasScope),
                    onDisconnect = { disconnectCurrentRemote() }
                )
                Section.ADD -> {
                    val addSession = remember(aliasScope) {
                        remoteAddSessions.forAccount(aliasScope)
                    }
                    val recentPlaces = remember(aliasScope, recentPlaceRevision) {
                        if (aliasScope.isBlank()) emptyList()
                        else recentPlaceStore.places(aliasScope)
                    }
                    // Only this freshly authenticated server's inventory may
                    // suggest folders. A stale/other-account snapshot must not
                    // become an Add destination shortcut.
                    val observedPlaces = remember(aliasScope, transfers, stale) {
                        if (aliasScope.isBlank() || stale) emptyList()
                        else RemoteObservedAddPlaces.from(transfers)
                    }
                    VerifiedAddScreen(
                        remote = connectedRemote, magnet = magnet, destination = addSession.destination,
                        session = addSession,
                        observedPlaces = observedPlaces,
                        initialTorrent = serverHandoffTorrent,
                        onInitialTorrentConsumed = { serverHandoffTorrent = null },
                        finishInOrderScope = aliasScope.takeIf { it.isNotBlank() },
                        environmentLabel = serverEnvironmentLabel(aliasScope),
                        onMagnet = { magnet = it }, onDestination = { addSession.destination = it },
                        addStartState = phoneAddStart,
                        addArtworkState = addArtworkChoices.onServer(aliasScope),
                        onBack = { section = Section.TRANSFERS },
                        onComplete = refresh,
                        onVerifiedAdd = { hash ->
                            if (server === connectedRemote) {
                                remoteAdded = RemoteAddedRecord(
                                    aliasScope, hash, RemoteAddedRecord.viewKey(query, filter.name))
                                latestAdded = remoteAdded?.combinedKey(aliasScope)
                            }
                        },
                        onViewVerifiedTransfer = { hash ->
                            // The Add result came from this exact authenticated
                            // server; switching accounts must never redirect it.
                            if (server === connectedRemote) {
                                setRemoteQuery(hash)
                                filter = TransferFilter.ALL
                                remoteFilterStore.save(sortEnvironment, TransferFilter.ALL)
                                requestedView = "main"
                                section = Section.TRANSFERS
                                refresh()
                            }
                        },
                        onMutationStarted = beginRemoteMutation,
                        onMutationSettled = settledRemoteMutation,
                        recentPlaces = recentPlaces,
                        onCustomPlaceVerified = { path ->
                            val saved = runCatching {
                                recentPlaceStore.recordVerifiedCustom(aliasScope, path)
                            }.getOrDefault(false)
                            if (saved) recentPlaceRevision++
                            saved
                        }
                    )
                }
                // These destinations render above, before remote-only routing.
                else -> Unit
            }
                } // RemoteTransferPrivacyGate: all remote tabs/actions protected.
            } // Connected local/remote route split.
        }
        } // scrollable/independent destination viewport
        AuthenticatedTransfersFooter(
            optionsOpen = null,
            onTransfers = {
                // One canonical catalog in phone-only, remote-only and mixed
                // modes. Its chips change view scopes only; this route grants
                // no engine, server or per-job mutation authority.
                section = Section.COMBINED
                combinedFromConnections = false
                returnToCatalogRevision++
                // Re-tapping Transfers is useful even when already on this
                // list: return to its first row without resetting search,
                // filter, exact environment selection or any Add draft.
                scope.launch { combinedListState.scrollToItem(0) }
            },
            onAdd = {
                // The footer ALWAYS enters the shared Add destination, even
                // while an external magnet/.torrent is pending and even when
                // there is no remote profile. Do not consume or auto-submit
                // its frozen source/Place/Start draft by changing screens.
                // The Add implementer replaces this interim gateway with the
                // one source-first Universal Add form and sole coordinator.
                section = Section.ADD_CHOOSE
            },
            onOptions = { section = Section.OPTIONS },
            onConnections = { section = Section.SERVER },
            addDescription = "Choose where to add a transfer",
            activeDestination = footerDestinationForVisibleScreen(section.name, server != null, toolOrigin)
        )
    }
}

internal fun readableFailure(failure: Exception): String = when (failure) {
    is RemoteSmartConnect.ConnectionFailed -> failure.message.orEmpty()
    // A real HTTP status from the transport says more than "network error".
    is io.github.gamesbyian.chunks.rpc.RpcHttpStatusException -> httpStatusExplanation(failure.status)
    is java.net.UnknownHostException -> "The server hostname could not be resolved."
    is java.net.SocketTimeoutException -> "The server did not respond before the timeout. If a command was submitted, its outcome must be checked before retrying."
    is java.net.ConnectException -> "The server did not accept the connection."
    is java.io.IOException -> "Network or proxy error. Check your connection and server settings."
    else -> "The server returned an incompatible or unsupported RPC response. Check its configuration before retrying an operation."
}

/** Plain-language meaning of an observed gateway HTTP status; never a retry instruction for writes. */
internal fun httpStatusExplanation(status: Int): String = when (status) {
    401, 403 -> "The server refused this login (HTTP $status). Its password or access may have " +
        "changed. Open Server, Disconnect, then sign in again; your torrents are unchanged."
    407 -> "A proxy between this phone and the server requires its own login (HTTP 407)."
    429 -> "The server is limiting requests (HTTP 429). Wait a moment, then refresh."
    in 500..599 -> "The server's web gateway returned HTTP $status. It may be restarting or " +
        "overloaded; refresh again shortly."
    else -> "The server returned HTTP $status for this list request."
}

/** True for an observed login refusal, where the repair is signing in again. */
internal fun isLoginRefused(failure: Exception): Boolean =
    failure is io.github.gamesbyian.chunks.rpc.RpcHttpStatusException &&
        (failure.status == 401 || failure.status == 403)

@Composable
internal fun ConnectionScreen(
    endpoint: String, username: String, password: String, error: String?, loading: Boolean,
    rpcPath: String = "", onRpcPath: (String) -> Unit = {},
    diagnostics: ConnectionDiagnostics = ConnectionDiagnostics(),
    onCancelConnect: () -> Unit = {},
    startupReconnectEligible: Boolean = false,
    onStartupReconnectConsumed: () -> Unit = {},
    rememberLogin: Boolean, savedLoginPresent: Boolean,
    savedProfiles: List<RememberedRemoteProfileRoster.Profile>, credentialNotice: String?,
    onRememberLogin: (Boolean) -> Unit, onForgetLogin: () -> Unit,
    onSelectProfile: (RememberedRemoteProfileRoster.Profile) -> Unit,
    /** Optional canonical browser deep link; login selection remains explicit. */
    onOpenProfileTransfers: ((RememberedRemoteProfileRoster.Profile) -> Unit)? = null,
    onRenameProfile: suspend (RememberedRemoteProfileRoster.Profile, String) -> Boolean,
    onForgetProfile: (RememberedRemoteProfileRoster.Profile) -> Unit,
    onLocalZipReview: () -> Unit,
    onInspectSnapshot: () -> Unit,
    onLocalEngine: () -> Unit,
    onCreateTorrent: () -> Unit = {},
    onEndpoint: (String) -> Unit, onUsername: (String) -> Unit,
    onPassword: (String) -> Unit, onConnect: () -> Unit,
    connectedSessionName: String? = null,
    connectedVersion: String = "",
    connectedRequestedView: String = "main",
    onSelectConnectedView: (String) -> Unit = {},
    onLoadConnectedView: () -> Unit = {},
    onConnectedSeedingRules: () -> Unit = {},
    onViewConnectedTransfers: () -> Unit = {},
    onDisconnectCurrent: () -> Unit = {}
) {
    // Submitting the form closes the keyboard, as forms conventionally do,
    // so the next screen's persistent footer is not waiting on an IME hide.
    val connectKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val connectFocus = androidx.compose.ui.platform.LocalFocusManager.current
    val submitConnect: () -> Unit = {
        connectKeyboard?.hide()
        connectFocus.clearFocus()
        if (connectedSessionName == null) onConnect()
    }
    var pendingForget by remember { mutableStateOf<String?>(null) }
    var extraToolsOpen by rememberSaveable { mutableStateOf(false) }
    var currentServerToolsOpen by rememberSaveable { mutableStateOf(false) }
    var renameProfileId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var renameBusy by remember { mutableStateOf(false) }
    var renameNotice by remember { mutableStateOf<String?>(null) }
    val renameScope = rememberCoroutineScope()
    // Reuse the exact existing verified Connect operation. No saved password or
    // endpoint is broadcast to an unverified profile, and no torrent mutation
    // occurs merely because the app opened. Consume before invoking the action
    // to avoid an automatic loop if authentication/network validation fails.
    StartupRemoteReconnectEffect(
        eligible = startupReconnectEligible,
        onConsumed = onStartupReconnectConsumed,
        onConnect = onConnect
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            // Connections manages rTorrent clients. Local transfers live in
            // the canonical Transfers browser, even before remote setup.
            ColoredPanel("Connect to another computer or server (optional)",
                "Manage a seedbox or other rTorrent client from a secure endpoint. " +
                    "Checking a connection never changes its transfers; each transfer action " +
                    "requires separate review.", Lilac, titleAsHeading = true)
        }
        if (connectedSessionName != null) item {
            Card(colors = CardDefaults.cardColors(containerColor = ChunksColors.SurfaceRaised)) {
                Column(Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Connected · " + connectedSessionName,
                        style = MaterialTheme.typography.titleMedium)
                    Text("This verified session is available in Transfers. Browsing does not change its torrents.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onViewConnectedTransfers,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .semantics { contentDescription = "View transfers on current verified server" }) {
                            Text("View transfers")
                        }
                        OutlinedButton(onClick = onDisconnectCurrent, enabled = !loading,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("Disconnect")
                        }
                    }
                    OutlinedButton(onClick = { currentServerToolsOpen = !currentServerToolsOpen },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .semantics {
                                stateDescription = if (currentServerToolsOpen) "Expanded" else "Collapsed"
                            }) {
                        Text(if (currentServerToolsOpen) "Hide current server tools" else "Current server tools")
                    }
                    if (currentServerToolsOpen) {
                        Text("rTorrent " + connectedVersion + " · selected view " + connectedRequestedView,
                            style = MaterialTheme.typography.bodySmall)
                        Text("A filtered rTorrent view is not proof of a complete account inventory.",
                            style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("main", "default", "started", "stopped").forEach { view ->
                                FilterChip(selected = connectedRequestedView == view,
                                    onClick = { onSelectConnectedView(view) }, label = { Text(view) },
                                    modifier = Modifier.heightIn(min = 48.dp))
                            }
                        }
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onLoadConnectedView, enabled = !loading,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text(if (loading) "Refreshing…" else "Load view")
                            }
                            OutlinedButton(onClick = onConnectedSeedingRules,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text("Seeding rules")
                            }
                        }
                    }
                }
            }
        }
        if (rememberLogin && savedProfiles.isNotEmpty()) {
            item {
                Text("Saved servers", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text(
                    if (onOpenProfileTransfers != null)
                        "View transfers opens this exact saved client under its existing privacy and read-only access rules. Use saved login to prepare an explicit Connect."
                    else
                        "Choose an account, then Connect. Selection alone does not contact the server.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(savedProfiles, key = { "saved-profile-" + it.id }) { profile ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (onOpenProfileTransfers != null) {
                        Text(profile.label, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium)
                        // Duplicate friendly labels cannot merge or disguise accounts.
                        Text(profile.address, style = MaterialTheme.typography.bodySmall)
                        Text(profile.lastVerifiedAt?.let {
                            "Last connected " + android.text.format.DateUtils.getRelativeTimeSpanString(
                                it, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
                        } ?: "Last connection time not recorded", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                pendingForget = null
                                onOpenProfileTransfers(profile)
                            }, enabled = !loading,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .semantics {
                                        contentDescription =
                                            "View transfers for saved " + profile.label + " on " + profile.address
                                    }) {
                                Text("View transfers")
                            }
                            OutlinedButton(onClick = {
                                pendingForget = null
                                onSelectProfile(profile)
                            }, enabled = !loading && connectedSessionName == null,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .semantics {
                                        contentDescription =
                                            "Use saved login for " + profile.label + " on " + profile.address
                                    }) {
                                Text("Use saved login")
                            }
                        }
                    } else {
                        // Legacy standalone callers retain an explicit login
                        // selection; they never silently acquire read authority.
                        OutlinedButton(onClick = {
                            pendingForget = null
                            onSelectProfile(profile)
                        }, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("Use " + profile.label)
                                Text(profile.address, style = MaterialTheme.typography.bodySmall)
                                Text(profile.lastVerifiedAt?.let {
                                    "Last connected " + android.text.format.DateUtils.getRelativeTimeSpanString(
                                        it, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
                                } ?: "Last connection time not recorded", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (pendingForget == profile.id) {
                        Text("Forget saved credentials for this account? Torrents are unchanged.",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                pendingForget = null
                                onForgetProfile(profile)
                            }, enabled = !loading, modifier = Modifier.weight(1f)
                                .heightIn(min = 48.dp)) { Text("Forget profile") }
                            OutlinedButton(onClick = { pendingForget = null },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text("Cancel")
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                renameProfileId = profile.id
                                renameDraft = profile.label
                                renameNotice = null
                                pendingForget = null
                            }, enabled = !loading && !renameBusy,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .semantics {
                                        contentDescription =
                                            "Rename saved " + profile.label + " on " + profile.address
                                    }) {
                                Text("Rename")
                            }
                            OutlinedButton(onClick = {
                                pendingForget = profile.id
                                renameProfileId = null
                            }, enabled = !loading && !renameBusy,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                    .semantics {
                                        contentDescription =
                                            "Forget saved " + profile.label + " on " + profile.address
                                    }) {
                                Text("Forget " + profile.label)
                            }
                        }
                    }
                    if (renameProfileId == profile.id) {
                        OutlinedTextField(
                            value = renameDraft, onValueChange = { renameDraft = it },
                            label = { Text("Friendly name for this server") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        Text("Only the name shown in Chunks changes. The server, login, torrents and files stay unchanged.",
                            style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                if (!renameBusy) {
                                    renameBusy = true
                                    val targetName = renameDraft
                                    renameScope.launch {
                                        val ok = try { onRenameProfile(profile, targetName) }
                                            catch (_: Exception) { false }
                                        renameNotice = if (ok) "Friendly name saved." else
                                            "Could not save this name. Existing profile preserved."
                                        if (ok) renameProfileId = null
                                        renameBusy = false
                                    }
                                }
                            }, enabled = !loading && !renameBusy &&
                                RememberedRemoteProfileRoster.validFriendlyName(renameDraft),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text(if (renameBusy) "Saving…" else "Save name")
                            }
                            OutlinedButton(onClick = { renameProfileId = null },
                                enabled = !renameBusy,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
        }
        if (renameNotice != null) item {
            Text(renameNotice.orEmpty(), style = MaterialTheme.typography.bodySmall)
        }
        if (connectedSessionName == null) {
        item {
            OutlinedTextField(
                value = endpoint, onValueChange = onEndpoint,
                label = { Text("Seedbox hostname, ruTorrent URL or RPC address") },
                placeholder = { Text("server.example or https://server.example/rutorrent/") },
                // Next moves to the following field; no autocorrect on an address.
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            val providerHint = remember(endpoint) { RemoteProviderCatalog.hint(endpoint) }
            if (providerHint != null) Text(providerHint, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
        item {
            var advancedOpen by rememberSaveable { mutableStateOf(rpcPath.isNotBlank()) }
            TextButton(onClick = { advancedOpen = !advancedOpen },
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (advancedOpen) "Hide advanced connection settings" else "Advanced connection settings")
            }
            if (advancedOpen) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = rpcPath, onValueChange = onRpcPath,
                    label = { Text("Exact RPC path (optional)") },
                    placeholder = { Text("/RPC2") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                    singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth()
                )
                Text("For a server your provider documents with a specific XML-RPC path. Enter only the " +
                    "hostname above; Chunks then tests just this one address, read-only, instead of " +
                    "checking known paths.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            OutlinedTextField(
                value = username, onValueChange = onUsername,
                label = { Text("Username, if required") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            SecurePasswordInput(
                value = password, onValueChange = onPassword,
                modifier = Modifier.fillMaxWidth(),
                // Go runs the same read-only Connect as the button, under its rules.
                onGo = { if (!loading && endpoint.isNotBlank()) submitConnect() }
            )
        }
        } // No mutable login inputs while an authenticated session is active.
        item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = rememberLogin, enabled = !loading, role = Role.Checkbox,
                    onValueChange = onRememberLogin
                ), verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = rememberLogin, onCheckedChange = null)
                Text("Remember this server and login on this device",
                    modifier = Modifier.padding(start = 8.dp))
            }
            Text("On by default. Password encrypted with Android Keystore. " +
                "Uncheck to use this connection without saving credentials.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (savedLoginPresent) item {
            OutlinedButton(onClick = onForgetLogin, enabled = !loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Forget all saved logins")
            }
        }
        if (credentialNotice != null) item {
            Text(credentialNotice, style = MaterialTheme.typography.bodySmall)
        }
        if (connectedSessionName == null) item {
            Button(
                onClick = submitConnect,
                enabled = !loading && endpoint.isNotBlank(),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            ) { Text(if (loading) "Finding secure rTorrent…" else "Connect to rTorrent") }
        }
        if (connectedSessionName == null) {
            item { ConnectionDiagnosticsPanel(diagnostics, loading, onCancelConnect) }
            if (error != null) item { Notice("Connection unsuccessful", error) }
        }
        item {
            Text("Protected HTTPS is required for remote servers. Login is encrypted and stored only on this device unless you opt out. No torrents or files are changed by connecting.",
                style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary)
        }
        item {
            OutlinedButton(
                onClick = { extraToolsOpen = !extraToolsOpen },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics {
                        stateDescription = if (extraToolsOpen) "Expanded" else "Collapsed"
                    }
            ) { Text(if (extraToolsOpen) "Hide offline tools" else "More offline tools") }
            if (extraToolsOpen) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTile("Inspect a local ZIP without connecting", ChunksColors.Yellow,
                    onClick = onLocalZipReview, modifier = Modifier.fillMaxWidth(),
                    icon = R.drawable.chunks_ic_archive, stacked = false)
                ActionTile("Inspect .chunksnap backup (offline)", ChunksColors.Pink,
                    onClick = onInspectSnapshot, modifier = Modifier.fillMaxWidth(),
                    icon = R.drawable.chunks_ic_document, stacked = false)
                ActionTile("Create a torrent from phone files", ChunksColors.Green,
                    onClick = onCreateTorrent, modifier = Modifier.fillMaxWidth(),
                    icon = R.drawable.chunks_ic_add_torrent, stacked = false)
            }
        }
    }
}

@Composable
private fun TransfersScreen(
    listState: androidx.compose.foundation.lazy.LazyListState,
    remote: RTorrentRemote, onLifecycleSettled: () -> Unit,
    onMutationStarted: () -> Boolean,
    aliasStore: TransferAliasStore, aliasScope: String,
    aliasSessions: MutableMap<String, TransferAliasEditSession>,
    aliasRevision: Int, onAliasSaved: () -> Unit,
    list: List<RemoteTransfer>, view: String, loading: Boolean, stale: Boolean,
    environmentLabel: String,
    snapshotRevision: Long,
    progressObservedAt: Long?,
    query: String, onQuery: (String) -> Unit, filter: TransferFilter,
    onCombined: () -> Unit,
    onFilter: (TransferFilter) -> Unit, onClearView: () -> Unit, order: TransferOrder,
    direction: UnifiedSortDirection,
    onSort: (TransferOrder, UnifiedSortDirection) -> Unit, onRefresh: () -> Unit, onAdd: () -> Unit,
    onConnections: () -> Unit,
    showNavigationFooter: Boolean = true,
    optionsOpen: Boolean, onOptions: () -> Unit,
    onReviewNames: () -> Unit, onExportSnapshot: () -> Unit,
    onInspectSnapshot: () -> Unit,
    onPreparePhone: (RemoteTransfer) -> Unit,
    onCopyToPhone: (String) -> Unit,
    selectedHash: String?, files: List<RemoteFile>?, filesError: String?,
    filesLoading: Boolean, onInspect: (RemoteTransfer) -> Unit,
    /** Latest verified Add on this exact account; explains a result the view hides. */
    addedRecord: RemoteAddedRecord? = null
) {
    val knownDownloadingHashes = remember(list) {
        RemoteQueueActivityContext.hashesReportingDownloadTraffic(list)
    }
    // Confirmed media titles are searchable too ("Babylon 5" finds B5.S02E07…), per exact account.
    val mediaTitles by io.github.gamesbyian.chunks.media.rememberMediaSearchTitles()
    val serverEnvironment = remember(aliasScope) {
        io.github.gamesbyian.chunks.model.EnvironmentSnapshots.serverIdOfAliasScope(aliasScope)?.value
    }
    val visible = remember(list, query, filter, order, direction, aliasRevision, aliasScope, mediaTitles) {
        TransferCatalog.visible(list, query, filter, order, direction,
            displayName = { transfer -> aliasStore.get(aliasScope, transfer.hash) ?: transfer.name },
            mediaTitle = { transfer -> remoteTransferKey(serverEnvironment, transfer.hash)
                ?.let { mediaTitles[io.github.gamesbyian.chunks.media.MediaTransferKey.of(it)] } })
    }
    val addedHidden = remember(addedRecord, aliasScope, query, filter, list, visible) {
        addedRecord?.hiddenIn(aliasScope, RemoteAddedRecord.viewKey(query, filter.name),
            list.map { it.hash }, visible.map { it.hash }) == true
    }
    val topControlScope = rememberCoroutineScope()
    val context = LocalContext.current
    var serverMoveRevision by remember { mutableStateOf(0) }
    var serverMoveMessage by remember { mutableStateOf<String?>(null) }
    val membershipStore = remember(context) { TransferCollectionStore(context) }
    // Selection is scoped to authenticated account + rTorrent view. A newly
    // accepted queue revision clears it: never apply an old batch to new rows.
    var selecting by remember(aliasScope, view) { mutableStateOf(false) }
    var toolsOpen by remember(aliasScope) { mutableStateOf(false) }
    // The app-wide Options tab no longer toggles the old remote-only panel.
    // Keep the existing authenticated batch tools reachable through an
    // explicit local disclosure without creating another mutation executor.
    var advancedToolsOpen by rememberSaveable(aliasScope, view) { mutableStateOf(false) }
    // Exact server/view scoped presentation state only; no operation lease is stored here.
    var listControlsOpen by rememberSaveable(aliasScope, view) { mutableStateOf(false) }
    var batchHashes by remember(aliasScope, view) { mutableStateOf<Set<String>>(emptySet()) }
    var batchName by remember(aliasScope, view) { mutableStateOf("") }
    var batchConfirm by remember(aliasScope, view) { mutableStateOf(false) }
    var batchBusy by remember(aliasScope, view) { mutableStateOf(false) }
    var batchMessage by remember(aliasScope, view) { mutableStateOf<String?>(null) }
    LaunchedEffect(snapshotRevision, aliasScope, view) {
        selecting = false
        batchHashes = emptySet()
        batchConfirm = false
    }
    val batchScope = rememberCoroutineScope()
    val showBackToTop by remember(listState, visible) {
        derivedStateOf {
            ReturnToTopPolicy.remote(
                firstVisibleIndex = listState.firstVisibleItemIndex,
                firstVisibleOffsetPx = listState.firstVisibleItemScrollOffset,
                viewportHeightPx = listState.layoutInfo.viewportSize.height,
                hasTransfers = visible.isNotEmpty()
            )
        }
    }
    // A measured Column gives the persistent footer its own layout space.
    // A Box overlay lets LazyColumn scroll buttons behind the footer, where
    // real taps hit + Add instead of a transfer's "More details". Avoid a
    // hard-coded footer-height guess: 200% font and accessibility text may
    // increase its actual height.
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Keep the final transfer reachable above the floating return
            // control, without reserving nonexistent footer space in the list.
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactRemoteInventoryHeader(
                    environmentLabel = environmentLabel,
                    environment = io.github.gamesbyian.chunks.model.EnvironmentSnapshots.serverIdOfAliasScope(aliasScope),
                    loadedCount = list.size,
                    view = view,
                    stale = stale,
                    loading = loading,
                    onAllEnvironments = onCombined,
                    observedAtEpochMillis = progressObservedAt
                )
                RemoteTransfersPrimaryActions(
                    loading = loading, stale = stale,
                    onAdd = onAdd, onRefresh = onRefresh
                )
                // Advanced controls are reachable from the persistent footer's
                // Options destination, instead of occupying a permanent row
                // above the actual transfer inventory. Keep Exit visible while
                // selection is active, even if the server becomes stale.
                OutlinedButton(onClick = { advancedToolsOpen = !advancedToolsOpen },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (advancedToolsOpen) "Hide batch & tools" else "Batch & tools")
                }
                RemoteAdvancedListActions(
                    optionsOpen = optionsOpen || advancedToolsOpen,
                    selecting = selecting,
                    toolsOpen = toolsOpen,
                    enabled = if (selecting) !batchBusy
                        else !loading && !stale && !batchBusy && list.isNotEmpty(),
                    onToggleSelection = {
                        val enteringSelection = !selecting
                        selecting = enteringSelection
                        batchHashes = emptySet()
                        batchConfirm = false
                        batchMessage = null
                        // Reveal the actual rows to select immediately rather than
                        // leaving the large Options navigation above the batch.
                        // The persistent footer still reopens Options at any time.
                        if (enteringSelection) {
                            advancedToolsOpen = false
                            if (optionsOpen) onOptions()
                        }
                    },
                    onToggleTools = { toolsOpen = !toolsOpen }
                )
                if ((optionsOpen || advancedToolsOpen) && toolsOpen) {
                    OutlinedButton(onClick = onReviewNames,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text("Review suggested queue names")
                    }
                    OutlinedButton(onClick = onExportSnapshot, enabled = !loading && !stale,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text("Save inventory-only snapshot")
                    }
                    RemoteTransferHealthExportPanel(
                        transfers = list, serverView = view, stale = stale || loading
                    )
                    BatchMagnetExportPanel(
                        rows = visible.map { io.github.gamesbyian.chunks.model.BatchMagnetHandoff.Row(
                            it.hash, it.name) },
                        stale = stale || loading,
                        sourceLabel = "remote"
                    )
                    OutlinedButton(onClick = onInspectSnapshot,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text("Inspect saved .chunksnap file")
                    }
                }
            }
        }
        if ((optionsOpen || advancedToolsOpen) && toolsOpen) item {
            val down = remember(list) { RemoteRateEvidence.download(list) }
            val up = remember(list) { RemoteRateEvidence.upload(list) }
            RemoteRateStatTiles(down, up, list.count { it.started })
            down.caveat("Download")?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            up.caveat("Upload")?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
        if ((optionsOpen || advancedToolsOpen) && toolsOpen) item {
            val transferring = list.count {
                (it.downBytesPerSecond ?: 0L) > 0L ||
                    (it.upBytesPerSecond ?: 0L) > 0L
            }
            val zeroDownloading = list.count {
                it.started && it.sizeBytes > it.completedBytes &&
                    it.downBytesPerSecond == 0L
            }
            val unknown = list.count {
                it.started && it.sizeBytes > it.completedBytes &&
                    it.downBytesPerSecond == null
            }
            Text("$transferring reporting traffic · $zeroDownloading started but 0 B/s down" +
                (if (unknown > 0) " · $unknown without a reported download rate" else ""),
                style = MaterialTheme.typography.bodySmall)
            Text("Start set is a server flag, not proof the engine is active or peers are " +
                "delivering data. Rates are a single loaded-view observation.",
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            ClearableTransferSearchField(
                value = query, onValueChange = onQuery,
                label = "Search names, aliases, folders or hashes"
            )
        }
        item {
            OutlinedButton(
                onClick = { listControlsOpen = !listControlsOpen },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics {
                        stateDescription = if (listControlsOpen) "Expanded" else "Collapsed"
                    }
            ) {
                Text((if (listControlsOpen) "Hide" else "Choose") + " filters & sort · " +
                    remoteShownCount(visible.size, list.size) + " · Sort: " + order.label + " · " +
                    order.directionLabel(direction) + " · " + filter.label)
            }
        }
        if (listControlsOpen) item {
            RemoteTransferFilterChoices(selected = filter, onChoose = onFilter)
        }
        if (listControlsOpen) item {
            RemoteTransfersSortSummary(visible.size, order.label, onCycleSort = {
                val choices = TransferOrder.entries
                val next = choices[(choices.indexOf(order) + 1) % choices.size]
                onSort(next, next.naturalDirection)
            })
        }
        if (listControlsOpen) item {
            TransferSortDirectionButton(
                sortName = order.label,
                directionLabel = order.directionLabel(direction),
                onReverse = { onSort(order, TransferSortOrdering.reversed(direction)) }
            )
        }
        if (selecting) item {
            AccentOutlineCard(ChunksColors.Purple) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Batch collections", fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge)
                    Text("${batchHashes.size} selected from the loaded '$view' view. " +
                        "Select shown means only the ${visible.size} filtered matches " +
                        "currently loaded by Chunks, not hidden views or unloaded transfers.")
                    // Wraps instead of splitting "Select shown" letter by letter at 320dp/200%.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = {
                            val next = batchHashes + visible.map { it.hash }
                            if (next.size > 512) {
                                batchMessage = "One batch supports at most 512 transfers. Narrow your filter."
                            } else {
                                batchHashes = next
                                batchConfirm = false
                                batchMessage = null
                            }
                        }, enabled = !stale && !loading && !batchBusy && visible.isNotEmpty(),
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Select shown")
                        }
                        OutlinedButton(onClick = {
                            batchHashes = emptySet()
                            batchConfirm = false
                        }, enabled = !batchBusy && batchHashes.isNotEmpty(),
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Clear")
                        }
                    }
                    OutlinedTextField(
                        value = batchName,
                        onValueChange = { batchName = it; batchConfirm = false },
                        label = { Text("Collection name for selected transfers") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (batchConfirm) {
                        Text("Confirm adding ${batchHashes.size} selected transfers to " +
                            "'${batchName.trim()}' on this device. Already-present memberships " +
                            "stay intact. No file, torrent name, rTorrent state or location changes.")
                    }
                    Button(onClick = {
                        val cleaned = CollectionMembershipRules.cleanName(batchName)
                        val targets = batchHashes.toList()
                        if (cleaned == null || targets.isEmpty()) {
                            batchMessage = "Select transfers and enter a valid Collection name."
                        } else if (!batchConfirm) {
                            batchConfirm = true
                            batchMessage = null
                        } else {
                            batchBusy = true
                            batchMessage = null
                            batchScope.launch {
                                val accepted = withContext(Dispatchers.IO) {
                                    try {
                                        membershipStore.addBatch(aliasScope, targets, cleaned) &&
                                            targets.all { hash ->
                                                membershipStore.forTransfer(aliasScope, hash)
                                                    .any { it.equals(cleaned, ignoreCase = true) }
                                            }
                                    } catch (_: Exception) { false }
                                }
                                batchMessage = if (accepted)
                                    "Saved ${targets.size} reviewed memberships on this phone. " +
                                        "Seeding and server files are unchanged."
                                else "Batch save was not confirmed. Inspect current Collection " +
                                    "memberships before retrying; no server mutation was requested."
                                if (accepted) {
                                    batchHashes = emptySet()
                                    selecting = false
                                    batchName = ""
                                }
                                batchConfirm = false
                                batchBusy = false
                            }
                        }
                    }, enabled = !stale && !loading && !batchBusy &&
                        batchHashes.isNotEmpty() &&
                        CollectionMembershipRules.cleanName(batchName) != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(if (batchBusy) "Saving…" else if (batchConfirm)
                            "Confirm batch Collection assignment" else
                            "Review Collection assignment")
                    }
                    batchMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    RemoteBatchLifecyclePanel(
                        selected = list.filter { it.hash in batchHashes },
                        enabled = !stale && !loading && !batchBusy && batchHashes.isNotEmpty(),
                        onMutationStarted = onMutationStarted,
                        onSettled = onLifecycleSettled,
                        performLifecycle = remote::setStarted
                    )
                    RemoteBatchPhoneCopyPanel(
                        selected = list.filter { it.hash in batchHashes },
                        enabled = !stale && !loading && !batchBusy && batchHashes.isNotEmpty(),
                        run = { items -> runBatchPhoneCopy(context, remote, items) },
                        move = { items ->
                            runBatchPhoneMove(context, remote, aliasScope, items, list)
                                .also { serverMoveRevision++ }
                        }
                    )
                    val serverDigest = TransferMoveStore.scopeDigest(aliasScope)
                    val pendingServerMoves = remember(list, serverMoveRevision, serverDigest) {
                        TransferMoveStore(context).all(TransferMove.Direction.SERVER_TO_PHONE).filter { record ->
                            record.stage == TransferMove.Stage.TARGET_VERIFIED &&
                                record.serverScopeDigest == serverDigest &&
                                list.any { it.hash.equals(record.infoHash, ignoreCase = true) &&
                                    TransferMove.sourceIdentityMatches(
                                        record, it.name, null, it.directory) }
                        }
                    }
                    if (view == "main") BatchMoveRemovalPanel(
                        pending = pendingServerMoves, serverScopeDigest = serverDigest,
                        serverLabel = "this phone", sourceLabel = "server",
                        busy = stale || loading,
                        observeTarget = { hash -> TransferMoveObservations.phone(hash) },
                        removeSource = { hash ->
                            val original = pendingServerMoves.singleOrNull {
                                it.infoHash.equals(hash, ignoreCase = true)
                            } ?: error("Original server Move journal missing")
                            val live = list.singleOrNull {
                                it.hash.equals(hash, ignoreCase = true)
                            } ?: error("Original server job unavailable")
                            if (!TransferMove.sourceIdentityMatches(
                                    original, live.name, null, live.directory))
                                error("Original server job changed after Move review")
                            remote.eraseFromSession(
                                original.infoHash, original.name,
                                requireNotNull(original.sourceDirectory))
                        },
                        observeSource = { hash -> TransferMoveObservations.server(remote, hash) },
                        save = { record -> TransferMoveStore(context).save(record) },
                        forget = { record -> TransferMoveStore(context).remove(record) },
                        onFinished = { text -> serverMoveMessage = text; serverMoveRevision++ },
                        onStarted = onMutationStarted,
                        onSettled = onLifecycleSettled
                    )
                }
            }
        }
        // An uncertain source removal may leave zero server jobs. Recovery
        // must remain reachable without batch-selection mode or a source card.
        if (view == "main") item(key = "remote-orphan-move-recovery") {
            OrphanMoveRecoveryPanel(
                direction = TransferMove.Direction.SERVER_TO_PHONE,
                serverAliasScope = aliasScope,
                visibleSourceHashes = list.map { it.hash },
                enabled = !stale && !loading,
                revision = serverMoveRevision,
                observeSource = { hash -> TransferMoveObservations.server(remote, hash) },
                onChanged = {
                    serverMoveMessage = "Only the resolved local Move reminder was cleared. " +
                        "Torrent jobs and downloaded files were not changed."
                    serverMoveRevision++
                }
            )
        }
        // Journal recovery may close its panel as soon as it succeeds; keep
        // the outcome visible in ordinary Transfers, not only in Select mode.
        if (serverMoveMessage != null) item(key = "remote-move-outcome") {
            Text(serverMoveMessage.orEmpty(), style = MaterialTheme.typography.bodySmall)
        }
        if (addedHidden) item(key = "remote-added-hidden") {
            PhoneAddedHiddenNotice(onShowAll = onClearView,
                showAllLabel = "Show all transfers on $environmentLabel")
        }
        if (visible.isEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ColoredPanel("Nothing in this view.",
                    RemoteEmptyTransfers.explanation(list.size, loading, stale, view,
                        query.isNotBlank(), filter != TransferFilter.ALL), Lilac)
                if (RemoteEmptyTransfers.canClear(query, filter)) {
                    OutlinedButton(onClick = onClearView,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Clear search and filter")
                    }
                }
            }
        }
        items(visible, key = { it.hash }) { transfer ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selecting) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(
                            value = transfer.hash in batchHashes,
                            enabled = !stale && !loading && !batchBusy,
                            role = Role.Checkbox,
                            onValueChange = { checked ->
                                batchHashes = if (checked) {
                                    if (batchHashes.size >= 512) {
                                        batchMessage = "A batch is limited to 512 selected transfers."
                                        batchHashes
                                    } else batchHashes + transfer.hash
                                } else batchHashes - transfer.hash
                                batchConfirm = false
                            }
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = transfer.hash in batchHashes,
                        onCheckedChange = null)
                    Text("Select " + (aliasStore.get(aliasScope, transfer.hash) ?: transfer.name),
                        modifier = Modifier.weight(1f), maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            RemoteTransferCompositionBoundary(aliasScope, transfer.hash) {
            TransferCard(
                remote = remote, stale = stale || loading, mainView = view == "main",
                onMutationStarted = onMutationStarted,
                alias = aliasStore.get(aliasScope, transfer.hash),
                aliasSession = aliasSessions.getOrPut(aliasScope + "\u0000" + transfer.hash) {
                    TransferAliasEditSession(aliasStore.get(aliasScope, transfer.hash))
                },
                onAliasSave = { proposed ->
                    val saved = aliasStore.save(aliasScope, transfer.hash, proposed)
                    if (saved) onAliasSaved()
                    saved
                },
                onLifecycleSettled = onLifecycleSettled,
                progressEnvironmentId = io.github.gamesbyian.chunks.model.EnvironmentSnapshots
                    .serverIdOfAliasScope(aliasScope)?.value,
                progressObservedAt = progressObservedAt,
                transfer = transfer, expanded = selectedHash == transfer.hash,
                snapshotRevision = snapshotRevision,
                otherTransferDownloading = RemoteQueueActivityContext.otherTransferReportsDownloading(
                    transfer, knownDownloadingHashes, !stale && !loading
                ),
                files = files, filesLoading = filesLoading, filesError = filesError,
                onInspect = { onInspect(transfer) },
                onPreparePhone = { onPreparePhone(transfer) },
                onCopyToPhone = onCopyToPhone,
                removalEnvironmentLabel = RemoteVerifiedUiIdentity.removalScopeLabel(aliasScope),
                moveScope = aliasScope,
                finishInOrderScope = aliasScope,
                // Same-account relations; opening one filters this list to that exact transfer.
                similar = serverEnvironment?.let { envId -> {
                    val source = remember(list, stale, loading) {
                        remoteSimilarSource(io.github.gamesbyian.chunks.model.EnvironmentId(envId), list, stale || loading)
                    }
                    source.transfers.firstOrNull { it.key.infoHash == transfer.hash.lowercase() }?.let { self ->
                        SimilarTransfersHost(self, listOf(source),
                            environmentLabel = { environmentLabel },
                            environmentColor = { ChunksColors.Blue },
                            onOpen = { key -> onQuery(key.infoHash) })
                    }
                } }
            )
            }
            }
        }
        item { RemoteCapabilityPanel(remote) }
        item {
            Text("Server snapshot. Start/stop and main-view session removal each require exact-target confirmation and readback. Session removal does not request payload deletion.",
                style = MaterialTheme.typography.bodySmall)
        }
        }
        ReturnToTopControl(
            visible = showBackToTop,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 16.dp),
            onClick = {
                topControlScope.launch {
                    if (ReturnToTopPolicy.animationsEnabled(context)) listState.animateScrollToItem(0)
                    else listState.scrollToItem(0)
                }
            }
        )
        }
        // Naturally sized below the scroll viewport; cannot steal a tap
        // from a transfer card, irrespective of density or text scaling.
        if (showNavigationFooter) AuthenticatedTransfersFooter(
            optionsOpen = optionsOpen,
            onTransfers = { topControlScope.launch { listState.scrollToItem(0) } },
            onAdd = onAdd,
            onOptions = onOptions,
            onConnections = onConnections,
            // Speak the exact Add target; the visible "+ Add" stays compact.
            addDescription = "Add torrent on " + serverEnvironmentLabel(aliasScope)
        )
    }
}

@Composable
private fun TransferCard(
    remote: RTorrentRemote, stale: Boolean, mainView: Boolean,
    onMutationStarted: () -> Boolean,
    alias: String?, aliasSession: TransferAliasEditSession,
    onAliasSave: (String?) -> Boolean,
    onLifecycleSettled: () -> Unit,
    progressEnvironmentId: String?,
    progressObservedAt: Long?,
    transfer: RemoteTransfer, expanded: Boolean,
    snapshotRevision: Long,
    otherTransferDownloading: Boolean,
    files: List<RemoteFile>?, filesLoading: Boolean, filesError: String?,
    onInspect: () -> Unit,
    onPreparePhone: () -> Unit,
    onCopyToPhone: (String) -> Unit,
    removalEnvironmentLabel: String?,
    /** Authenticated endpoint + account scope, or null when ambiguous. */
    moveScope: String? = null,
    /** Authenticated endpoint + account scope for Finish in Order; null when ambiguous. */
    finishInOrderScope: String? = null,
    /** Similar Transfers in this card's details; null hides it. */
    similar: (@Composable () -> Unit)? = null
) {
    var confirmPhoneHandoff by remember(transfer.hash) { mutableStateOf(false) }
    // Exact profile/hash identity prevents a recycled row inheriting another
    // torrent's expansion. No network/file operation occurs on disclosure.
    var detailsOpen by rememberSaveable(moveScope, transfer.hash) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = ChunksColors.Surface),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, ChunksColors.Muted),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TransferRowArtwork(rememberRemoteTransferKey(progressEnvironmentId, transfer.hash), transfer.name)
                Text(alias ?: transfer.name, style = MaterialTheme.typography.titleMedium,
                    // Collapsed rows stay compact, but full names are available
                    // to TalkBack and when the user expands this exact card.
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = alias ?: transfer.name
                    },
                    maxLines = if (detailsOpen) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis)
            }
            if (detailsOpen) {
            if (alias != null) {
                Text("Original torrent: " + transfer.name,
                    style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary,
                    maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
            }
            TransferAliasEditor(
                originalName = transfer.name, hash = transfer.hash,
                alias = alias, onSave = onAliasSave, session = aliasSession
            )
            }
            if (transfer.metadataPending != true && transfer.sizeBytes > 0) ChunkedProgressBar(
                progress = transfer.fractionComplete,
                modifier = Modifier.fillMaxWidth(),
                historyKey = progressEnvironmentId?.let {
                    ProgressChunkStore.identity(it, transfer.hash)
                },
                observeFresh = !stale,
                observationEpochMillis = progressObservedAt,
                tone = when {
                    transfer.fractionComplete >= 1f -> ChunksTone.COMPLETE
                    !transfer.started -> ChunksTone.PAUSED
                    else -> ChunksTone.ACTIVE
                }
            )
            // Wraps the pill onto its own line when the text is large.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(if (transfer.metadataPending == true) "Payload size and progress pending metadata" else
                    (transfer.fractionComplete * 100).toInt().toString() + "% · " +
                    Units.bytes(transfer.completedBytes) + " of " + Units.bytes(transfer.sizeBytes),
                    style = MaterialTheme.typography.bodySmall)
                // Same words as the phone, from observed facts only: the started
                // flag alone never reads as "Downloading".
                val status = RemoteStatusLabel.of(transfer, stale)
                StatusPill(status.label, status.tone)
            }
            val conciseRates = buildList {
                transfer.downBytesPerSecond?.let { add("↓ " + Units.bytes(it) + "/s") }
                transfer.upBytesPerSecond?.let { add("↑ " + Units.bytes(it) + "/s") }
                transfer.ratioThousandths?.takeIf { it >= 0 }?.let { ratio ->
                    add("Ratio " + String.format(java.util.Locale.US, "%.2f",
                        ratio.toDouble() / 1_000.0))
                }
                // Last, as on phone and Combined rows: rates first, then the estimate.
                if (transfer.started && !stale && transfer.metadataPending != true)
                    Units.timeLeft(transfer.sizeBytes - transfer.completedBytes,
                        transfer.downBytesPerSecond)?.let { add(it) }
            }.joinToString(" · ").let { facts ->
                // Rates from an out-of-date snapshot are not live.
                if (stale && facts.isNotEmpty()) "Last read: $facts" else facts
            }
            // Thin collapsed cards: at ordinary text sizes the disclosure shares
            // the rates line instead of adding a full-width row to every card.
            val inlineDisclosure = LocalDensity.current.fontScale < 1.5f
            val disclosureLabel = if (detailsOpen) "Hide details" else "More details"
            val disclosureModifier = Modifier.heightIn(min = 48.dp)
                .semantics { stateDescription = if (detailsOpen) "Expanded" else "Collapsed" }
            if (inlineDisclosure) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (!detailsOpen) conciseRates else "", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { detailsOpen = !detailsOpen },
                        modifier = disclosureModifier) { Text(disclosureLabel) }
                }
            } else {
                if (!detailsOpen && conciseRates.isNotBlank()) {
                    Text(conciseRates, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(
                    onClick = { detailsOpen = !detailsOpen },
                    modifier = disclosureModifier.fillMaxWidth()
                ) { Text(disclosureLabel) }
            }
            if (detailsOpen) {
            if (transfer.started && transfer.downBytesPerSecond == 0L &&
                transfer.fractionComplete < 1f) {
                Text("No download traffic in this snapshot. Start state alone does not prove " +
                    "the torrent is active or connected; use Diagnose download activity below.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (otherTransferDownloading) {
                Text(
                    "Other transfers in this loaded server view report download traffic, " +
                        "while this torrent reports none. This is one server snapshot, " +
                        "not proof of available seeders or future progress for this torrent.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            RemoteRateEvidence.detailLines(transfer).forEach { fact ->
                Text(fact, style = MaterialTheme.typography.bodySmall)
            }
            // Collapsed rows stay scannable; expanded details show the whole
            // Place, because the exact destination is what a person checks.
            Text("Place: " + transfer.directory, style = MaterialTheme.typography.bodySmall,
                color = ChunksColors.TextSecondary,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis)
            val suggestion = remember(transfer.name) { Classifier.suggest(transfer.name) }
            if (suggestion.category != "Unsorted") {
                Text("Suggested: " + suggestion.category +
                    (suggestion.collection?.let { " / " + it } ?: ""),
                    style = MaterialTheme.typography.labelLarge, color = Blue)
            }
            // Presentation only, keyed by the exact authenticated account + hash. No remote
            // payload is read and no RPC is issued for identification or artwork.
            rememberRemoteTransferKey(progressEnvironmentId, transfer.hash)?.let { key ->
                TransferMediaDetailSection(key, transfer.name)
            }
            similar?.invoke()
            ActionTile(if (expanded) "Hide files" else "Inspect files", ChunksColors.Cyan,
                onClick = onInspect, modifier = Modifier.fillMaxWidth(), icon = R.drawable.chunks_ic_folder, stacked = false)
            if (TorrentIdentityHandoff.prepare(transfer.hash, transfer.name) != null) {
                ActionTile("Copy Transfer to phone…", ChunksColors.Blue,
                    onClick = { confirmPhoneHandoff = true }, enabled = !stale,
                    modifier = Modifier.fillMaxWidth(), icon = R.drawable.chunks_ic_copy, stacked = false)
                if (confirmPhoneHandoff) {
                    Text("This torrent's v1 hash and name, plus its public tracker " +
                        "addresses, become a magnet Add draft on your phone. Private " +
                        "torrents get the hash and name only: their tracker passkeys are " +
                        "never read, so they may not find peers. No downloaded bytes move, " +
                        "and the phone may download everything again. The server transfer " +
                        "stays intact.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            confirmPhoneHandoff = false
                            onPreparePhone()
                        }, enabled = !stale, modifier = Modifier.weight(1f)
                            .heightIn(min = 48.dp)) { Text("Review phone Add") }
                        OutlinedButton(onClick = { confirmPhoneHandoff = false },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("Cancel")
                        }
                    }
                }
            }
            if (mainView) TransferMoveControl(
                direction = TransferMove.Direction.SERVER_TO_PHONE,
                infoHash = transfer.hash, name = transfer.name,
                sourceDirectory = transfer.directory,
                serverAliasScope = moveScope?.takeIf {
                    TorrentIdentityHandoff.prepare(transfer.hash, transfer.name) != null
                },
                busy = stale,
                ports = TransferMovePorts(
                    observeTarget = { TransferMoveObservations.phone(transfer.hash) },
                    observeSource = { TransferMoveObservations.server(remote, transfer.hash) },
                    // Guarded by the current name and Place; session-only d.erase.
                    removeSource = { remote.eraseFromSession(transfer.hash, transfer.name, transfer.directory) },
                    targetUnavailableReason = "Open transfers on this device once so Chunks can check the device."
                ),
                onStartMove = onPreparePhone,
                onMutationStarted = onMutationStarted,
                onMutationSettled = onLifecycleSettled
            )
            ServerToPhoneCopyControl(remote = remote, transfer = transfer, stale = stale,
                onReviewPhoneAdd = onCopyToPhone)
            TransferLifecycleControl(
                remote = remote, transfer = transfer, snapshotStale = stale,
                snapshotRevision = snapshotRevision,
                onMutationStarted = onMutationStarted,
                onSettled = onLifecycleSettled
            )
            TransferSessionRemoveControl(
                remote = remote, transfer = transfer, snapshotStale = stale,
                mainView = mainView, onMutationStarted = onMutationStarted,
                onSettled = onLifecycleSettled,
                environmentLabel = removalEnvironmentLabel,
                snapshotRevision = snapshotRevision
            )
            if (expanded) {
                if (filesLoading) Text("Reading server file list…")
                if (filesError != null) Text(filesError, color = ChunksColors.Red)
                if (files != null) {
                    if (mainView && finishInOrderScope != null) RemoteFinishInOrderPanel(
                        remote = remote, transfer = transfer, scope = finishInOrderScope,
                        files = files, stale = stale,
                        onMutationStarted = onMutationStarted, onSettled = onLifecycleSettled)
                    TransferFilePager(
                        remote = remote, hash = transfer.hash, files = files,
                        snapshotStale = stale, mainView = mainView,
                        onMutationStarted = onMutationStarted,
                        onSettled = onLifecycleSettled
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun PlacesScreen(list: List<RemoteTransfer>, onUsePlace: (String) -> Unit) {
    val places = remember(list) { TransferCatalog.places(list) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ColoredPanel("Everything has a place.",
                "These paths come from rTorrent. Chunks hasn't verified filesystem access to them.", Lilac)
        }
        item { PhoneStoragePanel() }
        items(places, key = { it.first }) { place ->
            Card(shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = ChunksColors.Surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(place.first, fontWeight = FontWeight.Bold)
                    Text(place.second.toString() + " transfers",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onUsePlace(place.first) },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Use in Add preview") }
                }
            }
        }
        if (places.isEmpty()) item { Text("No server locations in this snapshot.") }
    }
}

@Composable
private fun OrganizeScreen(
    list: List<RemoteTransfer>,
    scope: String,
    aliasStore: TransferAliasStore,
    aliasRevision: Int
) {
    val context = LocalContext.current
    val membershipStore = remember(context) { TransferCollectionStore(context) }
    val nameRegistry = remember(context) { CollectionNameRegistry(context) }
    var registeredNames by remember(scope) {
        mutableStateOf(if (scope.isBlank()) emptyList() else nameRegistry.names(scope))
    }
    var manualQuery by remember(scope) { mutableStateOf("") }
    var membershipRevision by remember(scope) { mutableStateOf(0) }
    var selectedCollection by remember(scope) { mutableStateOf<String?>(null) }
    var collectionPage by remember(scope) { mutableStateOf(0) }
    // One device-local lookup per transfer and membership revision, reused for
    // counts and filtering; no remote calls or backing-file mutations.
    val membershipsByHash = remember(list, scope, membershipRevision) {
        if (scope.isBlank()) emptyMap()
        else list.associate { it.hash to membershipStore.forTransfer(scope, it.hash) }
    }
    // Counts apply only to the currently loaded rTorrent view. A transfer
    // in another view is not silently interpreted as removed or unassigned.
    val manualCounts = remember(membershipsByHash) {
        membershipsByHash.values.flatten().groupingBy { it }.eachCount()
            .toList().sortedBy { it.first.lowercase() }
    }
    // Search still covers the original title, Chunks alias and path. Apply
    // collection membership before paging; never hide later eligible matches.
    val manualMatches = remember(list, manualQuery, scope, aliasRevision, selectedCollection, membershipsByHash) {
        TransferCatalog.visible(list, manualQuery, TransferFilter.ALL, TransferOrder.NAME) {
            if (scope.isBlank()) it.name else aliasStore.get(scope, it.hash) ?: it.name
        }.asSequence().filter { transfer ->
            CollectionMembershipFilter.matches(selectedCollection,
                membershipsByHash[transfer.hash].orEmpty())
        }.toList()
    }
    val pagedMatches = remember(manualMatches, collectionPage) {
        ManualCollectionPager.slice(manualMatches, collectionPage)
    }
    val knownRemoteCollections = remember(manualCounts, registeredNames) {
        CollectionNameRegistryRules.ordered(registeredNames + manualCounts.map { it.first })
    }
    val visibleCollectionCounts = remember(knownRemoteCollections, manualCounts) {
        knownRemoteCollections.map { name ->
            name to manualCounts.filter { it.first.equals(name, ignoreCase = true) }.sumOf { it.second }
        }
    }
    val collections = remember(list) { TransferCatalog.discoveredCollections(list) }
    val examples = remember(list) {
        list.map { it to Classifier.suggest(it.name) }
            .filter { it.second.category != "Unsorted" }.take(60)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ColoredPanel("Chunks has ideas.",
                "Local filename suggestions only. No media is inspected, no remote paths are changed.", Coral)
        }
        item {
            Text("Your collections", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Text("Manual, device-local memberships. A transfer may belong to several collections; no filenames, folders, rTorrent categories or sharing change.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (scope.isNotBlank()) item {
            CollectionNameManager(scope, nameRegistry, registeredNames) {
                registeredNames = it
            }
        }
        if (visibleCollectionCounts.isEmpty()) item {
            Text("No collections yet. Save an empty name, or assign a transfer to a Collection.")
        }
        items(visibleCollectionCounts, key = { "manual-count-" + it.first }) { (name, count) ->
            FilterChip(
                selected = selectedCollection?.equals(name, ignoreCase = true) == true,
                onClick = {
                    selectedCollection = if (selectedCollection == name) null else name
                    collectionPage = 0
                },
                label = { Text("$name · $count " +
                    (if (count == 1) "transfer" else "transfers")) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
        if (selectedCollection != null) item {
            Text("Showing only " + selectedCollection + " in this server view. A saved Collection may have no members in the loaded view.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { selectedCollection = null; collectionPage = 0 },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Show all collections") }
        }
        item {
            ClearableTransferSearchField(
                value = manualQuery, onValueChange = { manualQuery = it; collectionPage = 0 },
                label = "Find a transfer to organize"
            )
            Text("Found " + pagedMatches.totalMatches + " matching transfers in the loaded server view and selected collection. Page " +
                (pagedMatches.pageIndex + 1) + " of " + pagedMatches.totalPages + ".",
                style = MaterialTheme.typography.bodySmall)
            if (pagedMatches.totalPages > 1) {
                // Full-width controls survive narrow displays and 200% text.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { collectionPage = (pagedMatches.pageIndex - 1).coerceAtLeast(0) },
                        enabled = pagedMatches.hasPrevious,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Previous page") }
                    OutlinedButton(
                        onClick = { collectionPage = pagedMatches.pageIndex + 1 },
                        enabled = pagedMatches.hasNext,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text("Next page") }
                }
            }
        }
        if (scope.isNotBlank()) {
            items(pagedMatches.items, key = { "manual-edit-" + it.hash }) { transfer ->
                val alias = aliasStore.get(scope, transfer.hash)
                if (alias != null && alias != transfer.name) {
                    Text("Original torrent title: " + transfer.name,
                        style = MaterialTheme.typography.bodySmall)
                }
                TransferCollectionEditor(
                    transfer.copy(name = alias ?: transfer.name), scope, membershipStore,
                    availableCollections = knownRemoteCollections
                ) {
                    // Persist only names explicitly saved via manual membership;
                    // classifier hints never become Collection assignments.
                    membershipStore.forTransfer(scope, transfer.hash).forEach {
                        nameRegistry.create(scope, it)
                    }
                    registeredNames = nameRegistry.names(scope)
                    membershipRevision++
                }
            }
        }
        item { Text("Discovered collections", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold) }
        if (collections.isEmpty()) item {
            Text("No obvious collections yet. Chunks will learn from explicit choices in a future release.")
        }
        items(collections, key = { it.first }) { entry ->
            Card(colors = CardDefaults.cardColors(containerColor = Lilac),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(entry.first, fontWeight = FontWeight.Bold)
                    Text(entry.second.toString() + " possible matches · no folders created",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("Why these suggestions?", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold) }
        items(examples, key = { it.first.hash }) { (transfer, idea) ->
            Card(colors = CardDefaults.cardColors(containerColor = ChunksColors.Surface),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(transfer.name, fontWeight = FontWeight.SemiBold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text(idea.category + (idea.collection?.let { " · " + it } ?: "") +
                        " · " + idea.confidence.toString() + "% category hint",
                        color = Blue, style = MaterialTheme.typography.bodySmall)
                    Text(idea.reasons.joinToString(". "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ServerScreen(
    version: String, displayedView: String, requestedView: String, onRequestedView: (String) -> Unit,
    loading: Boolean, stale: Boolean, count: Int,
    credentialNotice: String?, savedLoginPresent: Boolean, onForgetLogin: () -> Unit,
    onRefresh: () -> Unit, onDisconnect: () -> Unit,
    onSeedingRules: () -> Unit = {},
    /** Exact server environment label, so the view choice states its scope. */
    environmentLabel: String = "this server"
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Server settings · " + environmentLabel.removePrefix("Server · "),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Text("rTorrent " + version +
                if (stale) " · last known list (refresh needed)" else " · connected session",
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("Server view", style = MaterialTheme.typography.titleLarge)
            SettingScopeNote(environmentLabel)
            Text("Different rTorrent servers organize views differently. Chunks never assumes the current view covers every torrent.",
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            FlowRow(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (view in listOf("main", "default", "started", "stopped")) {
                    FilterChip(selected = requestedView == view,
                        onClick = { onRequestedView(view) },
                        label = { Text(view) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        item {
            Text("Showing " + count + " transfers from '" + displayedView + "'" +
                if (stale) " · stale snapshot" else "",
                style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Button(onClick = onRefresh, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                Text(if (loading) "Refreshing…" else "Load selected view")
            }
        }
        if (savedLoginPresent) item {
            OutlinedButton(onClick = onForgetLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Forget saved login")
            }
        }
        if (credentialNotice != null) item {
            Text(credentialNotice, style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedButton(onClick = onSeedingRules, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                Text("Seeding rules…")
            }
        }
        item {
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                Text("Disconnect")
            }
        }
        item {
            Text("The current connection remains in memory. Unless opted out, the server login is encrypted on this device for later reconnection. Torrent operations remain separately confirmed.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ColoredPanel(
    title: String, message: String, color: Color, titleAsHeading: Boolean = false
) {
    Card(shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black, color = Ink,
                modifier = Modifier.semantics { if (titleAsHeading) heading() })
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Ink)
        }
    }
}

/**
 * Preserve an honest entire rate label at 320dp and 200% font size.
 * This is a presentation decision only: rates and the server scope are
 * computed by the existing observation model, and no action is dispatched.
 */
@Composable
internal fun RemoteRateStatTiles(
    download: RemoteRateSubtotal,
    upload: RemoteRateSubtotal,
    started: Int
) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 380.dp || scale >= 1.5f) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Down", download.tileLabel(), Lilac, Modifier.fillMaxWidth())
                StatTile("Up", upload.tileLabel(), Coral, Modifier.fillMaxWidth())
                StatTile("Start set", started.toString(), Lime, Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Down", download.tileLabel(), Lilac, Modifier.weight(1f))
                StatTile("Up", upload.tileLabel(), Coral, Modifier.weight(1f))
                StatTile("Start set", started.toString(), Lime, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Ink)
            // Unknown/partial rate labels must remain readable, including 200% text.
            Text(value, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold, color = Ink)
        }
    }
}

@Composable
/** A failure the user should read before retrying. */
private fun Notice(title: String, message: String) =
    ChunksBanner(title, message, ChunksTone.ERROR, Modifier.padding(horizontal = 2.dp))
