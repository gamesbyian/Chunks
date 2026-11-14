package io.github.gamesbyian.chunks

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteDirectoryLookup
import io.github.gamesbyian.chunks.rpc.RemoteFolderCreateCapability
import io.github.gamesbyian.chunks.rpc.RemoteFolderCreateOutcome
import io.github.gamesbyian.chunks.rpc.RuTorrentFileManager
import io.github.gamesbyian.chunks.rpc.RuTorrentDirectoryListing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Read-only server-backed picker. Opening, moving between folders and cancelling
 * cannot mutate a torrent or the Add draft. _getdir has no trusted mkdir API.
 */
@Composable
internal fun RemoteFolderPicker(
    remote: RTorrentRemote,
    connectionName: String,
    initialFolder: String?,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var observedRoot by remember(remote) { mutableStateOf<String?>(null) }
    var current by remember(remote) { mutableStateOf<String?>(null) }
    var result by remember(remote) { mutableStateOf<RemoteDirectoryLookup?>(null) }
    var revision by remember(remote) { mutableIntStateOf(0) }
    var manualMode by remember(remote) { mutableStateOf(false) }
    var manualPath by remember(remote) { mutableStateOf(initialFolder.orEmpty()) }
    var createCapability by remember(remote) { mutableStateOf<RemoteFolderCreateCapability?>(null) }
    var showNewFolder by remember(remote) { mutableStateOf(false) }
    var newFolderName by remember(remote) { mutableStateOf("") }
    var creating by remember(remote) { mutableStateOf(false) }
    var createStatus by remember(remote) { mutableStateOf<String?>(null) }
    var uncertainPath by remember(remote) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(remote, observedRoot) {
        createCapability = null
        val root = observedRoot ?: return@LaunchedEffect
        createCapability = withContext(Dispatchers.IO) {
            remote.folderCreateCapability(root)
        }
    }

    LaunchedEffect(remote, current, revision, manualMode) {
        if (manualMode) return@LaunchedEffect
        result = null
        val requested = current.orEmpty()
        val loaded = withContext(Dispatchers.IO) { remote.browseDirectories(requested) }
        if (current == null && loaded is RemoteDirectoryLookup.Ready) {
            observedRoot = loaded.page.path
            val desired = initialFolder?.let(RuTorrentDirectoryListing::canonical)
            val starting = if (desired != null &&
                RuTorrentDirectoryListing.contains(loaded.page.path, desired))
                desired else loaded.page.path
            current = starting
            if (starting == loaded.page.path) result = loaded
        } else {
            result = loaded
        }
    }

    val currentPage = (result as? RemoteDirectoryLookup.Ready)?.page
    val root = observedRoot
    val parent = currentPage?.path?.substringBeforeLast('/', "").orEmpty().ifBlank { "/" }
    val canGoUp = currentPage != null && root != null &&
        currentPage.path != root && RuTorrentDirectoryListing.contains(root, parent)
    val manualValid = RecentRemotePlaceStore.isValidParent(manualPath)

    Dialog(onDismissRequest = { if (!creating) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Save to · " + connectionName,
                    style = MaterialTheme.typography.titleLarge)
                if (manualMode) {
                    Text("Enter a server folder manually. Chunks will check the actual destination during Add.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = manualPath,
                        onValueChange = { manualPath = it },
                        label = { Text("Remote folder path") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 3
                    )
                } else {
                    Text(currentPage?.path ?: current ?: "Checking server folders…",
                        style = MaterialTheme.typography.bodyMedium)
                    when (val state = result) {
                        null -> Text("Loading server folders…")
                        is RemoteDirectoryLookup.Unavailable -> {
                            val message = when (state.reason) {
                                RemoteDirectoryLookup.Reason.UNSUPPORTED ->
                                    "This server does not expose the supported folder-listing plugin."
                                RemoteDirectoryLookup.Reason.AUTHENTICATION ->
                                    "The server denied folder browsing. Check this connection's access."
                                RemoteDirectoryLookup.Reason.OUTSIDE_SCOPE ->
                                    "The server changed the requested directory. No folder was selected."
                                RemoteDirectoryLookup.Reason.INVALID_RESPONSE ->
                                    "The server returned an invalid folder listing."
                                RemoteDirectoryLookup.Reason.CONNECTION ->
                                    "Cannot list server folders right now."
                            }
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                        is RemoteDirectoryLookup.Ready -> {
                            if (state.page.folders.isEmpty()) Text("No subfolders here")
                        }
                    }
                    LazyColumn(Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (canGoUp) item {
                            TextButton(
                                onClick = { current = parent; showNewFolder = false; createStatus = null },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) { Text("↑ Parent folder") }
                        }
                        items(currentPage?.folders.orEmpty(), key = { it }) { folder ->
                            OutlinedButton(
                                onClick = {
                                    val next = if (currentPage?.path == "/") "/" + folder
                                        else currentPage?.path + "/" + folder
                                    current = next
                                    showNewFolder = false
                                    createStatus = null
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .semantics { contentDescription = "Open folder " + folder }
                            ) { Image(painter = painterResource(R.drawable.chunks_ic_folder),
                                contentDescription = null, modifier = Modifier.size(24.dp))
                            Text(folder + "  ›") }
                        }
                    }
                    createStatus?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (createCapability is RemoteFolderCreateCapability.Ready &&
                        currentPage != null) {
                        if (!showNewFolder) TextButton(
                            onClick = {
                                showNewFolder = true
                                newFolderName = ""
                                createStatus = null
                            },
                            enabled = !creating,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("New folder") }
                        if (showNewFolder) {
                            Text("Create folder in " + currentPage.path,
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = newFolderName,
                                onValueChange = { if (!creating) newFolderName = it },
                                label = { Text("Folder name") },
                                enabled = !creating,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            if (newFolderName.isNotBlank() &&
                                RuTorrentFileManager.validLeaf(newFolderName)) {
                                Text("New location: " + currentPage.path.trimEnd('/') + "/" + newFolderName,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Row(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { showNewFolder = false },
                                    enabled = !creating, modifier = Modifier.heightIn(min = 48.dp)
                                ) { Text("Cancel folder") }
                                Button(
                                    onClick = {
                                        val rootForCreate = root
                                        val parentForCreate = currentPage.path
                                        val nameForCreate = newFolderName
                                        if (rootForCreate != null &&
                                            RuTorrentFileManager.validLeaf(nameForCreate) &&
                                            (parentForCreate.trimEnd('/') + "/" + nameForCreate) != uncertainPath) {
                                            creating = true
                                            createStatus = null
                                            scope.launch {
                                                try {
                                                    val outcome = withContext(Dispatchers.IO) {
                                                        remote.createServerFolder(
                                                            rootForCreate, parentForCreate, nameForCreate)
                                                    }
                                                    when (outcome) {
                                                        is RemoteFolderCreateOutcome.Created -> {
                                                            showNewFolder = false
                                                            current = outcome.path
                                                            revision++
                                                            createStatus = "Folder created and verified."
                                                            uncertainPath = null
                                                        }
                                                        RemoteFolderCreateOutcome.AlreadyExists ->
                                                            createStatus = "That folder already exists. Choose it from the list."
                                                        RemoteFolderCreateOutcome.Refused ->
                                                            createStatus = "Folder creation was refused. No success was verified."
                                                        RemoteFolderCreateOutcome.Uncertain -> {
                                                            uncertainPath = parentForCreate.trimEnd('/') + "/" + nameForCreate
                                                            showNewFolder = false
                                                            createStatus = "Result uncertain; check the folder listing. Chunks will not repeat creation."
                                                            revision++
                                                        }
                                                    }
                                                } finally { creating = false }
                                            }
                                        }
                                    },
                                    enabled = !creating &&
                                        RuTorrentFileManager.validLeaf(newFolderName) &&
                                        (currentPage.path.trimEnd('/') + "/" + newFolderName) != uncertainPath,
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) { Text(if (creating) "Creating…" else "Create folder") }
                            }
                        }
                    } else if (createCapability is RemoteFolderCreateCapability.Unavailable) {
                        Text("This server does not expose a verified folder-creation capability. You can still browse or enter a path.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { manualMode = !manualMode },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(if (manualMode) "Browse" else "Enter path") }
                    TextButton(onClick = { revision++ },
                        enabled = !manualMode && !creating, modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Retry") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDismiss, enabled = !creating,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text("Cancel") }
                    Button(
                        onClick = {
                            if (manualMode) onChoose(manualPath)
                            else currentPage?.path?.let(onChoose)
                        },
                        enabled = !creating && (if (manualMode) manualValid else currentPage != null),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) { Text(if (manualMode) "Use this path" else "Use this folder") }
                }
            }
        }
    }
}
