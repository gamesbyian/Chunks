package io.github.gamesbyian.chunks

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.TransferCatalog
import io.github.gamesbyian.chunks.model.TransferFilter
import io.github.gamesbyian.chunks.model.TransferOrder
import io.github.gamesbyian.chunks.model.Units
import io.github.gamesbyian.chunks.organize.Classifier
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteFile
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import kotlinx.coroutines.launch

private val Ink = Color(0xFF262442)
private val Cream = Color(0xFFFFF8EC)
private val Lilac = Color(0xFFE6DAFF)
private val Lime = Color(0xFFDDF1A7)
private val Coral = Color(0xFFFFB69D)
private val Blue = Color(0xFF4662C6)

class MainActivity : ComponentActivity() {
    private var incomingMagnet by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The cream Compose surface extends behind the system status bar.
        // Use dark icons; white icons failed contrast review in actual API 35 captures.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        incomingMagnet = magnetFromIntent(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Blue, onPrimary = Color.White, surface = Cream,
                onSurface = Ink, background = Cream, secondary = Ink
            )) {
                Surface(modifier = Modifier.fillMaxSize()) { ChunksApp(incomingMagnet) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingMagnet = magnetFromIntent(intent)
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
    TRANSFERS("Transfers"), PLACES("Places"), ORGANIZE("Organize"), SERVER("Server"), ADD("Add")
}

@Composable
private fun ChunksApp(sharedMagnet: String?) {
    var endpoint by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var server by remember { mutableStateOf<RTorrentRemote?>(null) }
    var version by remember { mutableStateOf("") }
    var transfers by remember { mutableStateOf<List<RemoteTransfer>>(emptyList()) }
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
    var draftPath by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val aliasStore = remember(context) { TransferAliasStore(context) }
    var aliasScope by remember { mutableStateOf("") }
    var aliasRevision by remember { mutableStateOf(0) }
    val remoteFence = remember { RemoteUiRequestFence() }
    val inspectionFence = remember { RemoteUiRequestFence() }

    // Incoming Android Sharesheet/VIEW links prefill a local draft. Never auto-submit.
    LaunchedEffect(sharedMagnet) {
        if (sharedMagnet != null) {
            magnet = sharedMagnet
            if (server != null) section = Section.ADD
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
                        if (server === remote) error = null
                        try {
                            val snapshot = remote.listTransfers(ticket.view)
                            if (server === remote && remoteFence.current(ticket)) {
                                transfers = snapshot
                                currentView = ticket.view
                                stale = false
                                // Previous file inspections belong to the OLD
                                // snapshot and must not arrive after this one.
                                inspectionFence.invalidate()
                                expandedHash = null
                                files = null
                                filesError = null
                                filesLoading = false
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
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)) {
            Text("chunks.", style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Black, color = Ink)
            Text("Your downloads have places to be.", style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF595575))
        }

        if (server == null) {
            ConnectionScreen(
                endpoint = endpoint, username = username, password = password,
                error = error, loading = loading,
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
                        scope.launch {
                            try {
                                val candidate = RTorrentRemote(settings)
                                val v = candidate.version()
                                val list = candidate.listTransfers(ticket.view)
                                if (remoteFence.current(ticket) && server == null) {
                                    inspectionFence.invalidate()
                                    transfers = list
                                    version = v
                                    aliasScope = endpoint
                                    server = candidate
                                    stale = false
                                    section = if (magnet.isNotBlank()) Section.ADD else Section.TRANSFERS
                                    currentView = ticket.view
                                    requestedView = ticket.view
                                    // Do not keep a second plaintext copy in the fields.
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
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (tab in Section.entries.filter { it != Section.ADD }) {
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
                    remote = requireNotNull(server), onLifecycleSettled = refresh,
                    aliasStore = aliasStore, aliasScope = aliasScope,
                    aliasRevision = aliasRevision, onAliasSaved = { aliasRevision++ },
                    list = transfers, view = currentView, loading = loading, stale = stale,
                    query = query, onQuery = { query = it },
                    filter = filter, onFilter = { filter = it },
                    order = order, onOrder = { order = it },
                    onRefresh = refresh, onAdd = { section = Section.ADD },
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
                Section.PLACES -> PlacesScreen(transfers) { path ->
                    draftPath = path
                    section = Section.ADD
                }
                Section.ORGANIZE -> OrganizeScreen(transfers)
                Section.SERVER -> ServerScreen(
                    version = version, displayedView = currentView,
                    requestedView = requestedView, onRequestedView = { requestedView = it },
                    loading = loading, stale = stale, count = transfers.size,
                    onRefresh = refresh,
                    onDisconnect = {
                        // Invalidate every in-flight read before clearing UI state.
                        remoteFence.invalidate()
                        inspectionFence.invalidate()
                        server = null
                        aliasScope = ""
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
                    }
                )
                Section.ADD -> VerifiedAddScreen(
                    remote = requireNotNull(server), magnet = magnet, destination = draftPath,
                    onMagnet = { magnet = it }, onDestination = { draftPath = it },
                    onBack = { section = Section.TRANSFERS },
                    onComplete = refresh
                )
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
    onEndpoint: (String) -> Unit, onUsername: (String) -> Unit,
    onPassword: (String) -> Unit, onConnect: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ColoredPanel("Hello, server.", "Connect an existing rTorrent installation. Chunks connects over protected XML-RPC. Torrent changes require separate explicit confirmation.", Lilac)
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
            Button(
                onClick = onConnect,
                enabled = !loading && endpoint.isNotBlank(),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text(if (loading) "Connecting…" else "Connect to rTorrent") }
        }
        if (error != null) item { Notice("Connection unsuccessful", error, Coral) }
        item {
            Text("Protected HTTPS is required for remote servers. Credentials stay in memory only. No torrents or files are changed.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF595575))
        }
    }
}

@Composable
private fun TransfersScreen(
    remote: RTorrentRemote, onLifecycleSettled: () -> Unit,
    aliasStore: TransferAliasStore, aliasScope: String,
    aliasRevision: Int, onAliasSaved: () -> Unit,
    list: List<RemoteTransfer>, view: String, loading: Boolean, stale: Boolean,
    query: String, onQuery: (String) -> Unit, filter: TransferFilter,
    onFilter: (TransferFilter) -> Unit, order: TransferOrder,
    onOrder: (TransferOrder) -> Unit, onRefresh: () -> Unit, onAdd: () -> Unit,
    selectedHash: String?, files: List<RemoteFile>?, filesError: String?,
    filesLoading: Boolean, onInspect: (RemoteTransfer) -> Unit
) {
    val visible = remember(list, query, filter, order, aliasRevision, aliasScope) {
        TransferCatalog.visible(list, query, filter, order) { transfer ->
            aliasStore.get(aliasScope, transfer.hash) ?: transfer.name
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
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
                    Button(onClick = onAdd, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text("+ Add torrent")
                    }
                    OutlinedButton(onClick = onRefresh, enabled = !loading,
                        modifier = Modifier.height(52.dp)) {
                        Text(if (loading) "Refreshing…" else "Refresh")
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Down", Units.bytes(list.sumOf { it.downBytesPerSecond ?: 0 }) + "/s", Lilac,
                    Modifier.weight(1f))
                StatTile("Up", Units.bytes(list.sumOf { it.upBytesPerSecond ?: 0 }) + "/s", Coral,
                    Modifier.weight(1f))
                StatTile("Active", list.count { it.started }.toString(), Lime, Modifier.weight(1f))
            }
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
        if (visible.isEmpty()) item {
            ColoredPanel("Nothing in this view.", "Try another filter, search, or server view.", Lilac)
        }
        items(visible, key = { it.hash }) { transfer ->
            TransferCard(
                remote = remote, stale = stale, mainView = view == "main",
                alias = aliasStore.get(aliasScope, transfer.hash),
                onAliasSave = { proposed ->
                    val saved = aliasStore.save(aliasScope, transfer.hash, proposed)
                    if (saved) onAliasSaved()
                    saved
                },
                onLifecycleSettled = onLifecycleSettled,
                transfer = transfer, expanded = selectedHash == transfer.hash,
                files = files, filesLoading = filesLoading, filesError = filesError,
                onInspect = { onInspect(transfer) }
            )
        }
        item {
            Text("Server snapshot. Start/stop and main-view session removal each require exact-target confirmation and readback. Session removal does not request payload deletion.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TransferCard(
    remote: RTorrentRemote, stale: Boolean, mainView: Boolean,
    alias: String?, onAliasSave: (String?) -> Boolean,
    onLifecycleSettled: () -> Unit,
    transfer: RemoteTransfer, expanded: Boolean,
    files: List<RemoteFile>?, filesLoading: Boolean, filesError: String?,
    onInspect: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
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
                alias = alias, onSave = onAliasSave
            )
            LinearProgressIndicator(
                progress = { transfer.fractionComplete },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = Blue, trackColor = Lilac
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text((transfer.fractionComplete * 100).toInt().toString() + "% · " +
                    Units.bytes(transfer.completedBytes) + " of " + Units.bytes(transfer.sizeBytes),
                    style = MaterialTheme.typography.bodySmall)
                Text(if (transfer.started) "Active" else "Stopped",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
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
            TransferLifecycleControl(
                remote = remote, transfer = transfer, snapshotStale = stale,
                onSettled = onLifecycleSettled
            )
            TransferSessionRemoveControl(
                remote = remote, transfer = transfer, snapshotStale = stale,
                mainView = mainView, onSettled = onLifecycleSettled
            )
            if (expanded) {
                if (filesLoading) Text("Reading server file list…")
                if (filesError != null) Text(filesError, color = Color(0xFF9E3930))
                if (files != null) {
                    TransferFilePager(transfer.hash, files)
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
private fun OrganizeScreen(list: List<RemoteTransfer>) {
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
    loading: Boolean, stale: Boolean, count: Int, onRefresh: () -> Unit, onDisconnect: () -> Unit
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
        item {
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text("Disconnect")
            }
        }
        item {
            Text("Credentials remain in memory only. Stopped Add and Start/Stop require confirmation. Moving or deleting physical files is not yet supported.",
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
