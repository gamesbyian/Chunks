package io.github.gamesbyian.chunks

import android.content.Context
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalEngineConfig
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
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

    /** Starts the engine (and its listening socket) on first explicit use only. */
    @Synchronized
    fun get(): LocalTorrentEngine = engine ?: LocalTorrentEngine(LocalEngineConfig()).also {
        it.start()
        engine = it
    }

    /** The only destination offered in this slice: a real POSIX folder Chunks owns. */
    fun appDownloads(context: Context): File? =
        context.getExternalFilesDir("Downloads")?.apply { mkdirs() }?.takeIf { it.isDirectory }
}

private class PendingLocalAdd(val preview: TorrentMetainfoPreview, val bytes: ByteArray)

@Composable
internal fun LocalEngineScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var transfers by remember { mutableStateOf<List<LocalTransferStatus>>(emptyList()) }
    var pending by remember { mutableStateOf<PendingLocalAdd?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val destination = remember { LocalEngineHolder.appDownloads(context) }

    LaunchedEffect(Unit) {
        while (true) {
            transfers = withContext(Dispatchers.IO) {
                try { LocalEngineHolder.current()?.list().orEmpty() } catch (_: Exception) { emptyList() }
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
        Text("Downloads and seeds with Chunks' own engine while the app is open. Nothing connects to the network until you add a torrent. Background downloading and seeding are not supported yet, and Android may stop Chunks at any time. Peers and trackers can see this phone's IP address.")
        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy && destination != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Choose .torrent file") }
        if (destination == null) Text("Phone storage for Chunks is unavailable right now.",
            color = MaterialTheme.colorScheme.error)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        val add = pending
        if (add != null && destination != null) LocalAddReview(add.preview, destination,
            busy = busy,
            onConfirm = {
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try { LocalEngineHolder.get().addStopped(add.bytes, destination) }
                        catch (_: Exception) { LocalAddResult.Refused("The local engine could not add this torrent") }
                    }
                    message = when (result) {
                        is LocalAddResult.AddedStopped -> null
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
                    busy = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) {
                            val engine = LocalEngineHolder.get()
                            if (transfer.stopped) engine.resume(transfer.infoHash) else engine.pause(transfer.infoHash)
                        }
                        message = when (outcome.outcome) {
                            LocalCommandOutcome.VERIFIED -> null
                            LocalCommandOutcome.UNCERTAIN -> "The engine has not confirmed the change yet. Check the status before trying again."
                            LocalCommandOutcome.NOT_FOUND -> "This transfer is no longer in the phone engine."
                        }
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
        }
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
    transfer.state == LocalTransferState.DOWNLOADING_METADATA -> "Fetching metadata"
    transfer.state == LocalTransferState.DOWNLOADING -> "Downloading"
    transfer.state == LocalTransferState.SEEDING || transfer.state == LocalTransferState.FINISHED -> "Sharing"
    else -> "Unknown"
}

@Composable
internal fun LocalTransferCard(
    transfer: LocalTransferStatus,
    busy: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit
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
            }
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
