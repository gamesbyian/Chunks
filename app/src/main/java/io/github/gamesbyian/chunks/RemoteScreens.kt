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

// A2611-02: the connection form and the classic rTorrent screens, moved unchanged out of MainActivity.kt.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    onPassword: (String) -> Unit, onConnect: (automatic: Boolean) -> Unit,
    loginRequested: Boolean = false,
    connectedSessionName: String? = null,
    connectedProfileId: String? = null,
    connectedSessionVerified: Boolean = true,
    connectedVersion: String = "",
    remoteForDefaultFolder: RTorrentRemote? = null,
    savedDefaultFolder: String? = null,
    onSaveDefaultFolder: (String?) -> Boolean = { false },
    connectedRequestedView: String = "main",
    onSelectConnectedView: (String) -> Unit = {},
    onLoadConnectedView: () -> Unit = {},
    onConnectedSeedingRules: () -> Unit = {},
    onViewConnectedTransfers: () -> Unit = {},
    onDisconnectCurrent: () -> Unit = {},
    connectionName: String = "",
    onConnectionName: (String) -> Unit = {},
    onAddConnection: () -> Unit = {},
    /** Optional, existing-form expansion. Only the caller can enable and implement it. */
    enableAdditionalProtocols: Boolean = false,
    chosenBackend: RemoteBackendKind = RemoteBackendKind.RTORRENT,
    onChosenBackend: (RemoteBackendKind) -> Unit = {},
    delugeDaemonHostId: String = "",
    onDelugeDaemonHostId: (String) -> Unit = {},
    onConnectReadOnlyBackend: () -> Unit = {},
    readOnlyBackendError: String? = null,
    readOnlySavedProfiles: List<RememberedRemoteProtocolProfiles.Profile> = emptyList(),
    readOnlyConnectedProfileId: String? = null,
    onViewReadOnlyProfile: (RememberedRemoteProtocolProfiles.Profile) -> Unit = {},
    onRenameReadOnlyProfile: suspend (RememberedRemoteProtocolProfiles.Profile, String) -> Boolean = { _, _ -> false },
    onForgetReadOnlyProfile: (RememberedRemoteProtocolProfiles.Profile) -> Unit = {}
) {
    // Submitting the form closes the keyboard, as forms conventionally do,
    // so the next screen's persistent footer is not waiting on an IME hide.
    val connectKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val connectFocus = androidx.compose.ui.platform.LocalFocusManager.current
    val validName = connectionName.isBlank() ||
        RememberedRemoteProfileRoster.validFriendlyName(connectionName)
    val isRTorrentForm = !enableAdditionalProtocols || chosenBackend == RemoteBackendKind.RTORRENT
    val needsDaemonId = !isRTorrentForm && chosenBackend == RemoteBackendKind.DELUGE
    // A blank Deluge daemon ID is allowed: Connect adopts the Web UI's daemon
    // only when exactly one is registered, and otherwise asks for the ID.
    val canSubmitConnection = endpoint.isNotBlank() && (isRTorrentForm || password.isNotEmpty())
    val submitConnect: () -> Unit = {
        connectKeyboard?.hide()
        connectFocus.clearFocus()
        if (!loading && validName && connectedSessionName == null && canSubmitConnection) {
            if (isRTorrentForm) onConnect(false) else onConnectReadOnlyBackend()
        }
    }
    var pendingForget by remember { mutableStateOf<String?>(null) }
    var extraToolsOpen by rememberSaveable { mutableStateOf(false) }
    var currentServerToolsOpen by rememberSaveable { mutableStateOf(false) }
    var editingRemoteDefault by remember(remoteForDefaultFolder) { mutableStateOf(false) }
    var remoteDefaultNotice by remember(remoteForDefaultFolder) { mutableStateOf<String?>(null) }
    if (editingRemoteDefault && remoteForDefaultFolder != null) {
        RemoteFolderPicker(
            remote = remoteForDefaultFolder,
            connectionName = connectedSessionName ?: "rTorrent",
            initialFolder = savedDefaultFolder,
            onChoose = { selected ->
                if (onSaveDefaultFolder(selected)) {
                    editingRemoteDefault = false
                    remoteDefaultNotice = "Default download folder saved for this remote client."
                } else remoteDefaultNotice = "Could not save the remote client's default folder."
            },
            onDismiss = { editingRemoteDefault = false },
            savedDefaultMode = true
        )
    }
    var renameProfileId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var renameBusy by remember { mutableStateOf(false) }
    var renameNotice by remember { mutableStateOf<String?>(null) }
    val renameScope = rememberCoroutineScope()
    val connectionList = rememberLazyListState()
    var showNewConnectionForm by rememberSaveable { mutableStateOf(loginRequested) }
    LaunchedEffect(loginRequested) { if (loginRequested) showNewConnectionForm = true }
    var managedProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    val activeCardId = connectedProfileId ?: "transient-verified-session"
    val managingConnected = managedProfileId == activeCardId && connectedSessionName != null
    val rosterVisible = managedProfileId == null && !showNewConnectionForm
    val openNewConnection = {
        onAddConnection()
        managedProfileId = null
        showNewConnectionForm = true
    }
    androidx.activity.compose.BackHandler(!rosterVisible) {
        managedProfileId = null
        showNewConnectionForm = false
        pendingForget = null
    }
    LaunchedEffect(connectedSessionName, readOnlyConnectedProfileId) {
        if (connectedSessionName != null || readOnlyConnectedProfileId != null) {
            showNewConnectionForm = false
        }
    }
    LaunchedEffect(showNewConnectionForm, managedProfileId) { connectionList.scrollToItem(0) }
    // Reuse the exact existing verified Connect operation. No saved password or
    // endpoint is broadcast to an unverified profile, and no torrent mutation
    // occurs merely because the app opened. Consume before invoking the action
    // to avoid an automatic loop if authentication/network validation fails.
    StartupRemoteReconnectEffect(
        eligible = startupReconnectEligible,
        onConsumed = onStartupReconnectConsumed,
        onConnect = { onConnect(true) }
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = connectionList,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (rosterVisible) "Connections" else if (showNewConnectionForm) "Connect client" else "Manage client",
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (!rosterVisible) OutlinedButton(onClick = {
                    managedProfileId = null; showNewConnectionForm = false; pendingForget = null
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Connections") }
            }
        }
        if (rosterVisible) {
            if (savedProfiles.isEmpty() && readOnlySavedProfiles.isEmpty() &&
                readOnlyConnectedProfileId == null && connectedSessionName == null) item {
                    Card(colors = CardDefaults.cardColors(containerColor = Lilac)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Connect to a remote client", modifier = Modifier.semantics { heading() },
                                style = MaterialTheme.typography.titleMedium,
                                color = ChunksColors.Background)
                            Text("Manage torrents in a remote client on another computer.", color = ChunksColors.Background)
                            OutlinedButton(onClick = openNewConnection, enabled = !loading,
                                modifier = Modifier.heightIn(min = 48.dp).testTag(ChunksSemantics.CONNECTION_ADD)) {
                                Text("+ Add connection", color = ChunksColors.Background)
                            }
                        }
                    }
            } else stickyHeader(key = "add-connection") {
                Surface(color = ChunksColors.Background) {
                OutlinedButton(onClick = openNewConnection, enabled = !loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(ChunksSemantics.CONNECTION_ADD)) {
                    Text("+ Add connection")
                }
                }
            }
            items(unifiedConnectionRoster(savedProfiles, connectedProfileId, connectedSessionName, connectedSessionVerified,
                attentionProfileId = savedProfiles.firstOrNull { error != null &&
                    it.credentials.endpoint == endpoint && it.credentials.username == username }?.id), key = { it.id }) { entry ->
                ConnectionRosterCard(entry, loading = loading,
                    onPrimary = {
                        if (entry.connected) onViewConnectedTransfers()
                        else entry.profile?.let { profile ->
                            // Retire any different verified UI session before preparing this exact login.
                            onAddConnection()
                            onSelectProfile(profile)
                            showNewConnectionForm = true
                        }
                    },
                    onManage = { managedProfileId = entry.id })
            }
            items(readOnlySavedProfiles, key = { "readonly-profile-" + it.id }) { profile ->
                Card(colors = CardDefaults.cardColors(containerColor = ChunksColors.SurfaceRaised)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(profile.label, style = MaterialTheme.typography.titleMedium)
                        val kindName = when (profile.backend) {
                            RemoteBackendKind.TRANSMISSION -> "Transmission"
                            RemoteBackendKind.QBITTORRENT -> "qBittorrent"
                            RemoteBackendKind.DELUGE -> "Deluge"
                            else -> "Remote client"
                        }
                        Text(kindName + " · " +
                            if (readOnlyConnectedProfileId == profile.id) "Recently verified"
                            else "Saved connection",
                            style = MaterialTheme.typography.bodySmall)
                        ResponsiveActionGroup(maxColumns = 2) { cell ->
                            OutlinedButton(onClick = { onViewReadOnlyProfile(profile) },
                                enabled = !loading, modifier = cell.heightIn(min = 48.dp)
                                    .testTag("protocol-profile-view-" + profile.id)) {
                                Text("View transfers")
                            }
                            OutlinedButton(onClick = { managedProfileId = profile.id },
                                modifier = cell.heightIn(min = 48.dp)
                                    .testTag("protocol-profile-manage-" + profile.id)) {
                                Text("Manage")
                            }
                        }
                    }
                }
            }
            if (error != null) item { Notice("Connection needs attention", error) }
        }
        if (managingConnected) item {
            Card(colors = CardDefaults.cardColors(containerColor = ChunksColors.SurfaceRaised)) {
                Column(Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text((if (connectedSessionVerified) "Connected · " else "Connection unverified · ") + connectedSessionName,
                        style = MaterialTheme.typography.titleMedium)
                    Text(if (connectedSessionVerified) "This verified session is available in Transfers."
                        else "Last connection is unverified. Inventory may be out of date.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onViewConnectedTransfers,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .semantics { contentDescription = "View transfers on current verified remote client" }) {
                            Text("View transfers")
                        }
                        OutlinedButton(onClick = { onDisconnectCurrent(); managedProfileId = null }, enabled = !loading,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("Disconnect")
                        }
                    }
                    if (remoteForDefaultFolder != null) {
                        Text(
                            "New downloads: " + (savedDefaultFolder ?: "rTorrent default folder"),
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            onClick = {
                                editingRemoteDefault = true
                                remoteDefaultNotice = null
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text("Choose default download folder") }
                        if (savedDefaultFolder != null) {
                            TextButton(
                                onClick = {
                                    remoteDefaultNotice =
                                        if (onSaveDefaultFolder(null))
                                            "This remote client will use its rTorrent default again."
                                        else "Could not clear the saved default folder."
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("Use rTorrent default") }
                        }
                        remoteDefaultNotice?.let { notice ->
                            Text(notice, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedButton(onClick = { currentServerToolsOpen = !currentServerToolsOpen },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .semantics {
                                stateDescription = if (currentServerToolsOpen) "Expanded" else "Collapsed"
                            }) {
                        Text(if (currentServerToolsOpen) "Hide current remote client tools" else "Current remote client tools")
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
        if (managedProfileId != null &&
            (savedProfiles.isNotEmpty() || readOnlySavedProfiles.isNotEmpty())) {
            /* Secondary profile settings, available independently of remembered-login preference. */
            item {
                Text("Profile settings", style = MaterialTheme.typography.titleMedium)
            }
            items(savedProfiles.filter { it.id == managedProfileId }, key = { "manage-profile-" + it.id }) { profile ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(profile.label, style = MaterialTheme.typography.titleMedium)
                    Text(profile.address, style = MaterialTheme.typography.bodySmall)
                    if (!managingConnected) Text("Disconnected · connect to use remote folder browsing and remote client tools.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        onAddConnection()
                        onSelectProfile(profile)
                        managedProfileId = null
                        showNewConnectionForm = true
                    }, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Edit connection / login")
                    }
                    if (!managingConnected && onOpenProfileTransfers != null) OutlinedButton(
                        onClick = { onOpenProfileTransfers(profile) }, enabled = !loading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("View available inventory (read only)")
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
                            label = { Text("Friendly name for this connection") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        Text("Only the name shown in Chunks changes. The remote client, login, torrents and files stay unchanged.",
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
                    // Saved profile colour belongs with the exact connection, not
                    // in the Transfers catalog. The immutable profile ID is authority.
                    EnvironmentColourPreference(
                        id = io.github.gamesbyian.chunks.model.EnvironmentId(profile.id),
                        label = profile.label,
                        configuredIds = listOf(io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL) +
                            savedProfiles.map { io.github.gamesbyian.chunks.model.EnvironmentId(it.id) },
                        accessibilityLabel = profile.label + " on " + profile.address)
                }
            }
        }
        if (managedProfileId != null) {
            items(readOnlySavedProfiles.filter { it.id == managedProfileId },
                key = { "manage-readonly-profile-" + it.id }) { profile ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(profile.label, style = MaterialTheme.typography.titleMedium)
                    Text("Open a transfer's Details to start, stop, recheck, set file priorities or remove it on this client.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onViewReadOnlyProfile(profile) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("View transfers")
                    }
                    if (pendingForget == profile.id) {
                        Text("Forget this connection's saved login? Torrents are unchanged.",
                            style = MaterialTheme.typography.bodySmall)
                        ResponsiveActionGroup(maxColumns = 2) { cell ->
                            OutlinedButton(onClick = {
                                pendingForget = null
                                managedProfileId = null
                                onForgetReadOnlyProfile(profile)
                            }, enabled = !loading, modifier = cell.heightIn(min = 48.dp)) {
                                Text("Forget connection")
                            }
                            OutlinedButton(onClick = { pendingForget = null },
                                modifier = cell.heightIn(min = 48.dp)) { Text("Cancel") }
                        }
                    } else {
                        ResponsiveActionGroup(maxColumns = 2) { cell ->
                            OutlinedButton(onClick = {
                                renameProfileId = profile.id
                                renameDraft = profile.label
                                renameNotice = null
                            }, enabled = !loading && !renameBusy,
                                modifier = cell.heightIn(min = 48.dp)) { Text("Rename") }
                            OutlinedButton(onClick = { pendingForget = profile.id },
                                enabled = !loading && !renameBusy,
                                modifier = cell.heightIn(min = 48.dp)) { Text("Forget") }
                        }
                    }
                    if (renameProfileId == profile.id) {
                        OutlinedTextField(value = renameDraft, onValueChange = { renameDraft = it },
                            label = { Text("Connection name") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        ResponsiveActionGroup(maxColumns = 2) { cell ->
                            OutlinedButton(onClick = {
                                if (!renameBusy) {
                                    renameBusy = true
                                    val name = renameDraft
                                    renameScope.launch {
                                        val saved = try { onRenameReadOnlyProfile(profile, name) }
                                            catch (_: Exception) { false }
                                        if (saved) renameProfileId = null
                                        renameNotice = if (saved) "Name saved." else "Could not save the name."
                                        renameBusy = false
                                    }
                                }
                            }, enabled = !loading && !renameBusy &&
                                RememberedRemoteProtocolProfiles.validLabel(renameDraft.trim()),
                                modifier = cell.heightIn(min = 48.dp)) { Text("Save") }
                            OutlinedButton(onClick = { renameProfileId = null },
                                enabled = !renameBusy,
                                modifier = cell.heightIn(min = 48.dp)) { Text("Cancel") }
                        }
                    }
                    EnvironmentColourPreference(
                        id = io.github.gamesbyian.chunks.model.EnvironmentId(profile.id),
                        label = profile.label,
                        configuredIds = listOf(io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL) +
                            (savedProfiles.map { io.github.gamesbyian.chunks.model.EnvironmentId(it.id) } +
                                readOnlySavedProfiles.map { io.github.gamesbyian.chunks.model.EnvironmentId(it.id) }),
                        accessibilityLabel = profile.label + " connection")
                }
            }
        }
        if (renameNotice != null) item {
            Text(renameNotice.orEmpty(), style = MaterialTheme.typography.bodySmall)
        }
        if (showNewConnectionForm) {
        if (connectedSessionName == null) {
        if (enableAdditionalProtocols) item {
            Text("Client type", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RemoteBackendKind.entries.forEach { backend ->
                    FilterChip(
                        selected = chosenBackend == backend,
                        onClick = { if (!loading) onChosenBackend(backend) },
                        enabled = !loading,
                        label = { Text(when (backend) {
                            RemoteBackendKind.RTORRENT -> "rTorrent"
                            RemoteBackendKind.TRANSMISSION -> "Transmission"
                            RemoteBackendKind.QBITTORRENT -> "qBittorrent"
                            RemoteBackendKind.DELUGE -> "Deluge"
                        }) },
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("connection-backend-" + backend.name.lowercase())
                    )
                }
            }
            Text("All clients use this same Connections screen. Connecting only checks the login and reads the transfer list.",
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedTextField(
                value = connectionName, onValueChange = onConnectionName,
                label = { Text("Connection name (optional)") },
                supportingText = { Text(if (validName) "Leave blank to use the account and host name."
                    else "Use up to 100 characters without control characters.") },
                isError = !validName, enabled = !loading, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag(ChunksSemantics.CONNECTION_NAME)
            )
        }
        item {
            OutlinedTextField(
                value = endpoint, onValueChange = onEndpoint,
                label = { Text(when {
                    isRTorrentForm -> "Seedbox hostname, ruTorrent URL or RPC address"
                    chosenBackend == RemoteBackendKind.TRANSMISSION -> "Transmission RPC URL"
                    chosenBackend == RemoteBackendKind.QBITTORRENT -> "qBittorrent WebUI root URL"
                    else -> "Deluge Web JSON URL"
                }) },
                placeholder = { Text(when {
                    isRTorrentForm -> "server.example or https://server.example/rutorrent/"
                    chosenBackend == RemoteBackendKind.TRANSMISSION -> "https://server.example/transmission/rpc"
                    chosenBackend == RemoteBackendKind.QBITTORRENT -> "https://server.example/qbittorrent/"
                    else -> "https://server.example/deluge/json"
                }) },
                // Next moves to the following field; no autocorrect on an address.
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    autoCorrectEnabled = false,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth()
            )
            val providerHint = if (isRTorrentForm) remember(endpoint) {
                RemoteProviderCatalog.hint(endpoint)
            } else null
            if (providerHint != null) Text(providerHint, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (isRTorrentForm) item {
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
                Text("For a host your provider documents with a specific XML-RPC path. Enter only the " +
                    "hostname above; Chunks then tests just this one address, read-only, instead of " +
                    "checking known paths.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (isRTorrentForm || chosenBackend != RemoteBackendKind.DELUGE) item {
            OutlinedTextField(
                value = username, onValueChange = onUsername,
                label = { Text("Username, if required") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                singleLine = true, enabled = !loading, modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            SecurePasswordInput(
                value = password, onValueChange = onPassword,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
                // Go runs the same read-only Connect as the button, under its rules.
                onGo = { if (!loading && canSubmitConnection) submitConnect() }
            )
        }
        if (needsDaemonId) item {
            OutlinedTextField(
                value = delugeDaemonHostId, onValueChange = onDelugeDaemonHostId,
                label = { Text("Deluge daemon ID (optional)") },
                supportingText = { Text("Leave empty to use the Web UI's only daemon. Needed only when it has several.") },
                singleLine = true, enabled = !loading,
                modifier = Modifier.fillMaxWidth()
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
                Text("Remember this connection and login on this device",
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
                enabled = !loading && canSubmitConnection && validName,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .testTag(ChunksSemantics.CONNECTION_CONNECT)
            ) { Text(when {
                loading && isRTorrentForm -> "Finding secure rTorrent…"
                loading -> "Checking connection…"
                isRTorrentForm -> "Connect to rTorrent"
                else -> "Connect to " + when (chosenBackend) {
                    RemoteBackendKind.TRANSMISSION -> "Transmission"
                    RemoteBackendKind.QBITTORRENT -> "qBittorrent"
                    RemoteBackendKind.DELUGE -> "Deluge"
                    else -> "rTorrent"
                }
            }) }
        }
        if (connectedSessionName == null) {
            if (isRTorrentForm) item {
                ConnectionDiagnosticsPanel(diagnostics, loading, onCancelConnect)
            }
            if (isRTorrentForm && error != null) item { Notice("Connection unsuccessful", error) }
            if (!isRTorrentForm && loading) item {
                OutlinedButton(onClick = onCancelConnect,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Cancel connection check")
                }
            }
            if (!isRTorrentForm && readOnlyBackendError != null) item {
                Notice("Connection unsuccessful", readOnlyBackendError)
            }
        }
        item {
            Text("Protected HTTPS is required for remote clients. Login is encrypted and stored only on this device unless you opt out. No torrents or files are changed by connecting.",
                style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary)
        }
        } // Explicit Add/Edit connection form.
        // Offline tools need no connection: keep them reachable from the roster itself
        // (zero profiles included), not only after starting "+ Add connection".
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
                ActionTile("Create a torrent from the local device files", ChunksColors.Green,
                    onClick = onCreateTorrent, modifier = Modifier.fillMaxWidth(),
                    icon = R.drawable.chunks_ic_add_torrent, stacked = false)
            }
        }
    }
}

@Composable
internal fun TransfersScreen(
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
    onPrepareLocal: (RemoteTransfer) -> Unit,
    onCopyToLocal: (String) -> Unit,
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
            Text("Start set is a client flag, not proof the engine is active or peers are " +
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
                                    "Saved ${targets.size} reviewed memberships on this device. " +
                                        "Seeding and remote files are unchanged."
                                else "Batch save was not confirmed. Inspect current Collection " +
                                    "memberships before retrying; no remote change was requested."
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
                    RemoteBatchLocalCopyPanel(
                        selected = list.filter { it.hash in batchHashes },
                        enabled = !stale && !loading && !batchBusy && batchHashes.isNotEmpty(),
                        run = { items -> runBatchLocalCopy(context, remote, items) },
                        move = { items ->
                            runBatchLocalMove(context, remote, aliasScope, items, list)
                                .also { serverMoveRevision++ }
                        }
                    )
                    val serverDigest = TransferMoveStore.scopeDigest(aliasScope)
                    val pendingServerMoves = remember(list, serverMoveRevision, serverDigest) {
                        TransferMoveStore(context).all(TransferMove.Direction.SERVER_TO_LOCAL).filter { record ->
                            record.stage == TransferMove.Stage.TARGET_VERIFIED &&
                                record.serverScopeDigest == serverDigest &&
                                list.any { it.hash.equals(record.infoHash, ignoreCase = true) &&
                                    TransferMove.sourceIdentityMatches(
                                        record, it.name, null, it.directory) }
                        }
                    }
                    if (view == "main") BatchMoveRemovalPanel(
                        pending = pendingServerMoves, serverScopeDigest = serverDigest,
                        serverLabel = "this device", sourceLabel = "remote client",
                        busy = stale || loading,
                        observeTarget = { hash -> TransferMoveObservations.local(hash) },
                        removeSource = { hash ->
                            val original = pendingServerMoves.singleOrNull {
                                it.infoHash.equals(hash, ignoreCase = true)
                            } ?: error("Original remote Move journal missing")
                            val live = list.singleOrNull {
                                it.hash.equals(hash, ignoreCase = true)
                            } ?: error("Original remote job unavailable")
                            if (!TransferMove.sourceIdentityMatches(
                                    original, live.name, null, live.directory))
                                error("Original remote job changed after Move review")
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
                direction = TransferMove.Direction.SERVER_TO_LOCAL,
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
            LocalAddedHiddenNotice(onShowAll = onClearView,
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
                onPrepareLocal = { onPrepareLocal(transfer) },
                onCopyToLocal = onCopyToLocal,
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
            Text("Remote snapshot. Start/stop and main-view session removal each require exact-target confirmation and readback. Session removal does not request payload deletion.",
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
internal fun TransferCard(
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
    onPrepareLocal: () -> Unit,
    onCopyToLocal: (String) -> Unit,
    removalEnvironmentLabel: String?,
    /** Authenticated endpoint + account scope, or null when ambiguous. */
    moveScope: String? = null,
    /** Authenticated endpoint + account scope for Finish in Order; null when ambiguous. */
    finishInOrderScope: String? = null,
    /** Similar Transfers in this card's details; null hides it. */
    similar: (@Composable () -> Unit)? = null
) {
    var confirmLocalHandoff by remember(transfer.hash) { mutableStateOf(false) }
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
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val artMaxWidth = minOf(maxWidth * 0.32f, 112.dp,
                    (maxWidth - 164.dp).coerceAtLeast(48.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransferRowArtwork(rememberRemoteTransferKey(progressEnvironmentId, transfer.hash),
                        transfer.name, modifier = Modifier.testTag("remote-card-artwork"),
                        height = if (detailsOpen) 132.dp else 112.dp, maxWidth = artMaxWidth)
                    Column(Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(alias ?: transfer.name, style = MaterialTheme.typography.titleMedium,
                    // Collapsed rows stay compact, but full names are available
                    // to TalkBack and when the user expands this exact card.
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = alias ?: transfer.name
                    },
                    maxLines = if (detailsOpen) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis)
            if (transfer.metadataPending != true && transfer.sizeBytes > 0) ChunkedProgressBar(
                progress = transfer.fractionComplete,
                modifier = Modifier.fillMaxWidth().testTag("remote-card-progress"),
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
                // Same words as the local device, from observed facts only: the started
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
                // Last, as on this device and Combined rows: rates first, then the estimate.
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
                    } // Compact title, rates and progress belong to the right-hand column.
                }
            }
            if (detailsOpen) {
                if (alias != null) Text("Original torrent: " + transfer.name,
                    style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary)
                TransferAliasEditor(
                    originalName = transfer.name, hash = transfer.hash,
                    alias = alias, onSave = onAliasSave, session = aliasSession)
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
                    "Other transfers in this loaded client view report download traffic, " +
                        "while this torrent reports none. This is one remote snapshot, " +
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
                ActionTile("Copy Transfer to the local device…", ChunksColors.Blue,
                    onClick = { confirmLocalHandoff = true }, enabled = !stale,
                    modifier = Modifier.fillMaxWidth(), icon = R.drawable.chunks_ic_copy, stacked = false)
                if (confirmLocalHandoff) {
                    Text("This torrent's v1 hash and name, plus its public tracker " +
                        "addresses, become a magnet Add draft on your device. Private " +
                        "torrents get the hash and name only: their tracker passkeys are " +
                        "never read, so they may not find peers. No downloaded bytes move, " +
                        "and the local device may download everything again. The remote transfer " +
                        "stays intact.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            confirmLocalHandoff = false
                            onPrepareLocal()
                        }, enabled = !stale, modifier = Modifier.weight(1f)
                            .heightIn(min = 48.dp)) { Text("Review local Add") }
                        OutlinedButton(onClick = { confirmLocalHandoff = false },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("Cancel")
                        }
                    }
                }
            }
            if (mainView) TransferMoveControl(
                direction = TransferMove.Direction.SERVER_TO_LOCAL,
                infoHash = transfer.hash, name = transfer.name,
                sourceDirectory = transfer.directory,
                serverAliasScope = moveScope?.takeIf {
                    TorrentIdentityHandoff.prepare(transfer.hash, transfer.name) != null
                },
                busy = stale,
                ports = TransferMovePorts(
                    observeTarget = { TransferMoveObservations.local(transfer.hash) },
                    observeSource = { TransferMoveObservations.server(remote, transfer.hash) },
                    // Guarded by the current name and Place; session-only d.erase.
                    removeSource = { remote.eraseFromSession(transfer.hash, transfer.name, transfer.directory) },
                    targetUnavailableReason = "Open transfers on this device once so Chunks can check the device."
                ),
                onStartMove = onPrepareLocal,
                onMutationStarted = onMutationStarted,
                onMutationSettled = onLifecycleSettled
            )
            ServerToLocalCopyControl(remote = remote, transfer = transfer, stale = stale,
                onReviewLocalAdd = onCopyToLocal)
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
                if (filesLoading) Text("Reading remote file list…")
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
internal fun PlacesScreen(list: List<RemoteTransfer>, onUsePlace: (String) -> Unit) {
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
        item { LocalStoragePanel() }
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
        if (places.isEmpty()) item { Text("No remote locations in this snapshot.") }
    }
}

@Composable
internal fun OrganizeScreen(
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
            Text("Showing only " + selectedCollection + " in this client view. A saved Collection may have no members in the loaded view.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { selectedCollection = null; collectionPage = 0 },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Show all collections") }
        }
        item {
            ClearableTransferSearchField(
                value = manualQuery, onValueChange = { manualQuery = it; collectionPage = 0 },
                label = "Find a transfer to organize"
            )
            Text("Found " + pagedMatches.totalMatches + " matching transfers in the loaded client view and selected collection. Page " +
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
internal fun ServerScreen(
    version: String, displayedView: String, requestedView: String, onRequestedView: (String) -> Unit,
    loading: Boolean, stale: Boolean, count: Int,
    credentialNotice: String?, savedLoginPresent: Boolean, onForgetLogin: () -> Unit,
    onRefresh: () -> Unit, onDisconnect: () -> Unit,
    onSeedingRules: () -> Unit = {},
    /** Exact server environment label, so the view choice states its scope. */
    environmentLabel: String = "this remote client"
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Connection settings · " + environmentLabel.removePrefix("Remote · "),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Text("rTorrent " + version +
                if (stale) " · last known list (refresh needed)" else " · connected session",
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("Client view", style = MaterialTheme.typography.titleLarge)
            SettingScopeNote(environmentLabel)
            Text("Different rTorrent clients organize views differently. Chunks never assumes the current view covers every torrent.",
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
            Text("The current connection remains in memory. Unless opted out, the remote login is encrypted on this device for later reconnection. Torrent operations remain separately confirmed.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ColoredPanel(
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
internal fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
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
internal fun Notice(title: String, message: String) =
    ChunksBanner(title, message, ChunksTone.ERROR, Modifier.padding(horizontal = 2.dp))
