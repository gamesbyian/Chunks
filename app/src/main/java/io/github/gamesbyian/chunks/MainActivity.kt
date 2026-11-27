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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.TransferCatalog
import io.github.gamesbyian.chunks.model.RemoteBackendKind
import io.github.gamesbyian.chunks.model.RemoteProtocolReadOnlyClient
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
import io.github.gamesbyian.chunks.snapshots.SnapshotFilePriorities
import io.github.gamesbyian.chunks.snapshots.SnapshotSettingsCapture
import io.github.gamesbyian.chunks.snapshots.SnapshotRemotePriorityComparison
import io.github.gamesbyian.chunks.snapshots.AndroidSnapshotRestoreJournalStore
import io.github.gamesbyian.chunks.snapshots.SnapshotRemoteDefinitionRestore
import io.github.gamesbyian.chunks.snapshots.SnapshotRemoteBatchStoppedRestore
import io.github.gamesbyian.chunks.snapshots.SnapshotRemotePriorityApply
import io.github.gamesbyian.chunks.snapshots.applyRemoteSnapshotAlias
import io.github.gamesbyian.chunks.snapshots.SnapshotDocumentInspectScreen
import io.github.gamesbyian.chunks.snapshots.SnapshotRestorePreviewPanel
import io.github.gamesbyian.chunks.snapshots.SnapshotRestoreTarget
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.gamesbyian.chunks.privacy.LocalTransferPrivacyGate
import io.github.gamesbyian.chunks.privacy.GatedProtocolAdd
import io.github.gamesbyian.chunks.privacy.RemoteTransferPrivacyGate
import io.github.gamesbyian.chunks.privacy.RemoteTransferPrivacyScope
import io.github.gamesbyian.chunks.privacy.TransferPrivacyPolicyStore
import io.github.gamesbyian.chunks.privacy.TransferPrivacyRuntime
import io.github.gamesbyian.chunks.rpc.RemoteWriteOwnerFence
import io.github.gamesbyian.chunks.seeding.RemoteSeeding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Local aliases for the shared ChunksColors tokens (see ChunksTheme.kt).
// Panels filled with an accent always carry Ink (near-black) text.
internal val Ink = ChunksColors.OnAccent
internal val Cream = ChunksColors.Background
internal val Lilac = ChunksColors.Purple
internal val Lime = ChunksColors.Green
internal val Coral = ChunksColors.Orange
internal val Blue = ChunksColors.Cyan

internal const val SAVED_DOCUMENT_REVIEW = "chunks.add.document-review-present"
internal const val INTERRUPTED_DOCUMENT_REVIEW =
    "Android restarted Chunks. Check Transfers, then reopen the .torrent to review it again."

class MainActivity : ComponentActivity() {
    // Retained across Activity recreation, never persisted on process death.
    private val localAddAttemptOwner: LocalAddAttemptOwner by viewModels()
    private val remoteSessionOwner: RemoteSessionOwner by viewModels()
    private fun accept(intent: Intent?) {
        val create = io.github.gamesbyian.chunks.create.SharedCreateDocument.fromIntent(intent)
        if (create != null) {
            localAddAttemptOwner.offerCreate(create)
            return
        }
        val candidate = ExternalAddSource.fromIntent(intent)
        logIntake(intent, candidate)
        localAddAttemptOwner.offerAdd(candidate)
    }

    /**
     * #1786 diagnostics: `adb logcat -s ChunksIntake` separates a refused handoff from a
     * crash or lost task. Fixed action/MIME/scheme categories and outcome only,
     * never untrusted raw MIME or URI scheme, full URI, display name, magnet,
     * tracker or file content.
     */
    private fun logIntake(intent: Intent?, candidate: ExternalAddSource?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return
        val outcome = when (candidate) {
            null -> "refused"
            is ExternalAddSource.TorrentDocument -> "document"
            is ExternalAddSource.Magnet -> "magnet"
            is ExternalAddSource.TorrentLink -> "link"
        }
        runCatching {
            android.util.Log.i("ChunksIntake", "action=" + action.substringAfterLast('.') +
                " type=" + IntakeDiagnosticLabels.mime(intent.type) +
                " scheme=" + IntakeDiagnosticLabels.scheme(intent.data?.scheme) +
                " outcome=" + outcome + " task=" + taskId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A possible typed remote write that outlived the previous process stays unresolved.
        ProtocolOperationReceipts.install(applicationContext)
        // The near-black Compose surface extends behind the system status bar,
        // so the status bar icons must be light.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        // An Android share is a one-shot event. After an Activity recreation,
        // the original launch Intent is still attached to this Activity, but
        // its source may already have been consumed or superseded. Replaying
        // it would re-open an old Add review and overwrite a newer draft.
        // Genuine new shares still enter through onNewIntent().
        if (savedInstanceState == null) {
            accept(intent)
        } else if (savedInstanceState.getBoolean(SAVED_DOCUMENT_REVIEW) &&
            !localAddAttemptOwner.hasDocumentReview()) {
            localAddAttemptOwner.sharedTorrentError.value = INTERRUPTED_DOCUMENT_REVIEW
        }
        setContent {
            ChunksTheme {
                // One process-wide media controller for rows, details and settings alike.
                val mediaController = androidx.compose.runtime.remember {
                    io.github.gamesbyian.chunks.media.ChunksMedia.get(applicationContext).controller
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    io.github.gamesbyian.chunks.media.LocalTransferMediaController provides mediaController
                ) {
                // The full-inventory media witness lives for the whole activity content, so
                // Details, Options, Add and Connections no longer cancel offscreen lookups
                // (#1453). The canonical Transfers source feeds it; a privacy lifecycle or
                // policy generation change revokes it until the next fresh feed.
                io.github.gamesbyian.chunks.media.ProvideMediaInventoryHost(
                    // Snapshot-read inside the host's effect, never in this composition: a
                    // foreground/background or policy revision must not recompose the whole app.
                    privacyEpoch = {
                        "${TransferPrivacyRuntime.lifecycleRevision}/" +
                            "${io.github.gamesbyian.chunks.privacy.TransferPrivacyPolicyStore.revision}"
                    }
                ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChunksApp(localAddAttemptOwner, remoteSessionOwner)
                }
                }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // A restored task may explain why its document review is missing. The
        // marker carries neither the source nor authority to reopen or submit it.
        if (localAddAttemptOwner.hasDocumentReview())
            outState.putBoolean(SAVED_DOCUMENT_REVIEW, true)
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

internal data class VerifiedConnectionNotice(val label: String, val saved: Boolean?)

internal data class SnapshotServerInventory(
    val account: RTorrentRemote,
    val revision: Long,
    val hashes: List<String>
)

// A cancelled composition cannot erase a document still owned by the
// configuration-retained source review. Only the live completed reader consumes it.
internal fun shouldConsumeReviewedSourceOnExit(
    isCurrentDocument: Boolean,
    lifecycleCancelled: Boolean
): Boolean = isCurrentDocument && !lifecycleCancelled

/** Generated exact-transfer lookups are temporary and stay in memory across recreation. */
internal class UniversalAddNavigationSearch {
    var exactHash by mutableStateOf<String?>(null)
        private set

    fun viewTransfer(hash: String) { exactHash = hash }
    fun clear() { exactHash = null }
    fun displayQuery(manualSearch: String): String = exactHash ?: manualSearch
}

internal enum class Section(val label: String) {
    TRANSFERS("Transfers"), COMBINED("All clients"), PLACES("Places"), ORGANIZE("Organize"), SERVER("Connections"), ARCHIVES("ZIP review"), CREATE("Create"), SEEDING("Seeding rules"), MEDIA("Artwork & info"), LOCAL("Transfers on this device"), OPTIONS("Options"), ADD_CHOOSE("Add"), ROUTING("Smart Routing"), NAMES("Names"), SNAPSHOT("Inventory backup"), SNAPSHOT_INSPECT("Inspect backup")
}

/** "12 shown", or "3 of 12 shown" when a search or filter narrows the list (as on the local device). */
internal fun remoteShownCount(shown: Int, loaded: Int): String =
    if (shown == loaded) "$shown shown" else "$shown of $loaded shown"

/**
 * Search the existing Options destinations without new authority. Aliases are keyed by the
 * [Section] itself, never by its label text, so a relabelled destination (Server → Connections,
 * On this device → Transfers on this device) cannot silently lose its aliases.
 */
internal fun optionsDestinationMatches(section: Section, rawQuery: String): Boolean {
    val needle = rawQuery.trim().lowercase(java.util.Locale.ROOT)
    if (needle.isEmpty()) return true
    val aliases = when (section) {
        Section.TRANSFERS -> "torrent downloads active list search"
        Section.LOCAL -> "local local device downloads storage files"
        Section.PLACES -> "folder destination location save path"
        Section.ORGANIZE -> "collections categories organize grouping"
        Section.SERVER -> "connection connections endpoint profile account login settings remote client clients server"
        Section.ARCHIVES -> "zip archives compressed inspect"
        Section.CREATE -> "create torrent publish share new"
        Section.MEDIA -> "artwork art poster cover image media metadata identify match provider tmdb lookup"
        else -> ""
    }
    val haystack = (section.label + " " + aliases).lowercase(java.util.Locale.ROOT)
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
    retainedAddOwner: LocalAddAttemptOwner,
    /** Retained by the Activity; tests and previews default to a remembered, non-retained owner. */
    remoteSession: RemoteSessionOwner = remember { RemoteSessionOwner() }
) {
    val incomingDelivery = retainedAddOwner.incomingDelivery
    val incomingAdd = (incomingDelivery as? ExternalIntakeDelivery.Add)?.source
    val sharedMagnet = when (incomingAdd) {
        is ExternalAddSource.Magnet -> incomingAdd.value
        is ExternalAddSource.TorrentLink -> incomingAdd.value
        else -> null
    }
    val incomingTorrent = (incomingAdd as? ExternalAddSource.TorrentDocument)?.uri
    val incomingCreate = (incomingDelivery as? ExternalIntakeDelivery.Create)?.request
    val context = LocalContext.current
    val vault = remember(context) { RememberedRemoteLogin(context) }
    val profileRoster = remember(context) { RememberedRemoteProfileRoster(context) }
    val protocolProfileRoster = remember(context) { RememberedRemoteProtocolProfiles(context) }
    val remembered = remember(vault, profileRoster) {
        vault.load() ?: profileRoster.list().firstOrNull()?.credentials
    }
    var savedProfiles by remember(profileRoster) {
        mutableStateOf(if (vault.rememberingEnabled()) profileRoster.list() else emptyList())
    }
    var savedProtocolProfiles by remember(protocolProfileRoster) {
        mutableStateOf(if (vault.rememberingEnabled()) protocolProfileRoster.list() else emptyList())
    }
    // The optional backend choice persists as nonsecret UI state; a password,
    // accepted read, client transport or authentication ticket never does.
    var chosenBackend by rememberSaveable { mutableStateOf(RemoteBackendKind.RTORRENT) }
    var delugeDaemonHostId by remember { mutableStateOf("") }
    var readOnlyBackendError by remember { mutableStateOf<String?>(null) }
    // The verified typed (Transmission/qBittorrent/Deluge) connection is
    // retained with the rTorrent session through recreation (A2611-02).
    var verifiedReadOnlyProfileId by remoteSession.verifiedProtocolProfileId
    var transientReadOnlyProfile by remoteSession.transientProtocolProfile
    var readOnlyConnectJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val combinedProtocolProfiles = remember(savedProtocolProfiles, transientReadOnlyProfile) {
        RemoteProtocolCombinedProfiles.saved(savedProtocolProfiles) +
            listOfNotNull(transientReadOnlyProfile?.takeUnless { candidate ->
                savedProtocolProfiles.any { it.id == candidate.id }
            })
    }
    var endpoint by rememberSaveable { mutableStateOf(remembered?.endpoint.orEmpty()) }
    // Advanced manual endpoint path; joined with a hostname-only address.
    var rpcPath by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf(remembered?.username.orEmpty()) }
    var connectionName by rememberSaveable { mutableStateOf("") }
    // Restore nonsecret drafts together, NEVER refill a restored draft with a
    // different remembered account's password or automatically submit that draft.
    var connectionDraftEdited by rememberSaveable { mutableStateOf(false) }
    var password by remember {
        mutableStateOf(if (connectionDraftEdited) "" else remembered?.password.orEmpty())
    }
    // Remembering is the default. Every user can opt out before Connect.
    var rememberLogin by remember { mutableStateOf(vault.rememberingEnabled()) }
    // A cold launch may attempt ONE read-only authenticated connection using the last
    // verified saved login. A failed 401/timeout does not start a retry loop, and
    // Disconnect/Cancel never automatically reconnect within the same app session.
    var startupReconnectAttempted by remember { mutableStateOf(false) }
    // Explicit Disconnect survives Activity recreation within this task.
    // A separate saved flag is essential: saving the startup-attempt flag
    // would disable legitimate read-only reconnect after ordinary process death.
    var explicitDisconnectSuppressed by rememberSaveable { mutableStateOf(false) }
    // Startup reconnect is one read-only probe; manual connection creation
    // returns to Connections with an explicit verified-result acknowledgement.
    var returnFromStartupConnectToCanonical by remember { mutableStateOf(false) }
    var verifiedConnectionNotice by remember { mutableStateOf<VerifiedConnectionNotice?>(null) }
    var savedLoginPresent by remember {
        mutableStateOf(remembered != null || savedProfiles.isNotEmpty() ||
            savedProtocolProfiles.isNotEmpty())
    }
    var credentialNotice by remember { mutableStateOf<String?>(null) }
    // A2611-02: the authenticated session, its inventory, fences and readback
    // gate are retained together through recreation (RemoteSessionOwner).
    var server by remoteSession.server
    var version by remoteSession.version
    var transfers by remoteSession.transfers
    // Wall-clock time of the last accepted server list, for honest freshness in combined views.
    var transfersObservedAt by remoteSession.transfersObservedAt
    // Advances ONLY when the server has delivered a fresh, accepted queue read.
    // The operation UI can then allow another separately confirmed lifecycle command.
    var snapshotRevision by remoteSession.snapshotRevision
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The current refresh error is an observed login refusal (HTTP 401/403).
    // Derived per message, so any later error or success clears it.
    var loginRefusedError by remember { mutableStateOf<String?>(null) }
    var connectDiagnostics by remember { mutableStateOf(ConnectionDiagnostics()) }
    var connectJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var stale by remoteSession.stale
    var currentView by remoteSession.currentView
    var requestedView by remoteSession.requestedView
    // Open the canonical catalog by default. A saved login can temporarily
    // delegate its one read-only reconnect to the existing Connections owner.
    var section by rememberSaveable(saver = SectionRestoreSaver) {
        mutableStateOf(Section.COMBINED)
    }
    // Restored canonical Transfers cannot itself run an authenticated Connect.
    // Delegate exactly one eligible cold-start attempt to existing Connections,
    // preserving its identity/credential epoch, cancel and read-only contracts.
    LaunchedEffect(section, remembered, rememberLogin, startupReconnectAttempted,
        explicitDisconnectSuppressed, connectionDraftEdited, server) {
        if (NavigationRestorePolicy.requiresColdReconnectHandoff(
                section = section.name,
                savedCredentialPresent = remembered != null,
                rememberingEnabled = rememberLogin,
                alreadyAttempted = startupReconnectAttempted ||
                    explicitDisconnectSuppressed || connectionDraftEdited,
                authenticated = server != null
            )) {
            returnFromStartupConnectToCanonical = true
            section = Section.SERVER
        }
    }

    // Keep the exact selection through configuration changes, without saving credentials
    // or letting a different authenticated session inherit the previous account's draft.
    var addDestination by retainedAddOwner.addDestination
    val addProtocolTargetId = (addDestination as? UniversalAddRouteTarget.Protocol)?.profileId
    var localSnapshotAddReview by remember { mutableStateOf(false) }
    LaunchedEffect(section) {
        if (section != Section.ADD_CHOOSE) localSnapshotAddReview = false
    }
    val artworkExpanded = retainedAddOwner.artworkExpanded
    // The Transmission/qBittorrent/Deluge attempt lives in the same app-owned owner as
    // Local and rTorrent: its write already runs in that owner's scope, so the receipt,
    // busy state and one-Add claim must survive Activity recreation with it.
    val protocolAddAttempt = retainedAddOwner.protocolAttempt
    var combinedFromLocal by rememberSaveable { mutableStateOf(false) }
    var combinedFromConnections by rememberSaveable { mutableStateOf(false) }
    var returnToCatalogRevision by remember { mutableLongStateOf(0L) }
    var showAllEnvironmentsRevision by remember { mutableLongStateOf(0L) }
    var consumedShowAllEnvironmentsRevision by remember { mutableLongStateOf(0L) }
    // View-only one-shot canonical catalog focus; never a login, Add target,
    // RPC account or authority. The browser consumes this exact stable ID.
    var savedProfileCatalogFocus by remember { mutableStateOf<String?>(null) }
    // One safe navigation return edge only, never an Add draft or authority.
    // When entering local from Combined, the local device's own Back returns there.
    var localFromCombined by rememberSaveable { mutableStateOf(false) }
    // A tool (Seeding rules, Create, ZIP review) returns to the screen that
    // opened it, e.g. the local device or Server, instead of always to the
    // server/connection entry. Navigation only; no command or draft.
    var toolOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    var localReturnFocus by remember { mutableStateOf<String?>(null) }
    fun openTool(tool: Section, from: Section) {
        toolOrigin = from.name
        section = tool
    }
    fun openFromLocal(tool: Section) = openTool(tool, Section.LOCAL)
    fun leaveTool() {
        // Returning to the local device: the button that opened this tool regains focus.
        if (toolOrigin == Section.LOCAL.name || toolOrigin == Section.OPTIONS.name)
            localReturnFocus = section.name
        section = toolOrigin?.let { origin -> Section.entries.firstOrNull { it.name == origin } }
            ?: Section.LOCAL
        toolOrigin = null
    }
    fun leaveLocal() {
        val returnToCombined = localFromCombined
        localFromCombined = false
        section = if (returnToCombined) Section.COMBINED else Section.LOCAL
    }
    var query by remember { mutableStateOf("") }
    // Secondary destinations appear only when requested on the ordinary list.
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    // Navigation-only list state that survives moving between routes
    // (local ↔ All environments ↔ server ↔ settings) and rotation. It holds
    // no selection, confirmation or command state.
    val localNavigation = rememberLocalNavigationMemory()
    // The single Add source draft belongs to the app, never to the currently
    // displayed local/server Add form. Both adapters edit the same source;
    // neither browsing filters nor opening a screen silently authorizes Add.
    // Process memory only, never SavedState/preferences: links can hold passkeys.
    val addSourceDraft = retainedAddOwner.sourceDraft
    val addAliasDraft = retainedAddOwner.aliasDraft
    val verifiedAliasStore = remember(context) { TransferAliasStore(context) }
    val localAddPlace = rememberSaveable { mutableStateOf<String?>(null) }
    val localAddFolder = rememberSaveable { mutableStateOf("") }
    val localAddStart = rememberSaveable { mutableStateOf(true) }
    // A reviewed .torrent can include private tracker tokens. Keep its bytes above
    // the disposable local route for navigation only; never use SavedState/Bundle.
    val localReviewedTorrent = retainedAddOwner.reviewedTorrent
    val activeLocalAddAttempt = retainedAddOwner.session
    // A local opt-out must not silently become an opt-out on another server
    // (or the same server endpoint's different principal/numbered route).
    // No raw source, account identity or private tracker enters SavedState.
    val addArtworkChoices = retainedAddOwner.artworkChoices
    val combinedListState = rememberLazyListState()
    var combinedDetailsVisible by remember { mutableStateOf(false) }
    // Combined search text, like the local device's: navigation state, not a preference.
    val combinedSearch = rememberSaveable { mutableStateOf("") }
    val addTransferSearch = retainedAddOwner.transferSearch
    LaunchedEffect(section) {
        if (section == Section.ADD_CHOOSE) addTransferSearch.clear()
    }
    // Last read-only listings of other saved profiles survive navigation between
    // roots, but never a privacy lock/unlock or policy lifecycle change.
    val combinedSavedListings = remember(TransferPrivacyRuntime.lifecycleRevision) {
        mutableStateOf<Map<io.github.gamesbyian.chunks.model.EnvironmentId, SavedServerListing>>(emptyMap())
    }
    // Per exact rTorrent account: list position and search survive route
    // switches (local, All environments, Places) and Activity recreation.
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
    // Remote Add and local Add share the same editable source, but retain
    // independent capability/Place/Start checks until their submit coordinator
    // is consolidated. No second magnet string is allowed to diverge here.
    var magnet by addSourceDraft
    // Process-memory-only Android Share source awaiting an explicit exact destination.
    // A magnet can contain tracker passkeys: never save/log/render its raw value.
    var pendingSharedMagnet by retainedAddOwner.pendingSharedMagnet
    // Explicitly replace or retain an occupied source before reading a newly
    // shared document. Neither URI nor metainfo is serialized to SavedState.
    var pendingSharedDocument by retainedAddOwner.pendingSharedDocument
    var incomingDocumentRead by retainedAddOwner.readingSharedDocument
    // Shared content:// documents are never persisted or sent to a remote server
    // as a URI. A selected remote receives only locally validated metainfo bytes.
    val sharedTorrentReading = incomingDocumentRead != null
    var sharedTorrentError by retainedAddOwner.sharedTorrentError
    // A server job's magnet waiting for the Local review: retained, single-claim, never an overwrite.
    val localHandoff = retainedAddOwner.handoff.staged
    // A transfer opened from All environments: searched once on the local device list.
    var localSearchPrefill by remember { mutableStateOf<String?>(null) }
    // A local transfer's exported .torrent waiting for the server Add review.
    // Process memory only; consumed by that review, never auto-submitted.
    fun remoteHandoff() = retainedAddOwner.remoteHandoff
    // Account-scoped recovery outlives Add's composition, including privacy gates
    // and reconnects. Process memory only; Activity/process restoration is separate.
    val remoteAddSessions = retainedAddOwner.remoteSessions
    val scope = rememberCoroutineScope()
    val credentialChanges = remember { RemoteCredentialActionQueue() }
    val aliasStore = remember(context) { TransferAliasStore(context) }
    val recentPlaceStore = remember(context) { RecentRemotePlaceStore(context) }
    var recentPlaceRevision by remember { mutableStateOf(0) }
    // Owned by the connected app, not the recyclable LazyColumn card.
    // A plain remembered map prevents getOrPut writes during composition from
    // being rolled back; each value has observable Compose state fields.
    val aliasSessions = remember { mutableMapOf<String, TransferAliasEditSession>() }
    var aliasScope by remoteSession.aliasScope
    // The selected source text, reviewed local metainfo, remote account session,
    // Place and Start remain parent-owned when Add changes its target in place.
    // Switching never authorizes or starts an engine operation.
    fun selectLocalAddTarget() {
        val remoteReview = aliasScope.takeIf {
            it.isNotBlank() && addDestination == UniversalAddRouteTarget.RTorrent(it) && remoteHandoff() == null
        }
            ?.let { remoteAddSessions.forAccount(it) }
        // The explicit remote file choice may supersede text that remains in
        // its source field. Transfer that chosen file, not the inactive text,
        // into the one local draft. A later text edit revokes the file choice.
        if (activeLocalAddAttempt.attemptedTicket == null &&
            remoteReview?.alreadyAttempted == false && remoteReview.useFile &&
            remoteReview.textAtFileSelection == magnet &&
            remoteReview.selectedTorrent != null && remoteReview.selectedBytes != null) {
            val bytes = requireNotNull(remoteReview.selectedBytes)
            // Preserve an Android document's original URI/handling authority
            // when this is already the very same reviewed source.
            localReviewedTorrent.value = remoteReview.selectedLocalReview()
                ?: PendingLocalAdd(requireNotNull(remoteReview.selectedTorrent), bytes.copyOf())
            magnet = ""
            remoteReview.textAtFileSelection = ""
        }
        localSnapshotAddReview = false
        addDestination = UniversalAddRouteTarget.Local
        section = Section.ADD_CHOOSE
    }
    fun selectAuthenticatedAddTarget() {
        if (server == null || aliasScope.isBlank() ||
            RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(aliasScope) == null) return
        val remoteReview = remoteAddSessions.forAccount(aliasScope)
        // The person explicitly chose this account's .torrent while the current text
        // was already present (the Add screen's own `.torrent file` choice). A target
        // switch is not a newer source and must not revert that choice to the magnet.
        val fileChosenOverCurrentText = remoteReview.useFile &&
            remoteReview.selectedBytes != null && remoteReview.textAtFileSelection == magnet
        if (MagnetDraftCheck.inspect(magnet) is MagnetDraftCheck.Valid &&
            !remoteReview.alreadyAttempted && !fileChosenOverCurrentText) {
            retainedAddOwner.clearRemoteHandoff()
            localReviewedTorrent.value = null
            remoteReview.preferMagnet()
        }
        if (magnet.isBlank() && !remoteReview.alreadyAttempted &&
            localReviewedTorrent.value != null) {
            retainedAddOwner.prepareCurrentReviewForRemote()
        } else if (magnet.isNotBlank() && !fileChosenOverCurrentText) {
            remoteReview.preferMagnet()
        }
        addDestination = UniversalAddRouteTarget.RTorrent(aliasScope)
        section = Section.ADD_CHOOSE
    }
    fun prepareRemoteMagnet(source: String) {
        magnet = source
        remoteAddSessions.selectMagnet(aliasScope)
    }
    /**
     * An explicitly chosen .torrent is the newest source and replaces any older typed magnet:
     * Universal Add holds one source, and a stale valid magnet would otherwise make
     * [selectAuthenticatedAddTarget] discard the document the user just picked.
     */
    fun prepareRemoteTorrent(document: ByteArray, reviewed: PendingLocalAdd? = null): Boolean =
        retainedAddOwner.stageRemoteTorrentHandoff(document, reviewed)
    var aliasRevision by remember { mutableStateOf(0) }
    // Purely derive the configured identities during composition. SharedPreferences
    // writes must happen after composition, off the main thread, and only when
    // the identity set changes. A malformed legacy profile cannot block local.
    val paletteIdentities = remember(savedProfiles, savedProtocolProfiles,
        transientReadOnlyProfile, aliasScope) {
        buildList {
            add(io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL)
            io.github.gamesbyian.chunks.model.EnvironmentSnapshots
                .serverIdOfAliasScope(aliasScope)?.let { add(it) }
            savedProfiles.forEach { profile ->
                runCatching { io.github.gamesbyian.chunks.model.EnvironmentId(profile.id) }
                    .getOrNull()?.let { add(it) }
            }
            combinedProtocolProfiles.forEach { profile ->
                add(profile.environment)
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
    // The most recent acknowledged Add from the local device or a server, for Combined.
    // A server key is re-checked against the current account when it is used.
    fun setRemoteQuery(value: String) {
        query = value
        remoteContinuity.rememberQuery(aliasScope, value)
    }
    val remoteFence = remoteSession.remoteFence
    val inspectionFence = remoteSession.inspectionFence
    val mutationGate = remoteSession.mutationGate
    val savedAddReconnect = remember { SavedAddProfileReconnect() }

    // A profile switch is a read-only navigation/authentication choice, but
    // it must retire the old authenticated principal before displaying the
    // connection form. Reuse the ordinary Disconnect revocation path; do not
    // clear any Add source, reviewed bytes, exact-job attempt or account-specific
    // Add preferences. No remote RPC or torrent mutation is sent here.
    fun detachAuthenticatedRemoteSession() {
        remoteFence.invalidate()
        connectJob?.cancel()
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
    }

    /**
     * A new explicit Android Share/Open With source after a FULLY VERIFIED Add begins the
     * next Add, exactly like the person's own "Start another Add" / "Review another local
     * Add". Without this, the finished attempt latch hid the new source and the Add action
     * (#1650 follow-up: second Open With reached Add with no usable submit). Pending,
     * uncertain or merely session-confirmed attempts are never released here; they keep
     * their explicit acknowledgement and no write is ever replayed.
     */
    fun releaseVerifiedAttemptsForNewSource() {
        if (AddSourceOccupancy.localVerifiedAttemptReleasable(
                localSelected = addDestination == UniversalAddRouteTarget.Local,
                attemptSubmitted = activeLocalAddAttempt.attemptedTicket != null,
                newReviewedDocumentPresent = localReviewedTorrent.value?.originalDocument != null,
                originalDocumentDeletionPending = activeLocalAddAttempt.originalDocument.deletion != null,
                originalDocumentDeleting = activeLocalAddAttempt.originalDocument.deleting,
                verified = activeLocalAddAttempt.coordinator.phase ==
                    io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.VERIFIED) &&
            activeLocalAddAttempt.finishReviewedAttempt()) {
            // The finished source is one shared document: clear both of its halves.
            localReviewedTorrent.value = null
            retainedAddOwner.clearRemoteHandoff()
        }
        val remoteDraft = aliasScope.takeIf { it.isNotBlank() }?.let { remoteAddSessions.forAccount(it) }
        if (addDestination == UniversalAddRouteTarget.RTorrent(aliasScope) &&
            remoteDraft != null && remoteDraft.alreadyAttempted && !remoteDraft.submitting &&
            remoteDraft.originalDocumentStatus.deletion == null &&
            remoteDraft.coordinator.phase ==
                io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.VERIFIED &&
            canCloseRemoteAddReview(remoteDraft.coordinator.phase, remoteDraft.verifiedHash,
                remoteDraft.uncertain)) {
            val oldSource = remoteDraft.attemptSource
            val oldDocument = remoteDraft.selectedLocalReview()
            val currentDocument = localReviewedTorrent.value
            val sameDraft = magnet == oldSource && remoteHandoff() == null &&
                (currentDocument == null || currentDocument === oldDocument)
            if (remoteDraft.beginNextDraft() && sameDraft) {
                magnet = ""
                retainedAddOwner.retireCompletedRemoteReview(oldDocument)
                addArtworkChoices.onRemoteClient(aliasScope).value = true
            }
        }
        val protocolId = addProtocolTargetId
        if (protocolId != null && protocolAddAttempt.profileId == protocolId &&
            protocolAddAttempt.coordinator(protocolId).phase ==
                io.github.gamesbyian.chunks.model.UniversalAddCoordinator.Phase.VERIFIED &&
            !protocolAddAttempt.originalDocument.deleting) {
            val sameDraft = protocolAddAttempt.submittedDraftStillSelected(magnet, localReviewedTorrent.value)
            if (protocolAddAttempt.clearSettled(protocolId) && sameDraft) {
                localReviewedTorrent.value = null
                magnet = ""
                addArtworkChoices.onProtocol(protocolId).value = true
            }
        }
    }

    fun selectedRemoteAddSession(): RemoteAddSession? {
        val selected = addDestination as? UniversalAddRouteTarget.RTorrent
        return remoteAddSessions.existingForAccount(selected?.accountScope ?: aliasScope)
    }

    /** An unsubmitted draft or a not-yet-releasable attempt holds a new shared source. */
    fun addSourceOccupied(incomingText: String? = null): Boolean {
        val remoteDraft = selectedRemoteAddSession()
        // A first Android document being read is an occupied draft even
        // before the new .torrent metainfo is visible on the Add form.
        return AddSourceOccupancy.occupied(
            sharedTorrentReading = sharedTorrentReading,
            draftText = magnet, incomingText = incomingText,
            localReviewedFilePresent = localReviewedTorrent.value != null,
            serverHandoffPresent = remoteHandoff() != null,
            remoteFileChosen = remoteDraft?.useFile == true && remoteDraft.selectedBytes != null,
            localAttemptSubmitted = activeLocalAddAttempt.attemptedTicket != null,
            remoteAlreadyAttempted = remoteDraft?.alreadyAttempted == true,
            protocolAttemptHeldForSelectedClient =
                addProtocolTargetId != null && protocolAddAttempt.profileId == addProtocolTargetId)
    }

    fun readSharedDocument(document: Uri) {
        if (pendingSharedDocument == document) pendingSharedDocument = null
        sharedTorrentError = null
        incomingDocumentRead = document
    }

    // Intake is shared across destinations. A held source may proceed after a
    // verified attempt settles only if there is no competing draft to replace.
    val heldRemoteAdd = selectedRemoteAddSession()
    val heldProtocolAdd = addProtocolTargetId?.takeIf { protocolAddAttempt.profileId == it }
    val heldLocalObservation = retainedAddOwner.attempts
        .observationRevision(io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL)
    LaunchedEffect(
        incomingDelivery, pendingSharedDocument, pendingSharedMagnet,
        sharedTorrentReading, addDestination, aliasScope, localReviewedTorrent.value,
        heldLocalObservation, activeLocalAddAttempt.coordinator.phase,
        activeLocalAddAttempt.originalDocument.deletion,
        activeLocalAddAttempt.originalDocument.deleting,
        heldRemoteAdd?.coordinator?.phase, heldRemoteAdd?.verifiedHash, heldRemoteAdd?.submitting,
        heldRemoteAdd?.originalDocumentStatus?.deletion, heldRemoteAdd?.originalDocumentStatus?.deleting,
        heldProtocolAdd?.let { protocolAddAttempt.coordinator(it).phase }, protocolAddAttempt.busy,
        protocolAddAttempt.originalDocument.deletion, protocolAddAttempt.originalDocument.deleting
    ) {
        val document = pendingSharedDocument
        val text = pendingSharedMagnet
        // An unclaimed delivery (Add or Create) outranks promotion of an
        // already-held source. Read the current owner, not a captured Compose
        // value: a later Android event may have arrived since composition.
        if (retainedAddOwner.incomingDelivery != null || sharedTorrentReading ||
            (document == null && text == null)) return@LaunchedEffect
        releaseVerifiedAttemptsForNewSource()
        if (addSourceOccupied()) return@LaunchedEffect
        pendingSharedDocument = null
        pendingSharedMagnet = null
        sharedTorrentError = null
        if (document != null) readSharedDocument(document)
        else if (text != null) {
            magnet = text
            retainedAddOwner.handoff.drop()
            remoteAddSessions.selectMagnet(aliasScope)
        }
    }

    // A server job's magnet is delivered to the Local review exactly once, never
    // replayed after the person leaves it, and never outlives its server session
    // or a privacy lock. The Local review itself refuses to replace an occupied source.
    LaunchedEffect(server, aliasScope) {
        retainedAddOwner.handoff.dropIfSessionChanged(server, aliasScope)
        if (server == null) retainedAddOwner.postAdd.clearRemote()
    }
    LaunchedEffect(TransferPrivacyRuntime.lifecycleRevision, TransferPrivacyPolicyStore.revision) {
        // Follow the privacy gate itself, not foreground/background counts: a rotation inside
        // an unlocked window keeps the handoff and the focus; a lock or expiry revokes them.
        val mayAct = { scope: String ->
            RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(scope)
                ?.let { TransferPrivacyRuntime.session.mayAct(it) } == true
        }
        retainedAddOwner.handoff.revokeIfLocked(mayAct)
        retainedAddOwner.postAdd.revokeIfLocked(mayAct)
    }
    // The Local review is composed for Local, for Options, and for Add with Local selected.
    val localReviewVisible = section == Section.LOCAL || section == Section.OPTIONS ||
        (section == Section.ADD_CHOOSE && addDestination == UniversalAddRouteTarget.Local)
    LaunchedEffect(localHandoff, localReviewVisible) {
        if (retainedAddOwner.handoff.arbitrate(localReviewVisible) ==
            AddHandoffState.Arbitration.NAVIGATE_TO_LOCAL) {
            localFromCombined = false
            section = Section.LOCAL
        }
    }

    var createRequest by retainedAddOwner.createReviewRequest
    // External intake adopts one exact delivery. Existing drafts still use
    // the occupied-source review before replacing a selected source.
    LaunchedEffect(incomingDelivery) {
        val delivery = incomingDelivery ?: return@LaunchedEffect
        if (!retainedAddOwner.claimIncoming(delivery)) return@LaunchedEffect
        when (delivery) {
            is ExternalIntakeDelivery.Create -> {
                createRequest = delivery
                toolOrigin = null
                section = Section.CREATE
            }
            is ExternalIntakeDelivery.Add -> {
                releaseVerifiedAttemptsForNewSource()
                sharedTorrentError = null
                pendingSharedDocument = null
                if (incomingTorrent != null) {
                    pendingSharedMagnet = null
                    if (addSourceOccupied()) pendingSharedDocument = incomingTorrent
                    else readSharedDocument(incomingTorrent)
                } else if (sharedMagnet != null) {
                    if (addSourceOccupied(incomingText = sharedMagnet)) {
                        pendingSharedMagnet = sharedMagnet
                    } else {
                        pendingSharedMagnet = null
                        magnet = sharedMagnet
                        retainedAddOwner.handoff.drop()
                        remoteAddSessions.selectMagnet(aliasScope)
                    }
                }
                section = Section.ADD_CHOOSE
            }
        }
    }
    // Android's granted document URI is read only in this process, validated
    // by the existing 2 MiB/bencode intake. A failed replacement keeps the
    // original selected draft and never reaches either Add executor.
    LaunchedEffect(incomingDocumentRead) {
        val document = incomingDocumentRead ?: return@LaunchedEffect
        val witness = IncomingDocumentSelectionWitness(
            text = magnet,
            localDocument = localReviewedTorrent.value,
            remoteHandoff = remoteHandoff()?.bytes,
            remoteSession = aliasScope.takeIf { it.isNotBlank() }
                ?.let(remoteAddSessions::forAccount)
        )
        sharedTorrentError = null
        var cancelledByLifecycle = false
        try {
            val reviewed = withContext(Dispatchers.IO) {
                SharedTorrentDocument.read(context.contentResolver, document)
            }
            val currentRemoteDraft = aliasScope.takeIf { it.isNotBlank() }
                ?.let(remoteAddSessions::forAccount)
            if (incomingDocumentRead == document && witness.canApply(
                    magnet, localReviewedTorrent.value, remoteHandoff()?.bytes,
                    currentRemoteDraft)) {
                retainedAddOwner.adoptSharedDocument(
                    PendingLocalAdd(reviewed.preview, reviewed.bytes, document),
                    forAuthenticatedRemote = addDestination == UniversalAddRouteTarget.RTorrent(aliasScope) &&
                        server != null && RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(aliasScope) != null
                )
            } else if (incomingDocumentRead == document) {
                sharedTorrentError = "The draft changed while reading the shared document. Your newer source was kept."
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // Rotation cancels the old composition's coroutine. The new
            // Activity still owns this RAM-only URI and must get a chance
            // to validate it; cancellation cannot consume that handoff.
            cancelledByLifecycle = true
            throw cancel
        } catch (_: Exception) {
            if (incomingDocumentRead == document) sharedTorrentError =
                "The shared .torrent could not be verified (v1 metainfo, maximum 2 MiB). The previous draft was kept."
        } finally {
            if (shouldConsumeReviewedSourceOnExit(
                    isCurrentDocument = incomingDocumentRead == document,
                    lifecycleCancelled = cancelledByLifecycle)) {
                incomingDocumentRead = null
            }
        }
    }
    // A file shared to "Create torrent" opens the Create review; nothing is read yet.
    val seedingEnvironments = remember(savedProfiles, aliasScope, server, combinedProtocolProfiles) {
        buildList {
            add(SeedingEnvironment.local())
            savedProfiles.forEach { add(SeedingEnvironment.server(it.credentials.endpoint, it.credentials.username)) }
            if (server != null && aliasScope.isNotBlank())
                add(SeedingEnvironment.server(aliasScope.substringBefore('\u0000'), aliasScope.substringAfter('\u0000', "")))
            // Transmission / qBittorrent / Deluge profiles, by their exact client identity.
            combinedProtocolProfiles.forEach {
                add(SeedingEnvironment(io.github.gamesbyian.chunks.seeding.RemoteSeeding.protocolEnvironment(it.id),
                    it.label, server = true))
            }
        }.distinctBy { it.id }
    }
    // On Android Activity recreation SectionRestoreSaver intentionally restores
    // transient CREATE to the safe Transfers shell. An Activity-retained owner
    // can still hold the reviewed source/request: restore only that existing
    // read-only journey, without replaying the ACTION_SEND or creating a job.
    // This effect runs once per new composition, so later deliberate navigation
    // away from Create is not overridden by retained source memory.
    LaunchedEffect(retainedAddOwner) {
        if (NavigationRestorePolicy.resumeRetainedCreateReview(
                restoredSection = section.name,
                incomingCreateAlreadyDispatched = incomingCreate != null,
                retainedRequest = createRequest != null,
                retainedSource = retainedAddOwner.createSource.value != null
            )) section = Section.CREATE
    }

    // The latest readback request a refresh() of THIS composition has covered:
    // a call while loading queues one more read with a fresh ticket.
    val servedReadback = remember { longArrayOf(-1L) }
    val refresh: () -> Unit = {
        servedReadback[0] = remoteSession.readbackRequests.longValue
        val remote = server
        if (remote != null) {
            if (loading) {
                // A mutation might settle during a manual refresh. Queue one
                // additional read rather than silently dropping its readback.
                remoteFence.queueRefresh()
            } else {
                loading = true
                scope.launch {
                    // The read loop lives in RemoteInventoryReader.kt; this host is current state.
                    val host = object : RemoteInventoryHost {
                        override val currentServer get() = server
                        override val viewToRead get() = requestedView
                        override var isLoading: Boolean
                            get() = loading
                            set(value) { loading = value }
                        override fun clearError() { error = null }
                        override fun accept(snapshot: List<io.github.gamesbyian.chunks.rpc.RemoteTransfer>, view: String) {
                            transfers = snapshot
                            transfersObservedAt = System.currentTimeMillis()
                            snapshotRevision++
                            currentView = view
                            stale = false
                            // Previous file inspections belong to the OLD
                            // snapshot and must not arrive after this one.
                            inspectionFence.invalidate()
                            expandedHash = null
                            files = null
                            filesError = null
                            filesLoading = false
                        }
                        override fun markStale() { stale = true }
                        override fun failed(failure: Exception) {
                            stale = true
                            error = readableFailure(failure)
                            loginRefusedError = if (isLoginRefused(failure)) error else null
                        }
                    }
                    readRemoteInventory(host, remote, remoteFence, mutationGate)
                }
            }
        }
    }

    // A2611-02: a composition attaching to a retained stale session, or a
    // mutation settled through a disposed composition's refresh (whose
    // coroutine scope is gone), still gets a fresh read. A request already
    // covered by this composition's refresh() is not read twice; one arriving
    // during a read in flight is queued behind it (that read's ticket predates
    // the settle and cannot release the latch).
    // Observed inside the effect so a settle does not recompose all of ChunksApp.
    // Also reads when a view switch was interrupted by recreation.
    val latestRefresh by androidx.compose.runtime.rememberUpdatedState(refresh)
    LaunchedEffect(server) {
        if (server == null) return@LaunchedEffect
        var attaching = true
        androidx.compose.runtime.snapshotFlow { remoteSession.readbackRequests.longValue }.collect { requested ->
            val interruptedViewSwitch = attaching && currentView != requestedView
            attaching = false
            if ((stale && requested > servedReadback[0]) || interruptedViewSwitch) latestRefresh()
        }
    }

    /** Disconnect does not forget credentials, transfer bytes or Add draft. */
    fun disconnectCurrentRemote(restoreSavedLogin: Boolean = true) {
        // Retire pending automatic restoration BEFORE server becomes null.
        // No Compose effect may turn a deliberate Disconnect into reconnect.
        startupReconnectAttempted = true
        explicitDisconnectSuppressed = true
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
        connectionName = ""
        section = Section.SERVER
        if (!restoreSavedLogin) return
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

    val addTargetIsAuthenticated = addDestination == UniversalAddRouteTarget.RTorrent(aliasScope) &&
        server != null && RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(aliasScope) != null
    val protocolAddProfile = addProtocolTargetId?.let { id ->
        combinedProtocolProfiles.firstOrNull { it.id == id && it.operations != null }
    }
    val addTargetUnavailable = when (addDestination) {
        UniversalAddRouteTarget.Local -> false
        is UniversalAddRouteTarget.RTorrent -> !addTargetIsAuthenticated
        is UniversalAddRouteTarget.Protocol -> protocolAddProfile == null
    }
    val remoteAddLocked = if (server != null && aliasScope.isNotBlank())
        remoteAddSessions.forAccount(aliasScope).alreadyAttempted else false
    val addChoicesEnabled = activeLocalAddAttempt.attemptedTicket == null && !remoteAddLocked
    val verifiedAddRemoteScope = aliasScope.takeIf {
        server != null && RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(it) != null
    }
    val savedAddTarget = savedProfiles.firstOrNull { profile ->
        eligibleUniversalAddRemote(verifiedAddRemoteScope,
            RemoteVerifiedUiIdentity.aliasScopeOf(profile.credentials))
    }
    val otherSavedAddTargets = savedProfiles.filterNot { profile ->
        eligibleUniversalAddRemote(verifiedAddRemoteScope,
            RemoteVerifiedUiIdentity.aliasScopeOf(profile.credentials))
    }.map { UniversalAddSavedDestination(it.id, it.label) }
    val activeAddRemoteName = verifiedAddRemoteScope?.let {
        savedAddTarget?.label?.trim()?.takeIf(String::isNotBlank)
            ?: connectionName.trim().takeIf(String::isNotBlank)
            ?: "rTorrent"
    }
    // Use the same collision-safe short label in Add chips and the frozen
    // remote attempt's status. An alias never authorizes a server operation.
    val activeAddDisplayName = compactUniversalAddTargetLabels(
        activeAddRemoteName, otherSavedAddTargets).first
    // Smart Routing: read the persisted policy off the composition thread
    // whenever Add is shown; suggest from the reviewed source's own names.
    val routingAddVisible = section == Section.ADD_CHOOSE
    var routingPolicy by remember(routingAddVisible) {
        mutableStateOf<io.github.gamesbyian.chunks.model.SmartRoutingPolicyStore.Policy?>(null)
    }
    LaunchedEffect(routingAddVisible) {
        if (!routingAddVisible) return@LaunchedEffect
        routingPolicy = withContext(Dispatchers.IO) {
            (runCatching { io.github.gamesbyian.chunks.model.SmartRoutingPolicyStore(context).read() }.getOrNull()
                as? io.github.gamesbyian.chunks.model.SmartRoutingPolicyStore.Read.Ready)?.policy
        }
    }
    // A configuration change must not let routing override the person's
    // exact client selection for the same retained source.
    var routingAutoAppliedFor by retainedAddOwner.routingAutoAppliedFor
    var routingPersonChoiceFor by retainedAddOwner.routingPersonChoiceFor
    val connectedRTorrentId = io.github.gamesbyian.chunks.model.EnvironmentSnapshots
        .serverIdOfAliasScope(aliasScope)?.value?.takeIf { server != null && verifiedAddRemoteScope != null }
    // A retained file is evidence only while it is the active source.
    // rTorrent can explicitly reselect its reviewed file over the same text.
    val preparingRemoteSource = addTargetIsAuthenticated && remoteHandoff() != null
    val routingRemoteReview = if (addTargetIsAuthenticated && !preparingRemoteSource)
        remoteAddSessions.forAccount(aliasScope) else null
    val routingSource = AddRouting.source(
        preparingRemoteSource = preparingRemoteSource,
        addTargetIsAuthenticated = addTargetIsAuthenticated,
        remoteReviewedFile = routingRemoteReview?.selectedTorrent,
        remoteFileStillCurrent = routingRemoteReview?.let {
            it.useFile && it.selectedBytes != null && it.textAtFileSelection == magnet
        } == true,
        draftBlank = magnet.isBlank(),
        localReviewedFile = localReviewedTorrent.value?.preview)
    val routingText = magnet.takeUnless { preparingRemoteSource || routingSource != null }.orEmpty()
    val (routingMagnetHash, routingMagnetName) = remember(routingText) {
        val parsed = MagnetDraftCheck.inspect(routingText) as? MagnetDraftCheck.Valid
        parsed?.infoHash to parsed?.displayName
    }
    // Capability-aware candidacy: each protocol client's own live session,
    // read off the main thread only while Add is shown and routing is on.
    val routingEnabled = routingAddVisible && routingPolicy?.enabled == true
    val routingSessions = rememberAddRoutingSessions(routingEnabled, combinedProtocolProfiles)
    val routingRTorrent = server.takeIf { connectedRTorrentId != null && !stale }
    val routingRTorrentPlace = rememberRTorrentRoutingDefault(routingEnabled, routingRTorrent)
    val routingResult = remember(routingPolicy, routingSource, routingMagnetName, connectedRTorrentId,
        combinedProtocolProfiles, activeAddRemoteName, routingSessions, routingRTorrent, routingRTorrentPlace,
        localAddStart.value) {
        val candidates = AddRouting.candidates(
            connectedRTorrentId = connectedRTorrentId, rTorrentLabel = activeAddRemoteName,
            rTorrentLive = routingRTorrent != null, rTorrentPlaceKnown = routingRTorrentPlace != null,
            protocolProfiles = combinedProtocolProfiles, protocolSessions = routingSessions,
            magnetSource = routingSource == null, startNow = localAddStart.value)
        io.github.gamesbyian.chunks.model.SmartRoutingAddSuggestion.evaluate(routingPolicy,
            routingSource?.name ?: routingMagnetName,
            routingSource?.files?.map { it.path }.orEmpty(), candidates)
    }
    val routingSuggestion = (routingResult as?
        io.github.gamesbyian.chunks.model.SmartRoutingAddSuggestion.Result.Suggested)?.suggestion
    // The one handler for using a suggestion: the person's tap on the chip, or
    // the explicit opt-in pre-selection below. A target choice is a draft edit.
    fun useRoutingSuggestion() {
        when (val target = AddRouting.targetFor(routingSuggestion?.environmentId, connectedRTorrentId,
            combinedProtocolProfiles, protocolAddAttempt.busy, protocolAddAttempt.profileId)) {
            null -> Unit
            AddRouting.Target.Local -> selectLocalAddTarget()
            AddRouting.Target.AuthenticatedRTorrent -> selectAuthenticatedAddTarget()
            is AddRouting.Target.Protocol -> {
                addDestination = UniversalAddRouteTarget.Protocol(target.profileId)
                section = Section.ADD_CHOOSE
            }
        }
    }
    val routingSourceKey = routingSource?.infoHashV1 ?: routingMagnetHash
    val routingSuggestionIsSelected = AddRouting.isSelected(routingSuggestion?.environmentId,
        addProtocolTargetId, addTargetIsAuthenticated, connectedRTorrentId,
        localSelected = addDestination == UniversalAddRouteTarget.Local)
    LaunchedEffect(routingSuggestion?.environmentId, routingSourceKey, routingPolicy?.autoSelect,
        section, localSnapshotAddReview, addTargetUnavailable) {
        if (section != Section.ADD_CHOOSE || localSnapshotAddReview || addTargetUnavailable)
            return@LaunchedEffect
        if (io.github.gamesbyian.chunks.model.SmartRoutingAddSuggestion.shouldPreselect(
                routingPolicy, routingSuggestion, routingSourceKey, routingAutoAppliedFor, routingPersonChoiceFor,
                routingSuggestionIsSelected,
                addInFlight = protocolAddAttempt.busy || !addChoicesEnabled)) {
            routingAutoAppliedFor = routingSourceKey
            useRoutingSuggestion()
        }
    }
    val addDestinationPicker: @Composable () -> Unit = {
        UniversalAddDestinationPicker(
            addingRemotely = addTargetIsAuthenticated,
            authenticatedRemoteLabel = activeAddRemoteName,
            savedOtherProfiles = otherSavedAddTargets,
            enabled = addChoicesEnabled && !sharedTorrentReading,
            aliasDraft = addAliasDraft.value,
            onAliasChange = { addAliasDraft.value = it },
            sourceName = routingSource?.name ?: routingMagnetName,
            incomingSourceWaiting = pendingSharedMagnet != null || pendingSharedDocument != null,
            canReplaceIncoming = !protocolAddAttempt.busy,
            importingSource = sharedTorrentReading,
            sourceError = sharedTorrentError,
            onAcceptIncoming = {
                val document = pendingSharedDocument
                val incomingText = pendingSharedMagnet
                pendingSharedDocument = null
                pendingSharedMagnet = null
                sharedTorrentError = null
                if (document != null) {
                    readSharedDocument(document)
                } else if (incomingText != null) {
                    // User-authorized replacement, not an automatic Android
                    // handoff. Keep the chosen target, Place and Start.
                    localReviewedTorrent.value = null
                    retainedAddOwner.clearRemoteHandoff()
                    magnet = incomingText
                    remoteAddSessions.selectMagnet(aliasScope)
                }
            },
            onKeepDraft = {
                pendingSharedDocument = null
                pendingSharedMagnet = null
                sharedTorrentError = null
            },
            onLocal = { routingPersonChoiceFor = routingSourceKey; selectLocalAddTarget() },
            onAuthenticatedRemote = { routingPersonChoiceFor = routingSourceKey; selectAuthenticatedAddTarget() },
            protocolDestinations = combinedProtocolProfiles.filter { it.operations != null }
                .map { UniversalAddSavedDestination(it.id, it.label) },
            routingSuggestion = routingSuggestion?.let { it.label to
                (io.github.gamesbyian.chunks.model.SmartRoutingAddSuggestion.kindLabel(it.kind).lowercase() + " rule") },
            routingSuggestionSelected = routingSuggestionIsSelected,
            onUseRoutingSuggestion = { useRoutingSuggestion() },
            selectedProtocolId = addProtocolTargetId,
            localSelected = addDestination == UniversalAddRouteTarget.Local,
            showDraftOptions = !addTargetUnavailable,
            displayNameSupported = when {
                addProtocolTargetId != null -> UniversalAddClientCapabilities.protocol(null).displayName
                addTargetIsAuthenticated -> UniversalAddClientCapabilities.RTORRENT.displayName
                else -> UniversalAddClientCapabilities.LOCAL.displayName
            },
            onProtocol = { id ->
                routingPersonChoiceFor = routingSourceKey
                if (!protocolAddAttempt.busy || protocolAddAttempt.profileId == id) {
                    addDestination = UniversalAddRouteTarget.Protocol(id)
                    section = Section.ADD_CHOOSE
                }
            },
            onConnectSaved = { requested ->
                val profile = savedProfiles.firstOrNull { it.id == requested }
                if (profile != null && !loading) {
                    val requestedScope = RemoteVerifiedUiIdentity.aliasScopeOf(profile.credentials)
                    if (server != null && aliasScope == requestedScope) {
                        // The current authenticated profile already owns the
                        // target; never authenticate or switch it a second time.
                        savedAddReconnect.abandon()
                        selectAuthenticatedAddTarget()
                    } else {
                        // A saved label is not an authenticated Add target. An
                        // already connected different account must be detached,
                        // or Section.SERVER would still display its old server
                        // screen while the new credentials sat in text fields.
                        savedAddReconnect.requestAuthentication(requestedScope)
                        // The one-shot cold-start reconnect may still be
                        // eligible after a manual login. This explicit saved
                        // profile choice must not silently reconnect the
                        // previously remembered (possibly different) account.
                        startupReconnectAttempted = true
                        detachAuthenticatedRemoteSession()
                        endpoint = profile.credentials.endpoint
                        rpcPath = ""
                        username = profile.credentials.username
                        password = profile.credentials.password
                        connectionName = profile.label
                        connectionDraftEdited = true
                        credentialNotice = "Authenticate this saved connection before remote Add. Your Add source is retained."
                        section = Section.SERVER
                    }
                }
            }
        )
    }
    val smartRoutingTargets = remember(savedProfiles, combinedProtocolProfiles) {
        buildList {
            add(SmartRoutingTargetChoice(io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL.value, "Local"))
            savedProfiles.forEach { add(SmartRoutingTargetChoice(it.id, it.label)) }
            combinedProtocolProfiles.forEach { add(SmartRoutingTargetChoice(it.id, it.label)) }
        }.distinctBy { it.environmentId }
    }
    fun viewAddedTransfer(client: io.github.gamesbyian.chunks.model.EnvironmentId, hash: String) {
        addTransferSearch.viewTransfer(hash)
        consumedShowAllEnvironmentsRevision = showAllEnvironmentsRevision
        savedProfileCatalogFocus = client.value
        combinedFromConnections = client != io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL
        combinedFromLocal = !combinedFromConnections
        section = Section.COMBINED
    }
    fun returnFromAdd() {
        section = Section.COMBINED
        combinedFromConnections = false
        returnToCatalogRevision++
        refresh()
    }
    val protocolAddContent: (@Composable () -> Unit)? = if (protocolAddProfile == null) null else {
        val profile = protocolAddProfile
        val savedOwner = savedProtocolProfiles.firstOrNull { it.id == profile.id }
        // The exact client principal's privacy scope; Add never composes for an
        // unverifiable identity, and a locked client asks for authentication first.
        val protocolPrivacyScope = CombinedReadOnlyProtocolAdmission.privacyScope(profile)
        val content: @Composable () -> Unit = {
          GatedProtocolAdd(privacyScope = protocolPrivacyScope,
              clientKind = RemoteProtocolLabels.kind(profile.backend), onBack = ::returnFromAdd) {
            ProtocolAddScreen(
                profileId = profile.id,
                clientLabel = profile.label,
                createOperations = requireNotNull(profile.operations),
                isSourceOwnerCurrent = {
                    section == Section.ADD_CHOOSE &&
                        addDestination == UniversalAddRouteTarget.Protocol(profile.id) &&
                        savedProtocolProfiles.firstOrNull { it.id == profile.id }.let { current ->
                            if (savedOwner != null) current == savedOwner
                            else current == null && transientReadOnlyProfile === profile
                        }
                },
                destinationPicker = addDestinationPicker,
                sourceDraft = addSourceDraft,
                reviewedTorrent = localReviewedTorrent,
                startState = localAddStart,
                artworkState = addArtworkChoices.onProtocol(profile.id),
                attempt = protocolAddAttempt,
                attemptScope = retainedAddOwner.addAttemptScope,
                attemptController = retainedAddOwner.attempts,
                aliasState = addAliasDraft,
                artworkExpandedState = artworkExpanded,
                onBack = ::returnFromAdd,
                externalSourceReading = sharedTorrentReading,
                onViewTransfer = { hash ->
                    viewAddedTransfer(io.github.gamesbyian.chunks.model.EnvironmentId(profile.id), hash)
                }
            )
          }
        }
        content
    }
    val localContent: @Composable (LocalPane) -> Unit = { pane ->
        val connectedRemote = server
        val localRemoteScope = connectedRemote?.let {
            RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(aliasScope)
        }
        LocalTransferPrivacyGate(
            scope = TransferCollectionStore.LOCAL_ENGINE_SCOPE,
            onBack = if (pane == LocalPane.ADD) ::returnFromAdd else ({ leaveLocal() }),
            controlsInContent = true
        ) {
            LocalEngineScreen(
                pane = pane,
                showNavigationFooter = false,
                listScroll = when (pane) {
                    LocalPane.ADD -> localNavigation.localAddScroll
                    LocalPane.OPTIONS -> localNavigation.localOptionsScroll
                    else -> localNavigation.localListScroll
                }, searchState = localNavigation.localSearch, optionsSearchState = localNavigation.localOptionsSearch,
                optionsCategoryState = localNavigation.localOptionsCategory,
                optionsReturnQueryState = localNavigation.localOptionsReturnQuery,
                optionsIndexScrollState = localNavigation.localOptionsIndexScroll,
                magnetDraftState = addSourceDraft,
                addPlaceState = localAddPlace, addFolderState = localAddFolder, addStartState = localAddStart,
                addArtworkState = addArtworkChoices.onLocal(),
                addDestinationPicker = if (pane == LocalPane.ADD) addDestinationPicker else null,
                addArtworkExpandedState = artworkExpanded,
                onLeaveAdd = ::returnFromAdd,
                addAliasState = addAliasDraft,
                externalSourceReading = sharedTorrentReading,
                pendingAddState = localReviewedTorrent,
                localAddAttemptSession = activeLocalAddAttempt,
                addAttemptScope = retainedAddOwner.addAttemptScope,
                addAttemptController = retainedAddOwner.attempts,
                onBack = { leaveLocal() },
                onSeedingRules = { openTool(Section.SEEDING,
                    if (section == Section.OPTIONS) Section.OPTIONS else Section.LOCAL) },
                onMediaSettings = { openTool(Section.MEDIA,
                    if (section == Section.OPTIONS) Section.OPTIONS else Section.LOCAL) },
                onSmartRouting = { openTool(Section.ROUTING,
                    if (section == Section.OPTIONS) Section.OPTIONS else Section.LOCAL) },
                onCreateTorrent = { openTool(Section.CREATE,
                    if (section == Section.OPTIONS) Section.OPTIONS else Section.LOCAL) },
                onZipReview = { openTool(Section.ARCHIVES,
                    if (section == Section.OPTIONS) Section.OPTIONS else Section.LOCAL) },
                onCombined = {
                    localFromCombined = false
                    combinedFromLocal = true
                    savedProfileCatalogFocus = null
                    showAllEnvironmentsRevision++
                    section = Section.COMBINED
                },
                showFirstRunWelcome = savedProfiles.isEmpty() && server == null,
                onConnections = { section = Section.SERVER },
                onOpenAdd = { selectLocalAddTarget() },
                onShowLocalSnapshotReview = {
                    // Select Local for the recovery review WITHOUT routing
                    // through ordinary Add's remote-source handoff.
                    localSnapshotAddReview = true
                    addDestination = UniversalAddRouteTarget.Local
                    section = Section.ADD_CHOOSE
                },
                onOpenOptions = { section = Section.OPTIONS },
                remoteEnvironmentLabel = aliasScope.takeIf { localRemoteScope != null }?.let(::serverEnvironmentLabel),
                onRemotePlaces = if (localRemoteScope != null) ({ section = Section.PLACES }) else null,
                onRemoteOrganize = if (localRemoteScope != null) ({ section = Section.ORGANIZE }) else null,
                onRemoteNames = if (localRemoteScope != null) ({ section = Section.NAMES }) else null,
                onRemoteSnapshot = if (localRemoteScope != null) ({ section = Section.SNAPSHOT }) else null,
                initialMagnet = localHandoff?.magnet,
                initialSearch = localSearchPrefill,
                onSearchConsumed = { localSearchPrefill = null },
                onAcknowledgedAdd = { hash -> retainedAddOwner.postAdd.rememberLocal(localAddedKey(hash)) },
                onViewAddTransfer = { hash ->
                    viewAddedTransfer(io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL, hash)
                },
                returnFocusTool = localReturnFocus, onReturnFocusConsumed = { localReturnFocus = null },
                canReviewOnConnectedServer = connectedRemote != null,
                serverCopy = if (connectedRemote == null || localRemoteScope == null) null
                    else LocalServerCopyLink(runCatching {
                        java.net.URI(aliasScope.substringBefore('\u0000').trim()).host
                    }.getOrNull() ?: "your remote client") { bytes ->
                        val place = connectedRemote.defaultDirectory()
                            ?: error("The remote client reports no default download location")
                        connectedRemote.loadStoppedTorrentBytes(bytes, place, useRemoteDefault = true)
                    },
                moveServer = if (connectedRemote == null || localRemoteScope == null) null
                    else LocalMoveServer(aliasScope) { hash ->
                        TransferMoveObservations.server(connectedRemote, hash)
                    },
                onPrefillConsumed = { retainedAddOwner.handoff.claim(localHandoff) },
                onPrepareRemote = { draft ->
                    prepareRemoteMagnet(draft)
                    if (connectedRemote == null) section = Section.TRANSFERS
                    else selectAuthenticatedAddTarget()
                },
                onPrepareRemoteTorrent = { document, reviewed ->
                    val staged = prepareRemoteTorrent(document, reviewed)
                    if (staged) {
                        if (connectedRemote == null) section = Section.TRANSFERS
                        else selectAuthenticatedAddTarget()
                    }
                    staged
                }
            )
        }
    }
    val remoteAddContent: @Composable () -> Unit = remoteAdd@{
        val connectedRemote = server ?: return@remoteAdd
        val exactScope = aliasScope
        val privacyScope = RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(exactScope)
            ?: return@remoteAdd
        RemoteTransferPrivacyGate(scope = privacyScope, onBack = ::returnFromAdd, controlsInContent = true) {
            fun canBeginRemoteAdd(): Boolean =
                server === connectedRemote && aliasScope == exactScope &&
                    section == Section.ADD_CHOOSE &&
                    addDestination == UniversalAddRouteTarget.RTorrent(exactScope) &&
                    !loading && !stale && mutationGate.canStart
            val beginRemoteMutation: () -> Boolean = {
                if (canBeginRemoteAdd() && mutationGate.tryStart()) {
                    stale = true
                    true
                } else false
            }
            val settledRemoteMutation: () -> Unit = {
                if (server === connectedRemote && aliasScope == exactScope) {
                    mutationGate.settled()
                    remoteSession.requestReadback()
                    stale = true
                    refresh()
                }
            }
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
            val initialRemoteHandoff = remoteHandoff()
            fun currentRemoteDraft() = server === connectedRemote && aliasScope == exactScope &&
                addDestination == UniversalAddRouteTarget.RTorrent(exactScope) && section == Section.ADD_CHOOSE
            VerifiedAddScreen(
                remote = connectedRemote, magnet = magnet, destination = addSession.destination,
                session = addSession,
                isSourceOwnerCurrent = ::currentRemoteDraft,
                // Configuration-retained owner, never a disposable
                // Composable/Activity scope: rotating the device or
                // returning to Transfers cannot cancel a sent Add.
                // Process death remains uncertain and never auto-retries.
                attemptScope = retainedAddOwner.addAttemptScope,
                sharedAttemptController = retainedAddOwner.attempts,
                observedPlaces = observedPlaces,
                initialTorrent = initialRemoteHandoff?.bytes,
                initialTorrentDocument = initialRemoteHandoff?.originalDocument,
                claimInitialTorrent = {
                    currentRemoteDraft() && retainedAddOwner.claimRemoteHandoff(initialRemoteHandoff)
                },
                onNextDraftStarted = retainedAddOwner::retireCompletedRemoteReview,
                // The newest explicit server-form .torrent replaces any older local
                // review, so a target switch can never resurrect the superseded file.
                onTorrentAdopted = { preview, bytes, document, fromHandoff ->
                    if (currentRemoteDraft() &&
                        (remoteHandoff() == null || remoteHandoff() === initialRemoteHandoff)) {
                        val reviewed = initialRemoteHandoff?.reviewed?.takeIf {
                            fromHandoff && localReviewedTorrent.value === it && it.stillMatchesReviewedSource(bytes)
                        } ?: PendingLocalAdd(preview, bytes.copyOf(), document)
                        localReviewedTorrent.value = reviewed
                        addSession.associatedLocalReview = reviewed
                    }
                },
                finishInOrderScope = aliasScope.takeIf { it.isNotBlank() },
                environmentLabel = activeAddDisplayName,
                onMagnet = { if (currentRemoteDraft()) magnet = it },
                onDestination = { addSession.destination = it },
                addStartState = localAddStart,
                addArtworkState = addArtworkChoices.onRemoteClient(aliasScope),
                addDestinationPicker = addDestinationPicker,
                externalSourceReading = sharedTorrentReading,
                externalSourceWaiting = pendingSharedDocument != null || pendingSharedMagnet != null,
                addAliasState = addAliasDraft,
                onVerifiedAlias = { hash, value ->
                    // The frozen coordinator confirms a new exact job,
                    // and a stale account/route cannot receive its label.
                    val ticket = addSession.attemptTicket
                    if (server !== connectedRemote ||
                        aliasScope != addSession.accountScope ||
                        ticket?.intent?.source?.expectedInfoHash?.equals(hash, true) != true) false
                    else verifiedAliasStore.saveVerifiedNewAddAlias(aliasScope, hash, value)
                },
                onBack = ::returnFromAdd,
                addArtworkExpandedState = artworkExpanded,
                onComplete = refresh,
                onVerifiedAdd = { hash ->
                    if (server === connectedRemote) {
                        retainedAddOwner.postAdd.rememberRemote(connectedRemote, RemoteAddedRecord(
                            aliasScope, hash, RemoteAddedRecord.viewKey(query, filter.name)))
                    }
                },
                onViewVerifiedTransfer = { hash ->
                    if (server === connectedRemote && aliasScope == addSession.accountScope) {
                        io.github.gamesbyian.chunks.model.EnvironmentSnapshots.serverIdOfAliasScope(aliasScope)
                            ?.let { viewAddedTransfer(it, hash) }
                    }
                },
                onMutationStarted = beginRemoteMutation,
                onMutationSettled = settledRemoteMutation,
                mutationReady = canBeginRemoteAdd(),
                recentPlaces = recentPlaces,
                savedDefaultPlace = remember(aliasScope, recentPlaceRevision) {
                    if (aliasScope.isBlank()) null
                    else runCatching { recentPlaceStore.savedDefault(aliasScope) }.getOrNull()
                },
                onSaveDefaultPlace = { path ->
                    val saved = aliasScope.isNotBlank() && runCatching {
                        recentPlaceStore.setSavedDefault(aliasScope, path)
                    }.getOrDefault(false)
                    if (saved) recentPlaceRevision++
                    saved
                },
                onCustomPlaceVerified = { path ->
                    val exactAccount = server === connectedRemote && aliasScope == addSession.accountScope
                    val saved = exactAccount && runCatching {
                        recentPlaceStore.recordVerifiedCustom(aliasScope, path)
                    }.getOrDefault(false)
                    if (saved) recentPlaceRevision++
                    saved
                }
            )
        }
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

        val policyLifecycle = LocalLifecycleOwner.current.lifecycle
        fun policiesMayRun() = policyLifecycle.currentStateFlow.value.isAtLeast(Lifecycle.State.STARTED) &&
            credentialChanges.isSettled()

        // The authenticated rTorrent owner shares the manual mutation gate.
        // The loop itself lives in RemotePolicyLoop.kt; this host exposes only current state.
        LaunchedEffect(server, aliasScope) {
            val connectedRemote = server ?: return@LaunchedEffect
            val expectedScope = aliasScope
            val privacyScope = RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(expectedScope)
                ?: return@LaunchedEffect
            val host = object : RemotePolicyHost {
                override val currentServer get() = server
                override val currentAliasScope get() = aliasScope
                override val isLoading get() = loading
                override var isStale: Boolean
                    get() = stale
                    set(value) { stale = value }
                override val currentTransfers get() = transfers
                override val connectInFlight get() = connectJob?.isActive == true
                override val mutationGate get() = remoteSession.mutationGate
                override fun mayRun() = policiesMayRun()
                override fun requestReadback() = remoteSession.requestReadback()
                override fun refresh() = latestRefresh()
            }
            runRemotePolicyLoop(context, host, connectedRemote, expectedScope, privacyScope)
        }
        // A typed policy session is reusable only for the exact roster and
        // authentication generation that admitted it. The loop lives in
        // ProtocolPolicyLoop.kt; this host exposes only current state.
        LaunchedEffect(savedProtocolProfiles, transientReadOnlyProfile) {
            val host = object : ProtocolPolicyHost {
                override fun savedProfiles() = savedProtocolProfiles
                override fun transientProfile() = transientReadOnlyProfile
                override val connectInFlight get() = readOnlyConnectJob?.isActive == true
                override fun mayRun() = policiesMayRun()
            }
            runProtocolPolicyLoop(
                context, host, combinedProtocolProfiles,
                expectedSaved = savedProtocolProfiles.associateBy { it.id },
                expectedTransient = transientReadOnlyProfile
            )
        }
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
        } else if (section == Section.COMBINED) {
            val addController = retainedAddOwner.attempts
            val localId = io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL
            val remoteId = connectedRTorrentId?.let { io.github.gamesbyian.chunks.model.EnvironmentId(it) }
            val addReceipts = buildList {
                val localSubmitting = addController.isSubmitting(localId)
                addController.observationRevision(localId)
                activeLocalAddAttempt.coordinator.transferReceipt(localId, "Local", localSubmitting,
                    activeLocalAddAttempt.registrationFromThisSubmission)?.let(::add)
                if (remoteId != null) remoteAddSessions.existingForAccount(aliasScope)?.let { attempt ->
                    addController.observationRevision(remoteId)
                    val submitting = attempt.submitting || addController.isSubmitting(remoteId)
                    attempt.coordinator.transferReceipt(remoteId,
                        attempt.submittedDisplayName ?: activeAddDisplayName ?: "rTorrent", submitting)?.let(::add)
                }
                // Observe the attempt's Compose state even when an exact profile is not selected.
                val protocolBusy = protocolAddAttempt.busy
                val profileId = protocolAddAttempt.profileId
                if (profileId != null) {
                    val profile = combinedProtocolProfiles.singleOrNull { it.id == profileId }
                    if (profile != null) {
                        addController.observationRevision(profile.environment)
                        val submitting = protocolBusy || addController.isSubmitting(profile.environment)
                        protocolAddAttempt.coordinator(profileId)
                            .transferReceipt(profile.environment, profile.label, submitting)?.let(::add)
                    }
                }
            }
            CombinedTransfersScreen(
                showNavigationFooter = false,
                scopeFocusRequest = savedProfileCatalogFocus,
                onScopeFocusConsumed = { savedProfileCatalogFocus = null },
                returnToCatalogRevision = returnToCatalogRevision,
                onDetailVisibilityChanged = { combinedDetailsVisible = it },
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
                hasRememberedRemoteLogin = remembered != null || savedProtocolProfiles.isNotEmpty(),
                // The existing Add destination remains the sole admission
                // coordinator until source-first Universal Add is integrated.
                onWelcomeAdd = { section = Section.ADD_CHOOSE },
                listState = combinedListState,
                addReceipts = addReceipts,
                onReviewAdd = { environment ->
                    when {
                        environment == localId -> selectLocalAddTarget()
                        environment == remoteId -> selectAuthenticatedAddTarget()
                        combinedProtocolProfiles.any { it.environment == environment && it.operations != null } -> {
                            addDestination = UniversalAddRouteTarget.Protocol(environment.value)
                            section = Section.ADD_CHOOSE
                        }
                    }
                },
                searchState = combinedSearch,
                initialAddSearch = addTransferSearch.displayQuery(combinedSearch.value),
                onManualSearchEdited = addTransferSearch::clear,
                savedListingsState = combinedSavedListings,
                // Only this session's positively verified Add on the current account.
                recentlyAddedTransfer = retainedAddOwner.postAdd.latestFor(server, aliasScope),
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
                                    remoteSession.requestReadback()
                                    stale = true
                                    refresh()
                                }
                            },
                            setStarted = remote::setStarted,
                            probe = remote::probe,
                            inspectStall = { hash ->
                                io.github.gamesbyian.chunks.rpc.RemoteStallInspector.inspect(remote, hash)
                            },
                            ownerSession = remote),
                        authenticatedSession = remote,
                        snapshotRevision = snapshotRevision,
                        refresh = { if (server === remote) refresh() })
                },
                savedProfiles = savedProfiles.map { profile ->
                    CombinedSavedProfile(profile.id, profile.label + " · " + profile.address,
                        aliasScope = RemoteVerifiedUiIdentity.aliasScopeOf(profile.credentials),
                        // Read-only "main" list with this profile's own saved credentials (HTTPS enforced).
                        listOnly = { verifiedSavedProfileMainInventory(profile.credentials) },
                        shortLabel = profile.label)
                },
                protocolProfiles = combinedProtocolProfiles,
                protocolOwnerCurrent = run {
                    val expectedSaved = savedProtocolProfiles.associateBy { it.id }
                    val expectedTransient = transientReadOnlyProfile
                    val checkOwner: (CombinedReadOnlyProtocolProfile) -> Boolean = { candidate ->
                        section == Section.COMBINED &&
                            savedProtocolProfiles.firstOrNull { it.id == candidate.id }.let { current ->
                                val expected = expectedSaved[candidate.id]
                                if (expected != null) current == expected &&
                                    candidate.verifiedAtMillis == expected.lastVerifiedAtMillis
                                else current == null && candidate === expectedTransient &&
                                    transientReadOnlyProfile === expectedTransient
                            }
                    }
                    checkOwner
                },
                onOpenLocal = { localFromCombined = true; section = Section.LOCAL },
                // Opening the local device inventory is non-mutating. Only the explicit
                // Add action hands off the pending magnet to its review.
                onAddToLocal = addLocal@{
                    val selectedDocument = incomingTorrent
                    if (selectedDocument != null && !sharedTorrentReading) {
                        if (!retainedAddOwner.claimIncoming(incomingDelivery)) return@addLocal
                        readSharedDocument(selectedDocument)
                    } else if (selectedDocument == null) {
                        pendingSharedMagnet?.let { source ->
                            retainedAddOwner.handoff.stage(source)
                            pendingSharedMagnet = null
                        }
                    }
                    // The existing local review reads only an explicitly
                    // selected URI, under its own independent privacy gate.
                    localFromCombined = true
                    selectLocalAddTarget()
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
                onAddToRemoteClient = if (server != null) (addRemote@{
                    val sharedDocument = incomingTorrent
                    if (sharedDocument != null && !sharedTorrentReading) {
                        if (RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(aliasScope) != null) {
                            // The same retained reader validates the document and only
                            // adopts it if this exact draft hasn't since changed.
                            if (!retainedAddOwner.claimIncoming(incomingDelivery)) return@addRemote
                            addDestination = UniversalAddRouteTarget.RTorrent(aliasScope)
                            readSharedDocument(sharedDocument)
                            section = Section.ADD_CHOOSE
                        } else {
                            sharedTorrentError =
                                "This rTorrent account is no longer verified. Reconnect before reviewing this .torrent."
                        }
                    } else if (sharedDocument == null) {
                        pendingSharedMagnet?.let { source ->
                            prepareRemoteMagnet(source)
                            pendingSharedMagnet = null
                        }
                        selectAuthenticatedAddTarget()
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
                        ExactOwningListNavigation.Route.LOCAL -> {
                            localSearchPrefill = key.infoHash
                            localFromCombined = true
                            section = Section.LOCAL
                        }
                        ExactOwningListNavigation.Route.AUTHENTICATED_SERVER -> {
                            // The owner link is a view scope in the SAME canonical
                            // browser, never a route back to the retired server list.
                            consumedShowAllEnvironmentsRevision = showAllEnvironmentsRevision
                            savedProfileCatalogFocus = key.environment.value
                            // Combined may show a full "main" inventory while
                            // the signed-in screen is still on a filtered view.
                            // The safe read-only refresh is queued if a read is
                            // already active; no mutation or implicit retry.
                            ExactOwningListNavigation.requestMainIfNeeded(
                                currentView, requestedView,
                                setRequestedView = { requestedView = it },
                                refresh = refresh
                            )
                            section = Section.COMBINED
                        }
                        ExactOwningListNavigation.Route.UNAVAILABLE -> Unit
                    }
                },
                onBack = {
                    // An explicit saved-profile deep link must return to
                    // Connections, not the old local/server-specific lists.
                    val returnToConnections = combinedFromConnections
                    combinedFromConnections = false
                    val returnToLocal = combinedFromLocal || server == null
                    localFromCombined = false
                    section = when {
                        returnToConnections -> Section.SERVER
                        returnToLocal -> Section.LOCAL
                        else -> Section.TRANSFERS
                    }
                }
            )
        } else if (section == Section.SEEDING) {
            SeedingRulesScreen(seedingEnvironments, onBack = { leaveTool() })
        } else if (section == Section.MEDIA) {
            MediaSettingsScreen(onBack = { leaveTool() })
        } else if (section == Section.ROUTING) {
            SmartRoutingSettingsScreen(smartRoutingTargets, onBack = { leaveTool() })
        } else if (section == Section.CREATE) {
            val shownCreateRequest = createRequest
            io.github.gamesbyian.chunks.create.CreateTorrentScreen(
                onBack = {
                    createRequest = null
                    retainedAddOwner.createSource.value = null
                    leaveTool()
                },
                shared = shownCreateRequest,
                // An older source scan may settle after a newer Share. Its
                // acknowledgement must never consume that newer request.
                onSharedConsumed = {
                    if (createRequest === shownCreateRequest) createRequest = null
                },
                retainedSourceState = retainedAddOwner.createSource
            )
        } else if (section in setOf(Section.ADD_CHOOSE, Section.LOCAL, Section.OPTIONS)) {
            UniversalAddRoute(
                target = if (section == Section.ADD_CHOOSE) addDestination else UniversalAddRouteTarget.Local,
                localPane = when (section) {
                    Section.ADD_CHOOSE -> LocalPane.ADD
                    Section.OPTIONS -> LocalPane.OPTIONS
                    else -> LocalPane.TRANSFERS
                },
                local = localContent,
                remote = if (addTargetIsAuthenticated) remoteAddContent else null,
                protocol = protocolAddContent,
                destinationPicker = addDestinationPicker,
                onBack = ::returnFromAdd
            )
        } else if (server == null || section == Section.SERVER) {
            ConnectionScreen(
                loginRequested = savedAddReconnect.awaitingAuthentication,
                connectedSessionName = server?.let {
                    savedProfiles.firstOrNull { profile ->
                        profile.id == io.github.gamesbyian.chunks.model.EnvironmentSnapshots
                            .serverIdOfAliasScope(aliasScope)?.value
                    }?.label ?: "Connected rTorrent client"
                },
                connectedProfileId = server?.let { io.github.gamesbyian.chunks.model.EnvironmentSnapshots
                    .serverIdOfAliasScope(aliasScope)?.value },
                connectedSessionVerified = !stale && error == null,
                connectedVersion = version,
                remoteForDefaultFolder = server,
                savedDefaultFolder = remember(aliasScope, recentPlaceRevision) {
                    if (aliasScope.isBlank()) null
                    else runCatching { recentPlaceStore.savedDefault(aliasScope) }.getOrNull()
                },
                onSaveDefaultFolder = { newDefault ->
                    val saved = aliasScope.isNotBlank() && server != null && runCatching {
                        recentPlaceStore.setSavedDefault(aliasScope, newDefault)
                    }.getOrDefault(false)
                    if (saved) recentPlaceRevision++
                    saved
                },
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
                onAddConnection = {
                    // Retire only the UI session; saved profiles and server jobs remain intact.
                    // Suppress the ordinary Disconnect credential refill for a NEW profile.
                    savedAddReconnect.abandon()
                    disconnectCurrentRemote(restoreSavedLogin = false)
                    endpoint = ""
                    rpcPath = ""
                    username = ""
                    password = ""
                    connectionName = ""
                    connectionDraftEdited = true
                    credentialNotice = null
                    connectDiagnostics = ConnectionDiagnostics()
                    readOnlyConnectJob?.cancel()
                    readOnlyConnectJob = null
                    readOnlyBackendError = null
                    delugeDaemonHostId = ""
                    chosenBackend = RemoteBackendKind.RTORRENT
                    verifiedReadOnlyProfileId = null
                    transientReadOnlyProfile = null
                },
                startupReconnectEligible = RemoteStartupReconnectPolicy.shouldAttempt(
                    rememberingEnabled = rememberLogin,
                    hasSavedVerifiedLogin = remembered != null,
                    alreadyAttempted = startupReconnectAttempted ||
                        explicitDisconnectSuppressed || connectionDraftEdited
                ),
                onStartupReconnectConsumed = { startupReconnectAttempted = true },
                endpoint = endpoint, username = username, password = password,
                connectionName = connectionName, onConnectionName = {
                    connectionName = it
                    connectionDraftEdited = true
                },
                rpcPath = rpcPath, onRpcPath = {
                    if (rpcPath != it) password = ""
                    rpcPath = it
                    connectionDraftEdited = true
                },
                error = error, loading = loading,
                diagnostics = connectDiagnostics,
                onCancelConnect = {
                    // A cancelled explicit Add-target login cannot route a
                    // future unrelated successful Connect back into Add.
                    savedAddReconnect.abandon()
                    // Discovery is read-only, so abandoning it changes nothing.
                    connectJob?.cancel()
                    readOnlyConnectJob?.cancel()
                    remoteFence.invalidate()
                    RemoteCredentialLock.invalidate()
                    returnFromStartupConnectToCanonical = false
                    loading = false
                    connectDiagnostics = ConnectionDiagnostics()
                    error = "Connection check cancelled. Nothing was changed on the remote client."
                    readOnlyBackendError = "Connection check cancelled. Nothing was changed."
                },
                rememberLogin = rememberLogin, savedLoginPresent = savedLoginPresent,
                savedProfiles = savedProfiles, credentialNotice = credentialNotice,
                enableAdditionalProtocols = true,
                chosenBackend = chosenBackend,
                onChosenBackend = { kind ->
                    if (!loading && kind != chosenBackend) {
                        // Switching type abandons any in-flight check and its
                        // right to save; a typed password never crosses types.
                        readOnlyConnectJob?.cancel()
                        RemoteCredentialLock.invalidate()
                        chosenBackend = kind
                        password = ""
                        delugeDaemonHostId = ""
                        readOnlyBackendError = null
                    }
                },
                delugeDaemonHostId = delugeDaemonHostId,
                onDelugeDaemonHostId = { delugeDaemonHostId = it; readOnlyBackendError = null },
                readOnlyBackendError = readOnlyBackendError,
                onConnectReadOnlyBackend = {
                    if (!loading && server == null && chosenBackend != RemoteBackendKind.RTORRENT) {
                        // Freeze the exact type, address, account, secret, daemon,
                        // label and remember choice before any suspension.
                        val kind = chosenBackend
                        val draftEndpoint = endpoint
                        val draftUser = username
                        val draftPassword = password
                        val draftDaemon = delugeDaemonHostId
                        val saveThisLogin = rememberLogin
                        val label = connectionName.trim()
                            .takeIf { RememberedRemoteProtocolProfiles.validLabel(it) }
                        loading = true
                        readOnlyBackendError = null
                        credentialNotice = null
                        RemoteCredentialLock.invalidate()
                        readOnlyConnectJob?.cancel()
                        var job: kotlinx.coroutines.Job? = null
                        job = scope.launch {
                            try {
                                credentialChanges.awaitSettled()
                                val uiRevision = credentialChanges.snapshotRevision()
                                val credentialTicket = RemoteCredentialLock.beginConnection()
                                val verified = withContext(Dispatchers.IO) {
                                    kotlinx.coroutines.withTimeout(60_000) {
                                        RemoteProtocolConnect.connect(kind, draftEndpoint, draftUser,
                                            draftPassword, draftDaemon)
                                    }
                                }
                                if (readOnlyConnectJob !== job || !credentialChanges.isLatest(uiRevision)) return@launch
                                val config = verified.configuration
                                val saved = saveThisLogin && withContext(Dispatchers.IO) {
                                    runCatching {
                                        protocolProfileRoster.saveVerified(kind, config.credentials,
                                            config.delugeDaemonHostId, verified.read.snapshot,
                                            verified.read.capabilities, credentialTicket, label)
                                    }.getOrDefault(false)
                                }
                                val roster = if (saved) withContext(Dispatchers.IO) { protocolProfileRoster.list() } else null
                                // A Forget/remember change or newer Connect during IO
                                // owns the UI; a late result cannot resurrect anything.
                                if (readOnlyConnectJob !== job || !credentialChanges.isLatest(uiRevision)) return@launch
                                val id = verified.read.environment.value
                                if (roster != null && roster.any { it.id == id }) {
                                    savedProtocolProfiles = roster
                                    transientReadOnlyProfile = null
                                } else {
                                    transientReadOnlyProfile = RemoteProtocolCombinedProfiles.transient(
                                        config, verified.read,
                                        label ?: (RemoteProtocolLabels.kind(kind) + " @ " +
                                            (runCatching { java.net.URI(config.credentials.endpoint).host }
                                                .getOrNull() ?: "remote client")).take(100))
                                }
                                verifiedReadOnlyProfileId = id
                                endpoint = config.credentials.endpoint
                                password = ""
                                delugeDaemonHostId = config.delugeDaemonHostId.orEmpty()
                                savedLoginPresent = vault.hasSavedLogin() || savedProfiles.isNotEmpty() ||
                                    savedProtocolProfiles.isNotEmpty()
                                credentialNotice = when {
                                    saved -> null
                                    saveThisLogin -> "Connected, but this login could not be saved. " +
                                        "It stays available until you close Chunks."
                                    else -> "Connected without remembering credentials."
                                }
                                consumedShowAllEnvironmentsRevision = showAllEnvironmentsRevision
                                savedProfileCatalogFocus = id
                                combinedFromConnections = true
                                section = Section.COMBINED
                            } catch (cancel: kotlinx.coroutines.CancellationException) {
                                if (cancel is kotlinx.coroutines.TimeoutCancellationException &&
                                    readOnlyConnectJob === job) {
                                    readOnlyBackendError = "The client did not answer in time. Nothing was changed."
                                } else throw cancel
                            } catch (failure: Exception) {
                                if (readOnlyConnectJob === job) readOnlyBackendError = RemoteProtocolConnect.explain(failure)
                            } finally {
                                if (readOnlyConnectJob === job) loading = false
                            }
                        }
                        readOnlyConnectJob = job
                    }
                },
                readOnlySavedProfiles = savedProtocolProfiles,
                readOnlyConnectedProfileId = verifiedReadOnlyProfileId,
                onViewReadOnlyProfile = { profile ->
                    // The immutable typed client ID selects only a read-only
                    // Combined view. No auth secret, Add or command is handed off.
                    consumedShowAllEnvironmentsRevision = showAllEnvironmentsRevision
                    savedProfileCatalogFocus = profile.id
                    combinedFromConnections = true
                    section = Section.COMBINED
                },
                onRenameReadOnlyProfile = { profile, newLabel ->
                    val saved = withContext(Dispatchers.IO) {
                        runCatching { protocolProfileRoster.rename(profile.id, newLabel) }.getOrDefault(false)
                    }
                    if (saved) {
                        savedProtocolProfiles = withContext(Dispatchers.IO) { protocolProfileRoster.list() }
                    }
                    saved
                },
                onForgetReadOnlyProfile = { profile ->
                    RemoteCredentialLock.invalidate()
                    // Hide immediately. A late background inventory may not
                    // resurrect a just-forgotten account during its IO write.
                    savedProtocolProfiles = savedProtocolProfiles.filterNot { it.id == profile.id }
                    if (transientReadOnlyProfile?.id == profile.id) transientReadOnlyProfile = null
                    if (verifiedReadOnlyProfileId == profile.id) verifiedReadOnlyProfileId = null
                    credentialChanges.submit(scope) { revision ->
                        val deleted = withContext(Dispatchers.IO) {
                            runCatching { protocolProfileRoster.forget(profile.id) }.getOrDefault(false)
                        }
                        val remaining = withContext(Dispatchers.IO) { protocolProfileRoster.list() }
                        if (credentialChanges.isLatest(revision)) {
                            savedProtocolProfiles = remaining
                            savedLoginPresent = vault.hasSavedLogin() || savedProfiles.isNotEmpty() ||
                                remaining.isNotEmpty()
                            credentialNotice = if (deleted) "Saved connection forgotten on this device."
                                else "Could not confirm credential removal. Review saved connections."
                        }
                    }
                },
                onSelectProfile = { profile ->
                    // Explicitly choosing another saved account abandons an
                    // older Add-return promise. Re-selecting the same account
                    // while editing its login leaves that draft intact.
                    savedAddReconnect.abandonIfDifferent(
                        RemoteVerifiedUiIdentity.aliasScopeOf(profile.credentials))
                    chosenBackend = RemoteBackendKind.RTORRENT
                    delugeDaemonHostId = ""
                    readOnlyBackendError = null
                    verifiedReadOnlyProfileId = null
                    endpoint = profile.credentials.endpoint
                    rpcPath = ""
                    username = profile.credentials.username
                    password = profile.credentials.password
                    connectionName = profile.label
                    connectionDraftEdited = true
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
                onLocalEngine = {
                    savedAddReconnect.abandon()
                    localFromCombined = false
                    section = Section.LOCAL
                },
                onCreateTorrent = { openTool(Section.CREATE, Section.SERVER) },
                onEndpoint = {
                    if (endpoint != it) password = ""
                    endpoint = it
                    connectionDraftEdited = true
                }, onUsername = {
                    if (username != it) password = ""
                    username = it
                    connectionDraftEdited = true
                },
                onPassword = { password = it; connectionDraftEdited = true },
                onConnect = { automatic ->
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
                        val preferredConnectionName = connectionName.trim()
                            .takeIf { it.isNotEmpty() && !automatic }
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
                                    // A local-prepared magnet may have waited through login.
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
                                    // Only an authenticated, positively verified
                                    // manual/new session clears deliberate Disconnect.
                                    // A failed login cannot re-enable auto retry.
                                    explicitDisconnectSuppressed = false
                                    stale = false
                                    // A local-origin .torrent can be prepared before a
                                    // remote login. Keep its reviewed Add draft visible
                                    // after connecting, without submitting any RPC.
                                    val startupReturn = automatic && returnFromStartupConnectToCanonical &&
                                        section == Section.SERVER
                                    returnFromStartupConnectToCanonical = false
                                    // A saved-profile choice in Universal Add resumes only after THIS
                                    // exact route/principal is authenticated; no RPC is sent.
                                    when {
                                        savedAddReconnect.consumeAuthenticated(aliasScope) ->
                                            selectAuthenticatedAddTarget()
                                        startupReturn -> {
                                            // Complete the delegated reconnect without narrowing
                                            // the person's saved Transfers scope to this server.
                                            consumedShowAllEnvironmentsRevision = showAllEnvironmentsRevision
                                            combinedFromConnections = true
                                            section = Section.COMBINED
                                        }
                                        !automatic -> {
                                            section = Section.SERVER
                                            val verifiedId = io.github.gamesbyian.chunks.model
                                                .EnvironmentSnapshots.serverIdOfAliasScope(aliasScope)?.value
                                            verifiedConnectionNotice = VerifiedConnectionNotice(
                                                preferredConnectionName
                                                    ?: savedProfiles.firstOrNull { it.id == verifiedId }?.label
                                                    ?: RememberedRemoteProfileRoster.addressOf(verifiedSettings.endpoint),
                                                saved = null // persistence has not been observed yet
                                            )
                                        }
                                        // A startup read completing after explicit navigation
                                        // updates the session without moving the current screen.
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
                                                    credentialTicket, preferredConnectionName
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
                                            // Only a confirmed paired save makes this draft safe
                                            // to refill/reconnect after recreation. A full roster,
                                            // failed vault commit or in-flight IO may leave the
                                            // previous account in the vault.
                                            connectionDraftEdited = !(retained && saveThisLogin)
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
            if (remotePrivacyScope == null) {
                // Never present a connected rTorrent queue with an ambiguous
                // account identity; scope policies could otherwise be bypassed.
                Text("Cannot verify which rTorrent profile owns these transfers. " +
                    "Go to the local device client or reconnect securely.",
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
                    remoteSession.requestReadback()
                    stale = true
                    refresh()
                }
            }
            // Reuse the already-authorized destinations. No search-created
            // settings screen, saved login or implicit server Add target.
            val optionsDestinations = Section.entries.filter {
                it != Section.NAMES && it != Section.SNAPSHOT &&
                    it != Section.SEEDING && it != Section.SNAPSHOT_INSPECT &&
                    it != Section.COMBINED
            }
            val visibleDestinations = if (section == Section.TRANSFERS && optionsOpen)
                optionsDestinations.filter { optionsDestinationMatches(it, optionsSearch) }
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
                                    OptionsShortcut.LOCAL_SETTINGS -> { localFromCombined = false; section = Section.LOCAL }
                                    OptionsShortcut.ENVIRONMENT_COLOURS -> { combinedFromLocal = false; section = Section.COMBINED }
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
                    addedRecord = retainedAddOwner.postAdd.remoteFor(server, aliasScope),
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
                        combinedFromLocal = false
                        savedProfileCatalogFocus = null
                        showAllEnvironmentsRevision++
                        section = Section.COMBINED
                    },
                    onRefresh = refresh, onAdd = { selectAuthenticatedAddTarget() },
                    onConnections = { section = Section.SERVER },
                    showNavigationFooter = false,
                    optionsOpen = optionsOpen,
                    onOptions = { optionsOpen = !optionsOpen; if (!optionsOpen) optionsSearch = "" },
                    onReviewNames = { section = Section.NAMES },
                    onExportSnapshot = { section = Section.SNAPSHOT },
                    onInspectSnapshot = { section = Section.SNAPSHOT_INSPECT },
                    onPrepareLocal = { source ->
                        val prepared = TorrentIdentityHandoff.prepare(source.hash, source.name)
                        if (prepared != null) {
                            // The lookup belongs to the app-owned scope and the exact live session,
                            // not this composition: a configuration change keeps it, and
                            // disconnect, forget, privacy lock or a different account retires it.
                            retainedAddOwner.handoff.prepare(
                                scope = retainedAddOwner.addAttemptScope,
                                stamp = RemoteSessionStamp(connectedRemote, aliasScope),
                                currentSession = { remoteSession.server.value to remoteSession.aliasScope.value },
                                mayAct = { scope ->
                                    RemoteTransferPrivacyScope.fromAuthenticatedAliasScope(scope)
                                        ?.let { TransferPrivacyRuntime.session.mayAct(it) } == true
                                }
                            ) {
                                // Public trackers let the local device find peers even before the
                                // server finishes. Private torrents: identity only, and their
                                // passkey-bearing tracker URLs are never read. A failed lookup
                                // still hands off the identity.
                                val trackers = try {
                                    (connectedRemote.readPublicTrackers(source.hash)
                                        as? RTorrentRemote.PublicTrackers.Urls)?.urls.orEmpty()
                                } catch (cancel: kotlinx.coroutines.CancellationException) {
                                    throw cancel
                                } catch (_: Exception) { emptyList() }
                                PublicTrackerHandoff.withTrackers(prepared, trackers)
                            }
                        }
                    },
                    onCopyToLocal = { magnet ->
                        retainedAddOwner.handoff.stage(
                            magnet, RemoteSessionStamp(connectedRemote, aliasScope))
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
                Section.SNAPSHOT -> {
                val snapshotAccount = server
                val inventoryRevision = snapshotRevision
                // The selected Transfers view may be filtered. Compare a backup
                // against an independently verified COMPLETE rTorrent session,
                // never the currently visible subset (which may hide jobs).
                // listCompleteSessionTransfers checks the unfiltered default
                // before and after its read and refuses unavailable coverage.
                val compareReady = snapshotAccount != null &&
                    inventoryRevision > 0 && !stale && !loading
                val verifiedInventory by androidx.compose.runtime.produceState<SnapshotServerInventory?>(
                    initialValue = null, snapshotAccount, inventoryRevision, compareReady
                ) {
                    value = null
                    if (!compareReady || snapshotAccount == null) return@produceState
                    value = try {
                        val hashes = snapshotAccount.listCompleteSessionTransfers().map { it.hash }
                        SnapshotServerInventory(snapshotAccount, inventoryRevision, hashes)
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) { null }
                }
                val comparisonHashes = verifiedInventory?.takeIf { comparison ->
                    compareReady && comparison.account === snapshotAccount &&
                        comparison.revision == inventoryRevision
                }?.hashes
                // Give the nested callback an explicit suspend function type:
                // let{} alone infers an ordinary Function1, not SuspendFunction1.
                val originalReader: suspend (String) -> ByteArray? = { hash ->
                    snapshotAccount?.originalMetainfoForSnapshot(hash)
                }
                val snapshotAliasScope = aliasScope
                val definitionAccountLabel = RemoteVerifiedUiIdentity.removalScopeLabel(snapshotAliasScope)
                val definitionRestore = remember(snapshotAccount, inventoryRevision, snapshotAliasScope) {
                    snapshotAccount?.let { account ->
                        SnapshotRemoteDefinitionRestore(
                            account, AndroidSnapshotRestoreJournalStore(context),
                            stillCurrent = {
                                server === account && aliasScope == snapshotAliasScope &&
                                    section == Section.SNAPSHOT && snapshotRevision == inventoryRevision &&
                                    !stale && !loading
                            },
                            admitWrite = { mutationGate.tryStart() },
                            settleWrite = settledRemoteMutation
                        )
                    }
                }
                // Batch writes use the SAME exact-account mutation gate, but
                // not the pre-first-Add inventory generation: their own
                // coordinator proves the full expected roster between every
                // one-time stopped Add. A different account, screen or unsafe
                // connection state still cancels the remaining items.
                val batchDefinitionRestore = remember(snapshotAccount, snapshotAliasScope) {
                    snapshotAccount?.let { account ->
                        SnapshotRemoteBatchStoppedRestore(
                            account, AndroidSnapshotRestoreJournalStore(context),
                            stillCurrent = {
                                server === account && aliasScope == snapshotAliasScope &&
                                    section == Section.SNAPSHOT && !stale && !loading
                            },
                            admitBatch = { mutationGate.tryStart() },
                            settleBatch = settledRemoteMutation
                        )
                    }
                }
                SnapshotInventoryExportScreen(
                    transfers = transfers, view = currentView,
                    snapshotRevision = snapshotRevision,
                    stale = stale, loading = loading,
                    onBack = { section = Section.TRANSFERS },
                    readOriginalMetainfo = if (snapshotAccount == null) null else originalReader,
                    sourceSessionIdentity = snapshotAccount,
                    accountStillCurrent = { server === snapshotAccount && aliasScope == snapshotAliasScope },
                    settingsCapture = snapshotAccount?.let { account ->
                        SnapshotSettingsCapture(
                            readAlias = { hash -> snapshotAliasScope.takeIf { it.isNotBlank() }
                                ?.let { aliasStore.get(it, hash) } },
                            readFiles = { hash ->
                                account.readSnapshotFiles(hash).mapIndexed { index, row ->
                                    SnapshotFilePriorities.Observation(
                                        SnapshotFilePriorities.File(index, row.path, row.sizeBytes), row.priority)
                                }
                            }
                        )
                    },
                    restorePanel = {
                        SnapshotRestorePreviewPanel(
                            targetHashes = comparisonHashes.orEmpty(),
                            target = SnapshotRestoreTarget.SERVER,
                            inventoryConfirmed = comparisonHashes != null,
                            inventoryStale = stale,
                            busyEngine = loading,
                            mutationGeneration = snapshotRevision,
                            remoteDefinitionRestore = definitionRestore,
                            remoteBatchStoppedRestore = batchDefinitionRestore,
                            remoteDefinitionActionScope = scope,
                            remoteDefinitionAccountLabel = definitionAccountLabel,
                            remoteDefinitionFolderAccount = snapshotAccount,
                            onApplyAlias = { entry, generation, send ->
                                val account = snapshotAccount
                                val exactScope = snapshotAliasScope
                                if (account == null || server !== account ||
                                    aliasScope != exactScope || stale || loading ||
                                    snapshotRevision != generation) {
                                    send("The exact rTorrent session changed. Compare the snapshot again.")
                                } else scope.launch {
                                    try {
                                        // Read the complete authenticated session afresh.
                                        // Never rename a server torrent or its backing files.
                                        val current = account.listCompleteSessionTransfers()
                                            .singleOrNull { it.hash.equals(entry.infoHashV1, true) }
                                        if (server !== account || aliasScope != exactScope ||
                                            stale || loading || snapshotRevision != generation) {
                                            send("The rTorrent session changed. No alias was applied.")
                                        } else {
                                            val feedback = withContext(Dispatchers.IO) {
                                                applyRemoteSnapshotAlias(aliasStore, exactScope,
                                                    entry, current)
                                            }
                                            if (feedback == "Saved remote display alias verified on this device.")
                                                aliasRevision++
                                            send(feedback)
                                        }
                                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                                        throw cancel
                                    } catch (_: Exception) {
                                        send("Could not confirm this remote client's current transfer. No alias was applied.")
                                    }
                                }
                            },
                            readRemotePriorityReview = { entry ->
                                val account = snapshotAccount
                                val expectedScope = snapshotAliasScope
                                if (account == null || server !== account ||
                                    aliasScope != expectedScope || stale || loading) null
                                else {
                                    // The visible main view can omit jobs. Independently read the
                                    // complete, unfiltered exact-account session before AND after
                                    // asking for this one torrent's file priorities.
                                    val prior = account.listCompleteSessionTransfers()
                                        .singleOrNull { it.hash.equals(entry.infoHashV1, true) }
                                    if (prior == null) null
                                    else {
                                        val observed = account.readSnapshotFiles(entry.infoHashV1)
                                        val after = account.listCompleteSessionTransfers()
                                            .singleOrNull { it.hash.equals(entry.infoHashV1, true) }
                                        if (server !== account || aliasScope != expectedScope ||
                                            stale || loading || after == null ||
                                            prior.name != after.name ||
                                            prior.directory != after.directory ||
                                            prior.sizeBytes != after.sizeBytes) null
                                        else SnapshotRemotePriorityComparison.plan(entry, after, observed)
                                    }
                                }
                            },
                            onApplyRemotePriorities = { entry, review, generation, send ->
                                val account = snapshotAccount
                                val expectedScope = snapshotAliasScope
                                if (account == null || server !== account ||
                                    aliasScope != expectedScope || stale || loading ||
                                    snapshotRevision != generation) {
                                    send("The exact rTorrent session changed. No priorities were submitted.")
                                } else scope.launch {
                                    val result = try {
                                        SnapshotRemotePriorityApply.apply(
                                            accountScope = expectedScope,
                                            entry = entry, reviewed = review,
                                            confirmed = true,
                                            stillCurrent = {
                                                server === account && aliasScope == expectedScope &&
                                                    !stale && !loading && snapshotRevision == generation
                                            },
                                            port = object : SnapshotRemotePriorityApply.Port {
                                                override suspend fun completeSession(): List<RemoteTransfer> =
                                                    account.listCompleteSessionTransfers()
                                                override suspend fun readFiles(hash: String): List<RemoteFile> =
                                                    account.readSnapshotFiles(hash)
                                                override suspend fun setFilePriority(
                                                    hash: String, index: Int, path: String,
                                                    sizeBytes: Long, expected: Int, desired: Int
                                                ): io.github.gamesbyian.chunks.rpc.FilePriorityOutcome =
                                                    account.setFilePriority(hash, index, path,
                                                        sizeBytes, expected, desired)
                                            }
                                        )
                                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                                        throw cancel
                                    } catch (_: Exception) {
                                        SnapshotRemotePriorityApply.Result(
                                            SnapshotRemotePriorityApply.Outcome.NEEDS_READ_ONLY_REVIEW,
                                            0, review.changeCount)
                                    }
                                    if (result.verifiedWrites > 0) snapshotRevision++
                                    send(when (result.outcome) {
                                        SnapshotRemotePriorityApply.Outcome.VERIFIED ->
                                            "All ${result.verifiedWrites} rTorrent priority changes verified. " +
                                                "No Start/Stop operation was submitted."
                                        SnapshotRemotePriorityApply.Outcome.NO_CHANGES ->
                                            "Saved priorities already match; no write submitted."
                                        SnapshotRemotePriorityApply.Outcome.REFUSED ->
                                            "Priority restore refused before writing. Review again."
                                        SnapshotRemotePriorityApply.Outcome.STALE ->
                                            "The session or file layout changed. No write submitted."
                                        SnapshotRemotePriorityApply.Outcome.NEEDS_READ_ONLY_REVIEW ->
                                            "Stopped after ${result.verifiedWrites} verified changes. " +
                                                "A write or postcondition may be uncertain; check current priorities read-only. " +
                                                "Nothing was retried."
                                    })
                                }
                            },
                            onPrepareAdd = { document ->
                                // This archive entry is a new source with no standalone
                                // document to archive or remove, even if the bytes coincide
                                // with an earlier Android document.
                                if (prepareRemoteTorrent(document)) selectAuthenticatedAddTarget()
                                else android.widget.Toast.makeText(context,
                                    "Finish the current Add review before preparing another source.",
                                    android.widget.Toast.LENGTH_LONG).show()
                            },
                            onPrepareMagnet = { link ->
                                prepareRemoteMagnet(link)
                                selectAuthenticatedAddTarget()
                            }
                        )
                    }
                )
                }
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
                Section.CREATE -> Unit // Composed by the shared Create route above.
                Section.PLACES -> PlacesScreen(transfers) { path ->
                    remoteAddSessions.forAccount(aliasScope)
                        .takeUnless { it.alreadyAttempted }?.destination = path
                    selectAuthenticatedAddTarget()
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
                    onDisconnect = {
                        savedAddReconnect.abandon()
                        disconnectCurrentRemote()
                    }
                )
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
                savedAddReconnect.abandon()
                // One canonical catalog in local-only, remote-only and mixed
                // modes. Its chips change view scopes only; this route grants
                // no engine, server or per-job mutation authority.
                val retappingList = section == Section.COMBINED && !combinedDetailsVisible
                section = Section.COMBINED
                combinedFromConnections = false
                returnToCatalogRevision++
                // Re-tapping Transfers is useful even when already on this
                // list: return to its first row without resetting search,
                // filter, exact environment selection or any Add draft.
                // Only a re-tap of the already-visible list requests its top.
                if (retappingList) scope.launch { combinedListState.scrollToItem(0) }
                // Returning to Transfers re-reads the current server (read-only, fenced by the
                // mutation gate), as ruTorrent/Transdroid lists do; otherwise a magnet stays
                // listed under its pre-metadata name until some unrelated write settles.
                refresh()
            },
            onAdd = {
                savedAddReconnect.abandon()
                section = Section.ADD_CHOOSE
            },
            onOptions = {
                savedAddReconnect.abandon()
                localNavigation.returnToOptionsIndexIfOpen(scope)
                section = Section.OPTIONS
            },
            onConnections = {
                returnFromStartupConnectToCanonical = false
                section = Section.SERVER
            },
            addDescription = "Add a torrent",
            activeDestination = footerDestinationForVisibleScreen(section.name, server != null, toolOrigin)
        )
    }
}

internal fun readableFailure(failure: Exception): String = when (failure) {
    is RemoteSmartConnect.ConnectionFailed -> failure.message.orEmpty()
    // A real HTTP status from the transport says more than "network error".
    is io.github.gamesbyian.chunks.rpc.RpcHttpStatusException -> httpStatusExplanation(failure.status)
    is java.net.UnknownHostException -> "The host name could not be resolved."
    is java.net.SocketTimeoutException -> "The remote client did not respond before the timeout. If a command was submitted, its outcome must be checked before retrying."
    is java.net.ConnectException -> "The host did not accept the connection."
    is java.io.IOException -> "Network or proxy error. Check your network and connection settings."
    else -> "The remote client returned an incompatible or unsupported RPC response. Check its configuration before retrying an operation."
}

/** Plain-language meaning of an observed gateway HTTP status; never a retry instruction for writes. */
internal fun httpStatusExplanation(status: Int): String = when (status) {
    401, 403 -> "The server refused this login (HTTP $status). Its password or access may have " +
        "changed. Open Connections, Disconnect, then sign in again; your torrents are unchanged."
    407 -> "A proxy between this device and the host requires its own login (HTTP 407)."
    429 -> "The server is limiting requests (HTTP 429). Wait a moment, then refresh."
    in 500..599 -> "The server's web gateway returned HTTP $status. It may be restarting or " +
        "overloaded; refresh again shortly."
    else -> "The server returned HTTP $status for this list request."
}

/** True for an observed login refusal, where the repair is signing in again. */
internal fun isLoginRefused(failure: Exception): Boolean =
    failure is io.github.gamesbyian.chunks.rpc.RpcHttpStatusException &&
        (failure.status == 401 || failure.status == 403)

/** Navigation memory only: never contains an Add source, credential, selection or write authority. */
private class LocalNavigationMemory(
    val localListScroll: androidx.compose.foundation.ScrollState,
    val localAddScroll: androidx.compose.foundation.ScrollState,
    val localOptionsScroll: androidx.compose.foundation.ScrollState,
    val localSearch: androidx.compose.runtime.MutableState<String>,
    val localOptionsSearch: androidx.compose.runtime.MutableState<String>,
    val localOptionsCategory: androidx.compose.runtime.MutableState<OptionsCategory?>,
    val localOptionsReturnQuery: androidx.compose.runtime.MutableState<String>,
    val localOptionsIndexScroll: androidx.compose.runtime.MutableState<Int>
) {
    fun returnToOptionsIndexIfOpen(scope: kotlinx.coroutines.CoroutineScope) {
        if (localOptionsCategory.value != null) {
            localOptionsCategory.value = null
            localOptionsSearch.value = localOptionsReturnQuery.value
            scope.launch {
                androidx.compose.runtime.withFrameNanos { }
                localOptionsScroll.scrollTo(localOptionsIndexScroll.value)
            }
        }
    }
}

@Composable
private fun rememberLocalNavigationMemory(): LocalNavigationMemory {
    val localListScroll = rememberScrollState()
    val localAddScroll = rememberScrollState()
    val localOptionsScroll = rememberScrollState()
    val localSearch = rememberSaveable { mutableStateOf("") }
    val localOptionsSearch = rememberSaveable { mutableStateOf("") }
    val localOptionsCategory = rememberSaveable { mutableStateOf<OptionsCategory?>(null) }
    val localOptionsReturnQuery = rememberSaveable { mutableStateOf("") }
    val localOptionsIndexScroll = rememberSaveable { mutableStateOf(0) }
    return remember(
        localListScroll, localAddScroll, localOptionsScroll, localSearch,
        localOptionsSearch, localOptionsCategory, localOptionsReturnQuery, localOptionsIndexScroll
    ) {
        LocalNavigationMemory(
            localListScroll, localAddScroll, localOptionsScroll, localSearch,
            localOptionsSearch, localOptionsCategory, localOptionsReturnQuery, localOptionsIndexScroll)
    }
}
