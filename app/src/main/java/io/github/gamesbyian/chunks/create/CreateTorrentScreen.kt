package io.github.gamesbyian.chunks.create

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.ActionTile
import io.github.gamesbyian.chunks.ChunkedProgressBar
import io.github.gamesbyian.chunks.ChunksBanner
import io.github.gamesbyian.chunks.ChunksColors
import io.github.gamesbyian.chunks.ChunksTone
import io.github.gamesbyian.chunks.R
import io.github.gamesbyian.chunks.model.Units
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Create & Share, first journey: pick a phone file or folder, review what
 * recipients will see, build the .torrent on the phone, then save it, share
 * the magnet or copy it. Nothing is sent anywhere and nothing seeds yet; the
 * result says so.
 */
@Composable
internal fun CreateTorrentScreen(
    onBack: () -> Unit,
    /** Tests start from an already-listed source; the app always starts empty. */
    initialSource: CreateTorrentSource? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(initialSource) }
    var scanning by remember { mutableStateOf(false) }
    var trackers by remember { mutableStateOf("") }
    var private by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Long?>(null) }
    var result by remember { mutableStateOf<TorrentMetainfoBuilder.Result?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val cancel = remember { AtomicBoolean(false) }

    fun scan(block: () -> CreateTorrentSource) {
        source = null; result = null; message = null; scanning = true; cancel.set(false)
        scope.launch {
            try { source = withContext(Dispatchers.IO) { block() } }
            catch (_: TorrentMetainfoBuilder.Cancelled) { message = "Cancelled." }
            catch (e: Exception) { message = e.message ?: "These files could not be read." }
            finally { scanning = false }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scan { CreateTorrentSource.fromDocument(context, uri) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scan { CreateTorrentSource.fromTree(context, uri) { cancel.get() } }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-bittorrent")) { uri ->
        val bytes = result?.torrent ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            message = withContext(Dispatchers.IO) { save(context, uri, bytes) }
        }
    }
    val busy = scanning || progress != null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Make it shareable.", style = MaterialTheme.typography.headlineSmall)
        Text("Choose a file or folder on this phone. Chunks builds a .torrent and a magnet link here, " +
            "on the phone. Nothing is uploaded or published.", style = MaterialTheme.typography.bodyMedium)
        val stack = LocalDensity.current.fontScale >= 1.5f
        if (stack) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile("Choose a file", ChunksColors.Cyan, { filePicker.launch(arrayOf("*/*")) },
                Modifier.fillMaxWidth(), enabled = !busy, icon = R.drawable.chunks_ic_document, stacked = false)
            ActionTile("Choose a folder", ChunksColors.Yellow, { folderPicker.launch(null) },
                Modifier.fillMaxWidth(), enabled = !busy, icon = R.drawable.chunks_ic_folder, stacked = false)
        } else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile("Choose a file", ChunksColors.Cyan, { filePicker.launch(arrayOf("*/*")) },
                Modifier.weight(1f).fillMaxHeight(), enabled = !busy, icon = R.drawable.chunks_ic_document)
            ActionTile("Choose a folder", ChunksColors.Yellow, { folderPicker.launch(null) },
                Modifier.weight(1f).fillMaxHeight(), enabled = !busy, icon = R.drawable.chunks_ic_folder)
        }
        if (scanning) Text("Reading the file list…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })

        source?.let { picked ->
            CreateReview(picked)
            OutlinedTextField(value = trackers, onValueChange = { trackers = it; result = null },
                label = { Text("Trackers (optional, one per line)") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth(), minLines = 2)
            Text("Without trackers, other BitTorrent apps can still find peers through DHT, but some " +
                "apps and seedboxes need at least one tracker.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = private, enabled = !busy, role = Role.Checkbox, onValueChange = { private = it; result = null }),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = private, enabled = !busy, onCheckedChange = null)
                Text("Private torrent, for a private tracker (no DHT or peer exchange; no magnet link)",
                    modifier = Modifier.padding(start = 8.dp))
            }
            val hashed = progress
            if (hashed != null) {
                Text("Building: ${Units.bytes(hashed)} of ${Units.bytes(picked.totalBytes)} checked",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                ChunkedProgressBar(if (picked.totalBytes > 0) hashed.toFloat() / picked.totalBytes else 0f,
                    Modifier.fillMaxWidth())
                OutlinedButton(onClick = { cancel.set(true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Cancel")
                }
            } else if (result == null) ActionTile("Create torrent", ChunksColors.Green, {
                val tiers = trackers.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { listOf(it) }
                cancel.set(false); message = null; progress = 0L
                scope.launch {
                    try {
                        result = withContext(Dispatchers.IO) {
                            TorrentMetainfoBuilder.build(
                                TorrentMetainfoBuilder.Request(picked.name, picked.entries, tiers, private),
                                onProgress = { done -> scope.launch { progress = done } },
                                isCancelled = { cancel.get() })
                        }
                    } catch (_: TorrentMetainfoBuilder.Cancelled) { message = "Cancelled. Nothing was created."
                    } catch (e: Exception) { message = e.message ?: "The torrent could not be created."
                    } finally { progress = null }
                }
            }, Modifier.fillMaxWidth(), icon = R.drawable.chunks_ic_add_torrent, stacked = false)
        }

        result?.let { done ->
            Text("Torrent created", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${done.pieceCount} pieces of ${Units.bytes(done.pieceLength.toLong())} · info hash ${done.infoHashV1}",
                style = MaterialTheme.typography.bodySmall)
            ChunksBanner("Not seeding yet",
                "Nobody can download these files until a BitTorrent app that has them is running and reachable. " +
                    "Chunks does not seed created torrents yet.", ChunksTone.PAUSED)
            ActionTile("Save .torrent file", ChunksColors.Blue, { saver.launch(fileName(source?.name)) },
                Modifier.fillMaxWidth(), icon = R.drawable.chunks_ic_document, stacked = false)
            done.magnet?.let { magnet ->
                ActionTile("Share magnet link", ChunksColors.Purple, {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, magnet), "Share magnet link"))
                }, Modifier.fillMaxWidth(), icon = R.drawable.chunks_ic_magnet, stacked = false)
                OutlinedButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Magnet link", magnet))
                    message = "Magnet link copied."
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Copy magnet link") }
            }
        }
        message?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Back") }
    }
}

@Composable
private fun CreateReview(source: CreateTorrentSource) {
    Text(source.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text("${source.entries.size} file${if (source.entries.size == 1) "" else "s"} · ${Units.bytes(source.totalBytes)}")
    source.entries.take(5).forEach {
        Text(it.path.joinToString("/") + " · " + Units.bytes(it.sizeBytes), style = MaterialTheme.typography.bodySmall,
            color = ChunksColors.TextSecondary)
    }
    if (source.entries.size > 5) Text("and ${source.entries.size - 5} more", style = MaterialTheme.typography.bodySmall,
        color = ChunksColors.TextSecondary)
    ChunksBanner("What recipients will see",
        "Anyone with this torrent sees the name, every file and folder name, and their sizes. Once shared, " +
            "a torrent cannot be taken back. Seeding later will show your IP address to peers.", ChunksTone.PAUSED)
}

internal fun fileName(name: String?): String =
    (name ?: "shared").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(120) + ".torrent"

private fun save(context: Context, uri: Uri, bytes: ByteArray): String = try {
    val stream = context.contentResolver.openOutputStream(uri, "wt")
    if (stream == null) "The .torrent could not be saved there."
    else { stream.use { it.write(bytes) }; "Saved." }
} catch (_: Exception) { "The .torrent could not be saved there." }
