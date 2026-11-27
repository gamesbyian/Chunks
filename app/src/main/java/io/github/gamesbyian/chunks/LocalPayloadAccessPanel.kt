package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Explicit disclosure followed by selection of exactly one reviewed file and action. */
@Composable
internal fun LocalPayloadAccessPanel(infoHash: String, engine: () -> LocalDetailsPort?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember(infoHash) { mutableStateOf(false) }
    var review by remember(infoHash) { mutableStateOf<LocalFileInspection.Review?>(null) }
    var selected by remember(infoHash) { mutableStateOf<LocalTransferFile?>(null) }
    var page by remember(infoHash) { mutableStateOf(0) }
    var notice by remember(infoHash) { mutableStateOf<String?>(null) }
    var task by remember(infoHash) { mutableStateOf<Job?>(null) }
    val busy = task != null

    fun runTask(action: suspend () -> Unit) {
        if (task != null) return
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { notice = "File access could not be prepared." }
            finally { task = null }
        }
        task = next
        next.start()
    }

    fun load() {
        expanded = true
        review = null
        selected = null
        page = 0
        notice = null
        runTask {
            val result = withContext(Dispatchers.IO) {
                if (!LocalPayloadAccess.available(context)) null
                else engine()?.checksumReview(infoHash)
            }
            review = result
            if (result == null) notice = "No accessible completed files were confirmed. Local privacy protection must be off for this version."
        }
    }

    fun launchHandoff(file: LocalTransferFile, shown: LocalFileInspection.Review, share: Boolean) {
        notice = null
        runTask {
            val prepared = withContext(Dispatchers.IO) {
                val worker = currentCoroutineContext()[Job]
                engine()?.prepareFileAccess(context, shown, file) { worker?.isActive != true }
            }
            val uri = prepared?.let { LocalPayloadAccess.issue(context, it) }
            if (prepared == null || uri == null) {
                notice = "File access refused: the reviewed source, privacy policy or available reader slots could not be confirmed."
            } else {
                try {
                    context.startActivity(LocalPayloadAccess.chooser(uri, prepared.mime, share))
                    notice = "Android chooser opened. The receiving app determines whether it can read this file."
                } catch (cancel: CancellationException) {
                    LocalPayloadAccess.revoke(context, uri)
                    throw cancel
                } catch (_: Exception) {
                    LocalPayloadAccess.revoke(context, uri)
                    notice = "No compatible chooser could be opened. File access was revoked."
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            if (expanded) { expanded = false; review = null; selected = null; notice = null }
            else load()
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (expanded) "Hide open and share" else "Open or share a completed file…")
        }
        if (!expanded) return@Column
        Text("One completed file on this phone. The chosen app receives temporary read-only access for up to 10 minutes. It may retain bytes it has already read. No copy is made by Chunks, and protected Local transfers are unavailable here.",
            style = MaterialTheme.typography.bodySmall)
        val shown = review
        if (shown != null) {
            val pageSize = 8
            val lastPage = (shown.files.size - 1) / pageSize
            Text("Files " + (page * pageSize + 1) + "–" + minOf((page + 1) * pageSize, shown.files.size) + " of " + shown.files.size)
            shown.files.drop(page * pageSize).take(pageSize).forEach { file ->
                OutlinedButton(onClick = { selected = file; notice = null }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Select " + file.path)
                }
            }
            if (lastPage > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { page--; selected = null }, enabled = !busy && page > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Previous files") }
                OutlinedButton(onClick = { page++; selected = null }, enabled = !busy && page < lastPage,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Next files") }
            }
            selected?.let { file ->
                Text("Selected: " + file.path + " (" + file.sizeBytes + " bytes)")
                OutlinedButton(onClick = { launchHandoff(file, shown, false) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open with…") }
                OutlinedButton(onClick = { launchHandoff(file, shown, true) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Share file…") }
            }
            OutlinedButton(onClick = ::load, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Refresh accessible files") }
        }
        if (busy) {
            Text("Checking exact file access…")
            OutlinedButton(onClick = { task?.cancel(); notice = "Access preparation cancelled." },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel file access") }
        }
        OutlinedButton(onClick = {
            LocalPayloadAccess.revokeAll(context)
            notice = "Temporary Local file grants revoked. Previously read bytes cannot be recalled."
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Revoke temporary file access")
        }
        notice?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}
