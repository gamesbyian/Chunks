package io.github.gamesbyian.chunks

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.model.BatchPhoneCopy
import io.github.gamesbyian.chunks.model.PublicTrackerHandoff
import io.github.gamesbyian.chunks.model.TorrentIdentityHandoff
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Batch Copy Transfer to phone for the selected server transfers (job-only). */
@Composable
internal fun RemoteBatchPhoneCopyPanel(
    selected: List<RemoteTransfer>,
    enabled: Boolean,
    run: suspend (List<BatchPhoneCopy.Item>) -> String,
    /** Batch Move step 1 (phone Adds + journal); null hides it. */
    move: (suspend (List<BatchPhoneCopy.Item>) -> String)? = null
) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var confirmingMove by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Batch Copy Transfer to phone", fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { confirming = !confirming; message = null },
            enabled = enabled && !running && selected.isNotEmpty() && selected.size <= BatchPhoneCopy.MAX_ITEMS,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Copy Transfer to phone (${selected.size})…")
        }
        if (confirming) {
            Text("Adds each selected torrent to this phone as a new job at the phone's default download " +
                "location, one at a time, in metadata-only mode: nothing downloads until you start it. " +
                "Downloaded server files are not transferred and the server transfers stay as they are. " +
                "Public tracker addresses are included; private torrents get their identity only (their " +
                "passkeys are never read), so they may find no peers.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                confirming = false
                running = true
                val items = selected.map { BatchPhoneCopy.Item(it.hash, it.name) }
                scope.launch {
                    message = try { run(items) } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        "The batch copy stopped unexpectedly. Check the phone list; nothing is retried."
                    } finally { running = false }
                }
            }, enabled = enabled && !running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Confirm copy to phone")
            }
        }
        if (move != null) {
            OutlinedButton(onClick = { confirmingMove = !confirmingMove; message = null },
                enabled = enabled && !running && selected.isNotEmpty() && selected.size <= BatchPhoneCopy.MAX_ITEMS,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Move Transfer to phone (${selected.size})…")
            }
            if (confirmingMove) {
                Text("Moves the torrent jobs, not their files, in two steps. First each selected torrent is " +
                    "added to this phone (metadata only) and confirmed there. Then you review and separately " +
                    "confirm removing the original server jobs. Downloaded files stay on the server and keep " +
                    "using its storage; the phone may download everything again.",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    confirmingMove = false
                    running = true
                    val items = selected.map { BatchPhoneCopy.Item(it.hash, it.name) }
                    scope.launch {
                        message = try { move(items) } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            "The batch move stopped before removal. Check the phone list; nothing was removed."
                        } finally { running = false }
                    }
                }, enabled = enabled && !running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Add to phone, then review removal")
                }
            }
        }
        if (running) Text("Adding to the phone one at a time…")
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Batch Move step 1: phone Adds, then journal each confirmed phone job at TARGET_VERIFIED. */
internal suspend fun runBatchPhoneMove(
    context: Context, remote: RTorrentRemote, aliasScope: String, items: List<BatchPhoneCopy.Item>
): String {
    var journaled = 0
    val text = runBatchPhoneCopy(context, remote, items) { report ->
        val store = TransferMoveStore(context)
        journaled = report.results.filter {
            it.second == BatchPhoneCopy.Result.ADDED || it.second == BatchPhoneCopy.Result.ALREADY_ON_PHONE
        }.count { (item, _) ->
            store.save(io.github.gamesbyian.chunks.model.TransferMove.start(
                io.github.gamesbyian.chunks.model.TransferMove.Direction.SERVER_TO_PHONE, item.infoHash, item.name,
                TransferMoveStore.scopeDigest(aliasScope), TransferMoveStore.hostLabel(aliasScope))
                .copy(stage = io.github.gamesbyian.chunks.model.TransferMove.Stage.TARGET_VERIFIED))
        }
    }
    return "$text $journaled ready to finish moving: review removal of the original server jobs below."
}

/** The production runner: public-tracker magnets into the phone's verified default Place. */
internal suspend fun runBatchPhoneCopy(
    context: Context, remote: RTorrentRemote, items: List<BatchPhoneCopy.Item>,
    afterReport: suspend (BatchPhoneCopy.Report) -> Unit = {}
): String {
    BatchPhoneCopy.validate(items)?.let { return it }
    val folder = withContext(Dispatchers.IO) {
        LocalDownloadPlaces.resolve(context).selected?.let(LocalDownloadPlaces::materialize)
            ?.let { LocalOwnedAddFolder.prepare(it, "") }
    } ?: return "The phone has no verified download location yet. Open phone transfers to set one up; nothing was added."
    val report = BatchPhoneCopy.run(items,
        magnetFor = { item ->
            TorrentIdentityHandoff.prepare(item.infoHash, item.name)?.let { identity ->
                val trackers = try {
                    (remote.readPublicTrackers(item.infoHash) as? RTorrentRemote.PublicTrackers.Urls)?.urls.orEmpty()
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { emptyList() }
                PublicTrackerHandoff.withTrackers(identity, trackers)
            }
        },
        addMetadataOnly = { magnet ->
            withContext(Dispatchers.IO) {
                LocalEngineHolder.get(context).addMagnet(magnet, folder, autoStartAfterMetadata = false)
            }
        })
    withContext(Dispatchers.IO) { afterReport(report) }
    return report.summary() + report.results.filter { it.second != BatchPhoneCopy.Result.ADDED }
        .take(10).joinToString("") { "\n• " + it.first.name.take(80) + ": " + it.second.label }
}
