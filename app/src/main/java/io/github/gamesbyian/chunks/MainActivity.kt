package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
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
import io.github.gamesbyian.chunks.model.Units
import io.github.gamesbyian.chunks.organize.Classifier
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteFile
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.rpc.RemoteQueueActivityContext
import io.github.gamesbyian.chunks.snapshots.SnapshotInventoryExportScreen
import io.github.gamesbyian.chunks.snapshots.SnapshotDocumentInspectScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF262442)
private val Cream = Color(0xFFFFF8EC)
private val Lilac = Color(0xFFE6DAFF)
private val Lime = Color(0xFFDDF1A7)
private val Coral = Color(0xFFFFB69D)
private val Blue = Color(0xFF4662C6)

class MainActivity : ComponentActivity() {
    private var incomingMagnet by mutableStateOf<String?>(null)
    private var incomingTorrent by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The cream Compose surface extends behind the system status bar.
        // Use dark icons; white icons failed contrast review in actual API 35 captures.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        incomingMagnet = magnetFromIntent(intent)
        incomingTorrent = SharedTorrentDocument.fromIntent(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Blue, onPrimary = Color.White, surface = Cream,
                onSurface = Ink, background = Cream, secondary = Ink
            )) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChunksApp(
                        sharedMagnet = incomingMagnet,
                        incomingTorrent = incomingTorrent,
                        onIncomingMagnetConsumed = { incomingMagnet = null },
                        onIncomingTorrentConsumed = { incomingTorrent = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingMagnet = magnetFromIntent(intent)
        incomingTorrent = SharedTorrentDocument.fromIntent(intent)
    }

    private fun magnetFromIntent(intent: Intent?): String? {
        val candidate = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trim() ?: return null
        return candidate.takeIf { it.length <= 8192 && it.startsWith("magnet:?", true) }
    }
}

private enum class Section(val label: String) {
    TRANSFERS("Transfers"), PLACES("Places"), ORGANIZE("Organize"), SERVER("Server"), ARCHIVES("ZIP review"), LOCAL("On this phone"), ADD("Add"), NAMES("Names"), SNAPSHOT("Inventory backup"), SNAPSHOT_INSPECT("Inspect backup")
}

@Composable
private fun ChunksApp(
    sharedMagnet: String?,
    incomingTorrent: Uri?,
    onIncomingMagnetConsumed: () -> Unit,
    onIncomingTorrentConsumed: () -> Unit
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
    var username by remember { mutableStateOf(remembered?.username.orEmpty()) }
    var password by remember { mutableStateOf(remembered?.password.orEmpty()) }
    // Remembering is the default. Every user can opt out before Connect.
    var rememberLogin by remember { mutableStateOf(vault.rememberingEnabled()) }
    var savedLoginPresent by remember {
        mutableStateOf(remembered != null || savedProfiles.isNotEmpty())
    }
    var credentialNotice by remember { mutableStateOf<String?>(null) }
    var server by remember { mutableStateOf<RTorrentRemote?>(null) }
    var version by remember { mutableStateOf("") }
    var transfers by remember { mutableStateOf<List<RemoteTransfer>>(emptyList()) }
    // Advances ONLY when the server has delivered a fresh, accepted queue read.
    // The operation UI can then allow another separately confirmed lifecycle command.
    var snapshotRevision by remember { mutableLongStateOf(0L) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stale by remember { mutableStateOf(false) }
    var currentView by remember { mutableStateOf("main") }
    var requestedView by remember { mutableStateOf("main") }
    var section by remember { mutableStateOf(Section.TRANSFERS) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(TransferFilter.ALL) }
    var order by remember { mutableStateOf(TransferOrder.NAME) }
    var expandedHash by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<RemoteFile>?>(null) }
    var filesError by remember { mutableStateOf<String?>(null) }
    var filesLoading by remember { mutableStateOf(false) }
    var magnet by remember { mutableStateOf("") }
    var phoneHandoffMagnet by remember { mutableStateOf<String?>(null) }
    // A phone transfer's exported .torrent waiting for the server Add review.
    // Process memory only; consumed by that review, never auto-submitted.
    var serverHandoffTorrent by remember { mutableStateOf<ByteArray?>(null) }
    var draftPath by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val aliasStore = remember(context) { TransferAliasStore(context) }
    val recentPlaceStore = remember(context) { RecentRemotePlaceStore(context) }
    var recentPlaceRevision by remember { mutableStateOf(0) }
    // Owned by the connected app, not the recyclable LazyColumn card.
    // A plain remembered map prevents getOrPut writes during composition from
    // being rolled back; each value has observable Compose state fields.
    val aliasSessions = remember { mutableMapOf<String, TransferAliasEditSession>() }
    var aliasScope by remember { mutableStateOf("") }
    var aliasRevision by remember { mutableStateOf(0) }
    val remoteFence = remember { RemoteUiRequestFence() }
    val inspectionFence = remember { RemoteUiRequestFence() }
    val mutationGate = remember { RemoteMutationReadbackGate() }

    // Content-only external .torrent links go to the phone's existing Add
    // review without starting an engine, creating a file or contacting peers.
    LaunchedEffect(incomingTorrent) {
        if (incomingTorrent != null) section = Section.LOCAL
    }

    // Every incoming magnet becomes an explicit Add draft, never an Add call.
    // When no rTorrent connection exists, the phone engine must be reachable
    // without requiring remote login. A connected remote retains its Add review.
    // Consume the Activity intent once accepted so repeating the *same* magnet
    // from Android launches a fresh, deliberate review.
    LaunchedEffect(sharedMagnet) {
        if (sharedMagnet != null) {
            if (server == null) {
                phoneHandoffMagnet = sharedMagnet
                section = Section.LOCAL
            } else {
                magnet = sharedMagnet
                section = Section.ADD
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
                            val snapshot = remote.listTransfers(ticket.view)
                            if (server === remote && remoteFence.current(ticket) &&
                                mutationGate.acceptFreshRead(readbackTicket)) {
                                transfers = snapshot
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

    Column(
        modifier = Modifier.fillMaxSize().background(Cream).safeDrawingPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ChunksBrandHeader()

        if (section == Section.ARCHIVES) {
            LocalZipReviewScreen(onBack = { section = Section.TRANSFERS })
        } else if (section == Section.SNAPSHOT_INSPECT) {
            SnapshotDocumentInspectScreen(onBack = { section = Section.TRANSFERS })
        } else if (section == Section.LOCAL && server == null) {
            LocalEngineScreen(
                onBack = { section = Section.TRANSFERS },
                initialMagnet = phoneHandoffMagnet,
                initialTorrentUri = incomingTorrent,
                onTorrentConsumed = onIncomingTorrentConsumed,
                onPrefillConsumed = { phoneHandoffMagnet = null },
                onPrepareRemote = { draft ->
                    magnet = draft
                    section = Section.TRANSFERS
                },
                onPrepareRemoteTorrent = { document ->
                    serverHandoffTorrent = document
                    section = Section.TRANSFERS
                }
            )
        } else if (server == null) {
            ConnectionScreen(
                endpoint = endpoint, username = username, password = password,
                error = error, loading = loading,
                rememberLogin = rememberLogin, savedLoginPresent = savedLoginPresent,
                savedProfiles = savedProfiles, credentialNotice = credentialNotice,
                onSelectProfile = { profile ->
                    endpoint = profile.credentials.endpoint
                    username = profile.credentials.username
                    password = profile.credentials.password
                    error = null
                    credentialNotice = "Selected saved profile. Connect to authenticate."
                },
                onForgetProfile = { profile ->
                    if (!loading) {
                        scope.launch {
                            val removed = withContext(Dispatchers.IO) {
                                try {
                                    val old = vault.load()
                                    val removedRoster = profileRoster.forget(profile.id)
                                    val removedLegacy = if (old?.endpoint?.trim() ==
                                            profile.credentials.endpoint.trim() &&
                                        old.username.trim() == profile.credentials.username.trim())
                                        vault.clear() else true
                                    removedRoster && removedLegacy
                                } catch (_: Exception) { false }
                            }
                            savedProfiles = withContext(Dispatchers.IO) { profileRoster.list() }
                            savedLoginPresent = vault.hasSavedLogin() || savedProfiles.isNotEmpty()
                            if (removed && endpoint == profile.credentials.endpoint &&
                                username == profile.credentials.username) password = ""
                            credentialNotice = if (removed) "Saved profile forgotten on this device."
                                else "Could not confirm full credential removal. Review saved profiles."
                        }
                    }
                },
                onRememberLogin = { enabled ->
                    rememberLogin = enabled
                    scope.launch {
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
                        rememberLogin = vault.rememberingEnabled()
                        savedProfiles = withContext(Dispatchers.IO) { profileRoster.list() }
                        savedLoginPresent = vault.hasSavedLogin() || savedProfiles.isNotEmpty()
                        credentialNotice = when {
                            !persisted -> "Could not save the credential remembering preference. Retry this change."
                            !enabled -> "Login remembering disabled across restarts; saved credentials erased."
                            else -> "Login remembering enabled. Credentials are saved after your next successful Connect."
                        }
                    }
                },
                onForgetLogin = {
                    rememberLogin = false
                    scope.launch {
                        val cleared = withContext(Dispatchers.IO) {
                            try {
                                val disabled = vault.setRememberingEnabled(false)
                                val rosterErased = profileRoster.clearAll()
                                disabled && rosterErased
                            } catch (_: Exception) { false }
                        }
                        savedProfiles = withContext(Dispatchers.IO) { profileRoster.list() }
                        savedLoginPresent = vault.hasSavedLogin() || savedProfiles.isNotEmpty()
                        if (cleared) {
                            password = ""
                            credentialNotice = "Saved login erased. This session's password field was cleared."
                        } else credentialNotice = "Could not erase saved login. Retry before leaving the app."
                    }
                },
                onLocalZipReview = { section = Section.ARCHIVES },
                onInspectSnapshot = { section = Section.SNAPSHOT_INSPECT },
                onLocalEngine = { section = Section.LOCAL },
                onEndpoint = { endpoint = it }, onUsername = { username = it },
                onPassword = { password = it },
                onConnect = {
                    if (!loading) {
                        loading = true
                        error = null
                        val ticket = remoteFence.begin("main")
                        // Freeze credentials for this one connection attempt.
                        // A later edit or lifecycle reset cannot redirect it.
                        val settings = RemoteCredentials(endpoint, username, password)
                        val saveThisLogin = rememberLogin
                        scope.launch {
                            try {
                                val candidate = RTorrentRemote(settings)
                                val v = candidate.version()
                                val list = candidate.listTransfers(ticket.view)
                                if (remoteFence.current(ticket) && server == null) {
                                    inspectionFence.invalidate()
                                    mutationGate.reset()
                                    transfers = list
                                    snapshotRevision++
                                    version = v
                                    aliasScope = endpoint.trim() + "\u0000" + username.trim()
                                    // Never carry another server/account's custom destination
                                    // into this Add draft. Blank selects the new server default.
                                    draftPath = ""
                                    server = candidate
                                    stale = false
                                    section = if (magnet.isNotBlank()) Section.ADD else Section.TRANSFERS
                                    currentView = ticket.view
                                    requestedView = ticket.view
                                    // A successful authenticated Connect is the only time
                                    // the default-on vault may save fresh credentials.
                                    // Failures never persist an unverified password.
                                    val retained = withContext(Dispatchers.IO) {
                                        try {
                                            if (saveThisLogin) {
                                                val singleSaved = vault.save(settings)
                                                val rosterSaved = profileRoster.saveVerified(settings)
                                                singleSaved && rosterSaved
                                            } else {
                                                val disabled = vault.setRememberingEnabled(false)
                                                val rosterErased = profileRoster.clearAll()
                                                disabled && rosterErased
                                            }
                                        } catch (_: Exception) { false }
                                    }
                                    savedProfiles = withContext(Dispatchers.IO) { profileRoster.list() }
                                    savedLoginPresent = vault.hasSavedLogin() || savedProfiles.isNotEmpty()
                                    credentialNotice = when {
                                        !retained -> "Connected, but login remembering settings could not be saved. " +
                                            "Confirm the preference before restarting."
                                        !saveThisLogin -> "Connected without remembering credentials. Your opt-out persists across restarts."
                                        else -> null
                                    }
                                    // Do not retain another copy in the screen field.
                                    password = ""
                                }
                            } catch (failure: Exception) {
                                if (remoteFence.current(ticket) && server == null)
                                    error = readableFailure(failure)
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
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (tab in Section.entries.filter { it != Section.ADD && it != Section.NAMES && it != Section.SNAPSHOT &&
                    it != Section.SNAPSHOT_INSPECT }) {
                    FilterChip(
                        selected = section == tab,
                        onClick = { section = tab },
                        label = { Text(tab.label) },
                        modifier = Modifier.height(48.dp)
                    )
                }
            }
            if (error != null) {
                Notice("Refresh problem", error.orEmpty(), Coral)
            }
            when (section) {
                Section.TRANSFERS -> TransfersScreen(
                    remote = connectedRemote, onLifecycleSettled = settledRemoteMutation,
                    onMutationStarted = beginRemoteMutation,
                    aliasStore = aliasStore, aliasScope = aliasScope,
                    aliasSessions = aliasSessions,
                    aliasRevision = aliasRevision, onAliasSaved = { aliasRevision++ },
                    list = transfers, view = currentView, loading = loading, stale = stale,
                    snapshotRevision = snapshotRevision,
                    query = query, onQuery = { query = it },
                    filter = filter, onFilter = { filter = it },
                    order = order, onOrder = { order = it },
                    onRefresh = refresh, onAdd = { section = Section.ADD },
                    onReviewNames = { section = Section.NAMES },
                    onExportSnapshot = { section = Section.SNAPSHOT },
                    onInspectSnapshot = { section = Section.SNAPSHOT_INSPECT },
                    onPreparePhone = { source ->
                        val prepared = TorrentIdentityHandoff.prepare(source.hash, source.name)
                        if (prepared != null) {
                            phoneHandoffMagnet = prepared
                            section = Section.LOCAL
                        }
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
                Section.SNAPSHOT -> SnapshotInventoryExportScreen(
                    transfers = transfers, view = currentView,
                    snapshotRevision = snapshotRevision,
                    stale = stale, loading = loading,
                    onBack = { section = Section.TRANSFERS }
                )
                Section.SNAPSHOT_INSPECT -> SnapshotDocumentInspectScreen(
                    onBack = { section = Section.TRANSFERS })
                Section.ARCHIVES -> LocalZipReviewScreen(onBack = { section = Section.TRANSFERS })
                Section.LOCAL -> LocalEngineScreen(
                    onBack = { section = Section.TRANSFERS },
                    initialMagnet = phoneHandoffMagnet,
                    initialTorrentUri = incomingTorrent,
                    onTorrentConsumed = onIncomingTorrentConsumed,
                    onPrefillConsumed = { phoneHandoffMagnet = null },
                    onPrepareRemote = { draft ->
                        magnet = draft
                        section = Section.ADD
                    },
                    onPrepareRemoteTorrent = { document ->
                        serverHandoffTorrent = document
                        section = Section.ADD
                    }
                )
                Section.PLACES -> PlacesScreen(transfers) { path ->
                    draftPath = path
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
                        rememberLogin = false
                        scope.launch {
                            val cleared = withContext(Dispatchers.IO) {
                                try {
                                    val disabled = vault.setRememberingEnabled(false)
                                    val rosterErased = profileRoster.clearAll()
                                    disabled && rosterErased
                                } catch (_: Exception) { false }
                            }
                            savedProfiles = withContext(Dispatchers.IO) { profileRoster.list() }
                            savedLoginPresent = vault.hasSavedLogin() || savedProfiles.isNotEmpty()
                            credentialNotice = if (cleared) "Saved login erased. Current connection remains active."
                                else "Could not erase saved login."
                        }
                    },
                    onRefresh = refresh,
                    onDisconnect = {
                        // Invalidate every in-flight read before clearing UI state.
                        remoteFence.invalidate()
                        inspectionFence.invalidate()
                        mutationGate.reset()
                        server = null
                        draftPath = ""
                        aliasScope = ""
                        aliasSessions.clear()
                        version = ""
                        transfers = emptyList()
                        expandedHash = null
                        files = null
                        filesError = null
                        filesLoading = false
                        loading = false
                        error = null
                        stale = false
                        currentView = "main"
                        requestedView = "main"
                        // Keep Disconnect separate from Forget. Reload the
                        // encrypted profile for one-tap reconnect in this session.
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                try { vault.load() } catch (_: Exception) { null }
                            }
                            if (server == null && rememberLogin && saved != null) {
                                endpoint = saved.endpoint
                                username = saved.username
                                password = saved.password
                            }
                        }
                    }
                )
                Section.ADD -> {
                    val recentPlaces = remember(aliasScope, recentPlaceRevision) {
                        if (aliasScope.isBlank()) emptyList()
                        else recentPlaceStore.places(aliasScope)
                    }
                    VerifiedAddScreen(
                        remote = connectedRemote, magnet = magnet, destination = draftPath,
                        initialTorrent = serverHandoffTorrent,
                        onInitialTorrentConsumed = { serverHandoffTorrent = null },
                        onMagnet = { magnet = it }, onDestination = { draftPath = it },
                        onBack = { draftPath = ""; section = Section.TRANSFERS },
                        onComplete = refresh,
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
            }
        }
    }
}

private fun readableFailure(failure: Exception): String = when (failure) {
    is java.net.UnknownHostException -> "The server hostname could not be resolved."
    is java.net.SocketTimeoutException -> "The server did not respond before the timeout. If a command was submitted, its outcome must be checked before retrying."
    is java.net.ConnectException -> "The server did not accept the connection."
    is java.io.IOException -> "Network or proxy error. Check your connection and server settings."
    else -> "The server returned an incompatible or unsupported RPC response. Check its configuration before retrying an operation."
}

@Composable
private fun ConnectionScreen(
    endpoint: String, username: String, password: String, error: String?, loading: Boolean,
    rememberLogin: Boolean, savedLoginPresent: Boolean,
    savedProfiles: List<RememberedRemoteProfileRoster.Profile>, credentialNotice: String?,
    onRememberLogin: (Boolean) -> Unit, onForgetLogin: () -> Unit,
    onSelectProfile: (RememberedRemoteProfileRoster.Profile) -> Unit,
    onForgetProfile: (RememberedRemoteProfileRoster.Profile) -> Unit,
    onLocalZipReview: () -> Unit,
    onInspectSnapshot: () -> Unit,
    onLocalEngine: () -> Unit,
    onEndpoint: (String) -> Unit, onUsername: (String) -> Unit,
    onPassword: (String) -> Unit, onConnect: () -> Unit
) {
    var pendingForget by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ColoredPanel("Hello, server.", "Connect an existing rTorrent installation. Chunks connects over protected XML-RPC. Torrent changes require separate explicit confirmation.", Lilac)
        }
        item {
            OutlinedButton(onClick = onLocalZipReview,
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Inspect a local ZIP without connecting")
            }
        }
        item {
            OutlinedButton(onClick = onInspectSnapshot,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Inspect .chunksnap backup (offline)")
            }
        }
        item {
            OutlinedButton(onClick = onLocalEngine,
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Download on this phone (experimental)")
            }
        }
        if (rememberLogin && savedProfiles.isNotEmpty()) {
            item {
                Text("Saved servers", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text("Choose an account, then Connect. No server is contacted just by selecting it.",
                    style = MaterialTheme.typography.bodySmall)
            }
            items(savedProfiles, key = { "saved-profile-" + it.id }) { profile ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        pendingForget = null
                        onSelectProfile(profile)
                    }, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Use " + profile.label)
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
                        OutlinedButton(onClick = { pendingForget = profile.id },
                            enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Forget " + profile.label)
                        }
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = endpoint, onValueChange = onEndpoint,
                label = { Text("ruTorrent web URL or RPC address") },
                placeholder = { Text("https://server.example/rutorrent/") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = username, onValueChange = onUsername,
                label = { Text("Username, if required") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = password, onValueChange = onPassword,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
        }
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
        item {
            Button(
                onClick = onConnect,
                enabled = !loading && endpoint.isNotBlank(),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text(if (loading) "Connecting…" else "Connect to rTorrent") }
        }
        if (error != null) item { Notice("Connection unsuccessful", error, Coral) }
        item {
            Text("Protected HTTPS is required for remote servers. Login is encrypted and stored only on this device unless you opt out. No torrents or files are changed by connecting.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF595575))
        }
    }
}

@Composable
private fun TransfersScreen(
    remote: RTorrentRemote, onLifecycleSettled: () -> Unit,
    onMutationStarted: () -> Boolean,
    aliasStore: TransferAliasStore, aliasScope: String,
    aliasSessions: MutableMap<String, TransferAliasEditSession>,
    aliasRevision: Int, onAliasSaved: () -> Unit,
    list: List<RemoteTransfer>, view: String, loading: Boolean, stale: Boolean,
    snapshotRevision: Long,
    query: String, onQuery: (String) -> Unit, filter: TransferFilter,
    onFilter: (TransferFilter) -> Unit, order: TransferOrder,
    onOrder: (TransferOrder) -> Unit, onRefresh: () -> Unit, onAdd: () -> Unit,
    onReviewNames: () -> Unit, onExportSnapshot: () -> Unit,
    onInspectSnapshot: () -> Unit,
    onPreparePhone: (RemoteTransfer) -> Unit,
    selectedHash: String?, files: List<RemoteFile>?, filesError: String?,
    filesLoading: Boolean, onInspect: (RemoteTransfer) -> Unit
) {
    val knownDownloadingHashes = remember(list) {
        RemoteQueueActivityContext.hashesReportingDownloadTraffic(list)
    }
    val visible = remember(list, query, filter, order, aliasRevision, aliasScope) {
        TransferCatalog.visible(list, query, filter, order) { transfer ->
            aliasStore.get(aliasScope, transfer.hash) ?: transfer.name
        }
    }
    val listState = rememberLazyListState()
    val topControlScope = rememberCoroutineScope()
    val context = LocalContext.current
    val membershipStore = remember(context) { TransferCollectionStore(context) }
    // Selection is scoped to authenticated account + rTorrent view. A newly
    // accepted queue revision clears it: never apply an old batch to new rows.
    var selecting by remember(aliasScope, view) { mutableStateOf(false) }
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
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Keep the final item reachable above the floating action.
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ColoredPanel(
                    "It's all here.",
                    list.size.toString() + " transfers in the '" + view + "' server view" +
                        if (stale) " · last known snapshot" else " · refreshed on demand",
                    Lime
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAdd, enabled = !loading && !stale,
                        modifier = Modifier.weight(1f).height(52.dp)) {
                        Text("+ Add torrent")
                    }
                    OutlinedButton(onClick = onRefresh, enabled = !loading,
                        modifier = Modifier.height(52.dp)) {
                        Text(if (loading) "Refreshing…" else "Refresh")
                    }
                }
                OutlinedButton(onClick = onReviewNames,
                    modifier = Modifier.fillMaxWidth().height(52.dp)) {
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
                OutlinedButton(
                    onClick = {
                        selecting = !selecting
                        batchHashes = emptySet()
                        batchConfirm = false
                        batchMessage = null
                    },
                    enabled = !loading && !stale && !batchBusy && list.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) {
                    Text(if (selecting) "Exit batch selection" else "Select transfers for a batch")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Down", Units.bytes(list.sumOf { it.downBytesPerSecond ?: 0 }) + "/s", Lilac,
                    Modifier.weight(1f))
                StatTile("Up", Units.bytes(list.sumOf { it.upBytesPerSecond ?: 0 }) + "/s", Coral,
                    Modifier.weight(1f))
                StatTile("Start set", list.count { it.started }.toString(),
                    Lime, Modifier.weight(1f))
            }
        }
        item {
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
            OutlinedTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                label = { Text("Search names or Places") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in TransferFilter.entries) {
                    FilterChip(selected = filter == option, onClick = { onFilter(option) },
                        label = { Text(option.label) }, modifier = Modifier.height(48.dp))
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(visible.size.toString() + " shown", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = {
                    val choices = TransferOrder.entries
                    onOrder(choices[(choices.indexOf(order) + 1) % choices.size])
                }, modifier = Modifier.height(48.dp)) {
                    Text("Sort: " + order.label)
                }
            }
        }
        if (selecting) item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Lilac),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Batch collections", fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge)
                    Text("${batchHashes.size} selected from the loaded '$view' view. " +
                        "Select shown means only the ${visible.size} filtered matches " +
                        "currently loaded by Chunks, not hidden views or unloaded transfers.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text("Select shown")
                        }
                        OutlinedButton(onClick = {
                            batchHashes = emptySet()
                            batchConfirm = false
                        }, enabled = !batchBusy && batchHashes.isNotEmpty(),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
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
                }
            }
        }
        if (visible.isEmpty()) item {
            ColoredPanel("Nothing in this view.", "Try another filter, search, or server view.", Lilac)
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
                transfer = transfer, expanded = selectedHash == transfer.hash,
                snapshotRevision = snapshotRevision,
                otherTransferDownloading = RemoteQueueActivityContext.otherTransferReportsDownloading(
                    transfer, knownDownloadingHashes, !stale && !loading
                ),
                files = files, filesLoading = filesLoading, filesError = filesError,
                onInspect = { onInspect(transfer) },
                onPreparePhone = { onPreparePhone(transfer) }
            )
            }
        }
        item {
            Text("Server snapshot. Start/stop and main-view session removal each require exact-target confirmation and readback. Session removal does not request payload deletion.",
                style = MaterialTheme.typography.bodySmall)
        }
        }
        ReturnToTopControl(
            visible = showBackToTop,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 22.dp),
            onClick = {
                topControlScope.launch {
                    if (ReturnToTopPolicy.animationsEnabled(context)) listState.animateScrollToItem(0)
                    else listState.scrollToItem(0)
                }
            }
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
    transfer: RemoteTransfer, expanded: Boolean,
    snapshotRevision: Long,
    otherTransferDownloading: Boolean,
    files: List<RemoteFile>?, filesLoading: Boolean, filesError: String?,
    onInspect: () -> Unit,
    onPreparePhone: () -> Unit
) {
    var confirmPhoneHandoff by remember(transfer.hash) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, Ink),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(alias ?: transfer.name, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (alias != null) {
                Text("Original torrent: " + transfer.name,
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFF595575),
                    maxLines = if (expanded) 5 else 1, overflow = TextOverflow.Ellipsis)
            }
            TransferAliasEditor(
                originalName = transfer.name, hash = transfer.hash,
                alias = alias, onSave = onAliasSave, session = aliasSession
            )
            ChunkedProgressBar(
                progress = transfer.fractionComplete,
                modifier = Modifier.fillMaxWidth(),
                activeColor = Blue, trackColor = Lilac
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text((transfer.fractionComplete * 100).toInt().toString() + "% · " +
                    Units.bytes(transfer.completedBytes) + " of " + Units.bytes(transfer.sizeBytes),
                    style = MaterialTheme.typography.bodySmall)
                Text(if (transfer.started) "Start state set" else "Stopped",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
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
            if (transfer.downBytesPerSecond != null) {
                Text("↓ " + Units.bytes(transfer.downBytesPerSecond) + "/s" +
                    "     ↑ " + Units.bytes(transfer.upBytesPerSecond ?: 0) + "/s" +
                    "     ratio " + ((transfer.ratioThousandths ?: 0L) / 1000.0).toString(),
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("Place: " + transfer.directory, style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF595575), maxLines = if (expanded) 5 else 2,
                overflow = TextOverflow.Ellipsis)
            val suggestion = remember(transfer.name) { Classifier.suggest(transfer.name) }
            if (suggestion.category != "Unsorted") {
                Text("Suggested: " + suggestion.category +
                    (suggestion.collection?.let { " / " + it } ?: ""),
                    style = MaterialTheme.typography.labelLarge, color = Blue)
            }
            OutlinedButton(onClick = onInspect, modifier = Modifier.height(48.dp)) {
                Text(if (expanded) "Hide files" else "Inspect files")
            }
            if (TorrentIdentityHandoff.prepare(transfer.hash, transfer.name) != null) {
                OutlinedButton(onClick = { confirmPhoneHandoff = true },
                    enabled = !stale, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Prepare torrent on phone (identity only)")
                }
                if (confirmPhoneHandoff) {
                    Text("Only this torrent's v1 hash/name becomes a magnet Add draft " +
                        "on your phone. No downloaded bytes move, and the phone may " +
                        "need to download everything again. Private/tracker-only " +
                        "torrents may not find peers. The server transfer stays intact.",
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
            TransferLifecycleControl(
                remote = remote, transfer = transfer, snapshotStale = stale,
                snapshotRevision = snapshotRevision,
                onMutationStarted = onMutationStarted,
                onSettled = onLifecycleSettled
            )
            TransferSessionRemoveControl(
                remote = remote, transfer = transfer, snapshotStale = stale,
                mainView = mainView, onMutationStarted = onMutationStarted,
                onSettled = onLifecycleSettled
            )
            if (expanded) {
                if (filesLoading) Text("Reading server file list…")
                if (filesError != null) Text(filesError, color = Color(0xFF9E3930))
                if (files != null) {
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
                colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(place.first, fontWeight = FontWeight.Bold)
                    Text(place.second.toString() + " transfers",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { onUsePlace(place.first) },
                        modifier = Modifier.height(48.dp)) { Text("Use in Add preview") }
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
            OutlinedTextField(
                value = manualQuery, onValueChange = { manualQuery = it; collectionPage = 0 },
                label = { Text("Find a transfer to organize") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
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
            Card(colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(transfer.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
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
    onRefresh: () -> Unit, onDisconnect: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ColoredPanel("Your rTorrent.", "Version " + version + " · protected remote session",
                if (stale) Coral else Lime)
        }
        item {
            Text("Server view", style = MaterialTheme.typography.titleLarge)
            Text("Different rTorrent servers organize views differently. Chunks never assumes the current view covers every torrent.",
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())) {
                for (view in listOf("main", "default", "started", "stopped")) {
                    FilterChip(selected = requestedView == view,
                        onClick = { onRequestedView(view) },
                        label = { Text(view) }, modifier = Modifier.height(48.dp))
                }
            }
        }
        item {
            Text("Showing " + count + " transfers from '" + displayedView + "'" +
                if (stale) " · stale snapshot" else "",
                style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Button(onClick = onRefresh, enabled = !loading, modifier = Modifier.fillMaxWidth().height(54.dp)) {
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
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().height(54.dp)) {
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
private fun ColoredPanel(title: String, message: String, color: Color) {
    Card(shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Ink)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Ink)
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Ink)
            Text(value, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold, color = Ink, maxLines = 1)
        }
    }
}

@Composable
private fun Notice(title: String, message: String, color: Color) {
    Card(shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Ink)
            Text(message, style = MaterialTheme.typography.bodySmall, color = Ink)
        }
    }
}
