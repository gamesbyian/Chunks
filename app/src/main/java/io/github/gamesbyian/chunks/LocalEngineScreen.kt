package io.github.gamesbyian.chunks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalRateLimits
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.Units
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoPreview
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One engine per process. It lives only as long as Android keeps Chunks running. */
internal object LocalEngineHolder {
    private var engine: LocalTorrentEngine? = null

    /** The running engine, if the user has added anything; never starts networking. */
    @Synchronized
    fun current(): LocalTorrentEngine? = engine

    /**
     * Starts the engine (and its listening socket) on first explicit use, or to
     * restore transfers saved earlier. Restored transfers always come back stopped.
     */
    @Synchronized
    fun get(context: Context): LocalTorrentEngine = engine ?: LocalTorrentEngine(
        LocalEngineConfig(stateDirectory = stateDirectory(context))
    ).also {
        it.start()
        if (dhtPreferred(context)) it.setDhtEnabled(true)
        it.setRateLimits(rateLimitsPreferred(context))
        engine = it
    }

    private fun prefs(context: Context) = context.getSharedPreferences("local-engine", Context.MODE_PRIVATE)
    fun dhtPreferred(context: Context) = prefs(context).getBoolean("dht", false)

    /** Persist the user's choice and apply it to a running engine; never starts one. */
    @Synchronized
    fun setDhtPreferred(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("dht", enabled).apply()
        engine?.setDhtEnabled(enabled)
    }

    fun rateLimitsPreferred(context: Context) = prefs(context).let {
        LocalRateLimits(it.getInt("download-limit", 0).coerceAtLeast(0), it.getInt("upload-limit", 0).coerceAtLeast(0))
    }

    /** Persist speed limits and apply them to a running engine; returns what the engine reads back. */
    @Synchronized
    fun setRateLimitsPreferred(context: Context, limits: LocalRateLimits): LocalRateLimits? {
        prefs(context).edit().putInt("download-limit", limits.downloadBytesPerSecond)
            .putInt("upload-limit", limits.uploadBytesPerSecond).apply()
        return engine?.setRateLimits(limits)
    }

    /** Start only if an earlier session left transfers to restore. */
    @Synchronized
    fun restoreIfSaved(context: Context): LocalTorrentEngine? = engine
        ?: if (stateDirectory(context).listFiles().orEmpty().any { it.name.endsWith(".resume") }) get(context) else null

    private fun stateDirectory(context: Context) = File(context.filesDir, "local-engine")

    /** The only destination offered in this slice: a real POSIX folder Chunks owns. */
    fun appDownloads(context: Context): File? =
        context.getExternalFilesDir("Downloads")?.apply { mkdirs() }?.takeIf { it.isDirectory }
}

private class PendingLocalAdd(val preview: TorrentMetainfoPreview, val bytes: ByteArray)

@Composable
internal fun LocalEngineScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val collectionStore = remember(context) { TransferCollectionStore(context) }
    var transfers by remember { mutableStateOf<List<LocalTransferStatus>>(emptyList()) }
    var filesFor by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<LocalTransferFile>?>(null) }
    var pending by remember { mutableStateOf<PendingLocalAdd?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var magnet by remember { mutableStateOf("") }
    var dht by remember { mutableStateOf(LocalEngineHolder.dhtPreferred(context)) }
    val destination = remember { LocalEngineHolder.appDownloads(context) }
    // The ongoing notification is how Android lets downloads continue in the background.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    var restoreNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        restoreNotice = withContext(Dispatchers.IO) {
            try {
                val engine = LocalEngineHolder.restoreIfSaved(context)
                val problems = engine?.restoreProblems.orEmpty()
                if (problems.isEmpty()) null
                else problems.size.toString() + " saved transfer record(s) could not be restored and were left untouched."
            } catch (_: Exception) { "Saved transfers could not be restored." }
        }
        var tick = 0
        while (true) {
            filesFor?.let { hash ->
                files = withContext(Dispatchers.IO) {
                    try { LocalEngineHolder.current()?.files(hash) } catch (_: Exception) { null }
                }
            }
            transfers = withContext(Dispatchers.IO) {
                try { LocalEngineHolder.current()?.list().orEmpty() } catch (_: Exception) { emptyList() }
            }
            // Refresh resume data about every 30 s so progress survives process death.
            if (++tick % 30 == 0) withContext(Dispatchers.IO) {
                try { LocalEngineHolder.current()?.saveResumeData() } catch (_: Exception) { }
            }
            delay(1_000)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = null
        scope.launch {
            pending = withContext(Dispatchers.IO) {
                try {
                    val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
                    val bytes = input.use {
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(16_384)
                        while (true) {
                            val count = it.read(buffer)
                            if (count < 0) break
                            require(out.size() + count <= TorrentMetainfoReader.MAX_BYTES)
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                    PendingLocalAdd(TorrentMetainfoReader.preview(bytes), bytes)
                } catch (_: Exception) { null }
            }
            if (pending == null) message = "This document is not a supported v1 .torrent file or exceeds 2 MiB."
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onBack != null) Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
        Text("On this phone (experimental)", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Text("Downloads and seeds with Chunks' own engine. While a transfer runs, an ongoing notification keeps it going in the background, with Stop all; Android may still stop it, and from Android 15 limits this kind of background transfer to about 6 hours a day, after which Chunks pauses everything. Nothing connects to the network until you add a torrent. Transfers are remembered and come back stopped after Chunks restarts. Peers and trackers can see this phone's IP address.")
        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy && destination != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Choose .torrent file") }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = dht, role = Role.Switch, onValueChange = { wanted ->
                dht = wanted
                scope.launch { withContext(Dispatchers.IO) { LocalEngineHolder.setDhtPreferred(context, wanted) } }
            }), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Find peers without trackers (DHT)")
                Text("Needed for most magnet links. Off by default: when on, this phone's IP address is shared with the public DHT network.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = dht, onCheckedChange = null)
        }
        OutlinedTextField(value = magnet, onValueChange = { magnet = it.trim() },
            label = { Text("Or paste a magnet link") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        if (magnet.isNotEmpty() && destination != null) {
            Text("Saves into Chunks' folder on phone storage. Chunks fetches only the torrent's description from the magnet's trackers and peers, then stops; no file data downloads until you press Start.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try { LocalEngineHolder.get(context).addMagnet(magnet, destination) }
                        catch (_: Exception) { LocalAddResult.Refused("The local engine could not add this magnet") }
                    }
                    message = (result as? LocalAddResult.Refused)?.reason
                    if (result !is LocalAddResult.Refused) magnet = ""
                    busy = false
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add magnet (metadata only)") }
        }
        if (destination == null) Text("Phone storage for Chunks is unavailable right now.",
            color = MaterialTheme.colorScheme.error)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        restoreNotice?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        val add = pending
        if (add != null && destination != null) LocalAddReview(add.preview, destination,
            busy = busy,
            onConfirm = {
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try { LocalEngineHolder.get(context).addStopped(add.bytes, destination) }
                        catch (_: Exception) { LocalAddResult.Refused("The local engine could not add this torrent") }
                    }
                    message = when (result) {
                        is LocalAddResult.AddedStopped, is LocalAddResult.FetchingMetadata -> null
                        is LocalAddResult.Refused -> result.reason
                    }
                    pending = null
                    busy = false
                }
            },
            onCancel = { pending = null })

        if (transfers.isEmpty()) Text("No transfers on this phone yet.", style = MaterialTheme.typography.bodySmall)
        transfers.forEach { transfer ->
            LocalTransferCard(transfer, busy,
                onToggle = {
                    if (transfer.stopped && Build.VERSION.SDK_INT >= 33 &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    busy = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            val engine = LocalEngineHolder.get(context)
                            if (transfer.stopped) engine.resume(transfer.infoHash) else engine.pause(transfer.infoHash)
                        }
                        var serviceProblem: String? = null
                        if (transfer.stopped && outcome.outcome != LocalCommandOutcome.NOT_FOUND) {
                            try { LocalEngineService.start(context) } catch (_: Exception) {
                                serviceProblem = "Android did not allow background running; keep Chunks open."
                            }
                        }
                        message = when (outcome.outcome) {
                            LocalCommandOutcome.VERIFIED -> serviceProblem
                            LocalCommandOutcome.UNCERTAIN -> "The engine has not confirmed the change yet. Check the status before trying again."
                            LocalCommandOutcome.NOT_FOUND -> "This transfer is no longer in the phone engine."
                        }
                        busy = false
                    }
                },
                files = files.takeIf { filesFor == transfer.infoHash },
                filesShown = filesFor == transfer.infoHash,
                onShowFiles = {
                    files = null
                    filesFor = if (filesFor == transfer.infoHash) null else transfer.infoHash
                },
                onFileWanted = { index, wanted ->
                    busy = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            LocalEngineHolder.current()?.setFileWanted(transfer.infoHash, index, wanted)
                                ?: LocalCommandOutcome.NOT_FOUND
                        }
                        message = if (outcome == LocalCommandOutcome.VERIFIED) null
                            else "The engine has not confirmed the file selection yet."
                        files = withContext(Dispatchers.IO) { LocalEngineHolder.current()?.files(transfer.infoHash) }
                        busy = false
                    }
                },
                onRemove = {
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { LocalEngineHolder.current()?.removeKeepingData(transfer.infoHash) }
                        busy = false
                    }
                })
            if (transfer.metadataKnown && transfer.progressPpm == 1_000_000) LocalLibraryCopyPanel(transfer)
        }
        LocalZipInterruptedExtractions()
        LocalSpeedLimits()
    }
}

@Composable
internal fun LocalAddReview(
    preview: TorrentMetainfoPreview,
    destination: File,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Review download", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            Text(preview.name, fontWeight = FontWeight.Bold)
            Text(preview.fileCount.toString() + (if (preview.fileCount == 1) " file, " else " files, ") +
                Units.bytes(preview.sizeBytes))
            Text("Saves into: Chunks' folder on phone storage (" + destination.absolutePath + "). Files here are removed if Chunks is uninstalled.")
            Text("It is added stopped. Nothing downloads until you press Start.", style = MaterialTheme.typography.bodySmall)
            if (preview.trackerCount == 0) Text("This torrent lists no tracker, so peers can only be found through DHT (if turned on) or its web sources.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onConfirm, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Add stopped")
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
        }
    }
}

private fun stateLabel(transfer: LocalTransferStatus): String = when {
    transfer.error != null -> "Error: " + transfer.error
    transfer.stopped -> "Stopped"
    transfer.state == LocalTransferState.CHECKING -> "Checking files"
    !transfer.metadataKnown || transfer.state == LocalTransferState.DOWNLOADING_METADATA -> "Fetching description (no file data)"
    transfer.state == LocalTransferState.DOWNLOADING -> "Downloading"
    transfer.state == LocalTransferState.SEEDING || transfer.state == LocalTransferState.FINISHED -> "Sharing"
    else -> "Unknown"
}

@Composable
internal fun LocalTransferCard(
    transfer: LocalTransferStatus,
    busy: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    files: List<LocalTransferFile>? = null,
    filesShown: Boolean = false,
    onShowFiles: () -> Unit = {},
    onFileWanted: (Int, Boolean) -> Unit = { _, _ -> }
) {
    var confirmRemove by remember(transfer.infoHash) { mutableStateOf(false) }
    val percent = transfer.progressPpm / 10_000
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(transfer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stateLabel(transfer) + " · " + percent + "% · " + transfer.peers + " peers")
            LinearProgressIndicator(progress = { transfer.progressPpm / 1_000_000f },
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = "$percent percent complete" })
            Text("Downloaded " + Units.bytes(transfer.payloadDownloadedBytes) + " · Shared " +
                Units.bytes(transfer.payloadUploadedBytes), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onToggle, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (transfer.stopped) "Start" else "Stop")
                }
                OutlinedButton(onClick = { confirmRemove = true }, enabled = !busy && !confirmRemove,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove…") }
                if (transfer.metadataKnown) OutlinedButton(onClick = onShowFiles, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (filesShown) "Hide files" else "Files")
                }
            }
            if (filesShown) LocalFileSelection(files, busy, transferKey = transfer.infoHash, onFileWanted = onFileWanted)
            if (confirmRemove) {
                Text("Remove from the phone engine only. Downloaded files stay in Chunks' folder; sharing of this torrent stops.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { confirmRemove = false; onRemove() }, enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove, keep files") }
                    OutlinedButton(onClick = { confirmRemove = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Keep")
                    }
                }
            }
        }
    }
}

/** Per-file download selection, bounded to one page even for huge multi-file torrents.
 * Unticking never deletes bytes already written. Page identity is scoped to the
 * transfer, not to a Compose list position or a periodically refreshed file list.
 */
@Composable
internal fun LocalFileSelection(
    files: List<LocalTransferFile>?,
    busy: Boolean,
    transferKey: String? = null,
    onFileWanted: (Int, Boolean) -> Unit
) {
    if (files == null) {
        Text("Loading files…", style = MaterialTheme.typography.bodySmall)
        return
    }
    val pageSize = 40
    val pageCount = if (files.isEmpty()) 1 else (files.size - 1) / pageSize + 1
    var requestedPage by remember(transferKey, files.size) { mutableStateOf(0) }
    val page = requestedPage.coerceIn(0, pageCount - 1)
    Text("Untick a file to skip downloading it. Already downloaded parts are kept.",
        style = MaterialTheme.typography.bodySmall)
    if (pageCount > 1) {
        Text("Files page " + (page + 1) + " of " + pageCount + " (" + files.size + " files)",
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { requestedPage = page - 1 }, enabled = !busy && page > 0,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Previous page") }
            OutlinedButton(onClick = { requestedPage = page + 1 },
                enabled = !busy && page + 1 < pageCount,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Next page") }
        }
    }
    files.drop(page * pageSize).take(pageSize).forEach { file ->
        // Multiplying done bytes by 100 can overflow Long for large files.
        val percent = if (file.sizeBytes <= 0L) 100 else
            (file.doneBytes.toDouble() / file.sizeBytes * 100).toInt().coerceIn(0, 100)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = file.wanted, enabled = !busy, role = Role.Checkbox,
                onValueChange = { onFileWanted(file.index, it) }),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = file.wanted, onCheckedChange = null)
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                Text(Units.bytes(file.sizeBytes) + " · " + percent + "%" + if (file.wanted) "" else " · skipped",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** KiB/s text to bytes per second; blank or 0 is unlimited, anything else invalid is null. */
internal fun parseSpeedLimit(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0
    val kib = trimmed.toIntOrNull() ?: return null
    return if (kib in 0..(Int.MAX_VALUE / 1024)) kib * 1024 else null
}

private fun speedText(bytesPerSecond: Int) = if (bytesPerSecond == 0) "" else (bytesPerSecond / 1024).toString()

@Composable
internal fun LocalSpeedLimits() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { LocalEngineHolder.rateLimitsPreferred(context) }
    var download by remember { mutableStateOf(speedText(saved.downloadBytesPerSecond)) }
    var upload by remember { mutableStateOf(speedText(saved.uploadBytesPerSecond)) }
    var result by remember { mutableStateOf<String?>(null) }
    val downloadLimit = parseSpeedLimit(download)
    val uploadLimit = parseSpeedLimit(upload)
    Text("Speed limits", fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
    Text("In KiB per second; leave blank for unlimited. Devices on your own network are not limited.",
        style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(value = download, onValueChange = { download = it; result = null },
            label = { Text("Download") }, singleLine = true, isError = downloadLimit == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f))
        OutlinedTextField(value = upload, onValueChange = { upload = it; result = null },
            label = { Text("Upload") }, singleLine = true, isError = uploadLimit == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f))
    }
    OutlinedButton(onClick = {
        val wanted = LocalRateLimits(downloadLimit ?: return@OutlinedButton, uploadLimit ?: return@OutlinedButton)
        scope.launch {
            val applied = withContext(Dispatchers.IO) {
                try { Result.success(LocalEngineHolder.setRateLimitsPreferred(context, wanted)) }
                catch (e: Exception) { Result.failure(e) }
            }
            result = when {
                applied.isFailure -> "Saved, but the running engine did not accept the limits."
                applied.getOrNull() == null -> "Saved. Applies when the engine starts."
                applied.getOrNull() == wanted -> "Applied."
                else -> "Saved, but the engine reports different limits."
            }
        }
    }, enabled = downloadLimit != null && uploadLimit != null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save speed limits") }
    result?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}
