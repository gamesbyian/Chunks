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
import io.github.gamesbyian.chunks.model.BatchLocalCopy
import io.github.gamesbyian.chunks.model.PublicTrackerHandoff
import io.github.gamesbyian.chunks.model.TorrentIdentityHandoff
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Batch Copy Transfer to the local device for the selected server transfers (job-only). */
@Composable
internal fun RemoteBatchLocalCopyPanel(
    selected: List<RemoteTransfer>,
    enabled: Boolean,
    run: suspend (List<BatchLocalCopy.Item>) -> String,
    /** Batch Move step 1 (local Adds + journal); null hides it. */
    move: (suspend (List<BatchLocalCopy.Item>) -> String)? = null
) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var confirmingMove by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Batch Copy Transfer to the local device", fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { confirming = !confirming; message = null },
            enabled = enabled && !running && selected.isNotEmpty() && selected.size <= BatchLocalCopy.MAX_ITEMS,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Copy Transfer to the local device (${selected.size})…")
        }
        if (confirming) {
            Text("Adds each selected torrent to this device as a new job at the local device's default download " +
                "location, one at a time, in metadata-only mode: nothing downloads until you start it. " +
                "Downloaded server files are not transferred and the server transfers stay as they are. " +
                "Public tracker addresses are included; private torrents get their identity only (their " +
                "passkeys are never read), so they may find no peers.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                confirming = false
                running = true
                val items = selected.map { BatchLocalCopy.Item(it.hash, it.name) }
                scope.launch {
                    message = try { run(items) } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        "The batch copy stopped unexpectedly. Check the local device list; nothing is retried."
                    } finally { running = false }
                }
            }, enabled = enabled && !running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Confirm copy to the local device")
            }
        }
        if (move != null) {
            OutlinedButton(onClick = { confirmingMove = !confirmingMove; message = null },
                enabled = enabled && !running && selected.isNotEmpty() && selected.size <= BatchLocalCopy.MAX_ITEMS,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Move Transfer to the local device (${selected.size})…")
            }
            if (confirmingMove) {
                Text("Moves the torrent jobs, not their files, in two steps. First each selected torrent is " +
                    "added to this device (metadata only) and confirmed there. Then you review and separately " +
                    "confirm removing the original server jobs. Downloaded files stay on the server and keep " +
                    "using its storage; the local device may download everything again.",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    confirmingMove = false
                    running = true
                    val items = selected.map { BatchLocalCopy.Item(it.hash, it.name) }
                    scope.launch {
                        message = try { move(items) } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            "The batch move stopped before removal. Check the local device list; nothing was removed."
                        } finally { running = false }
                    }
                }, enabled = enabled && !running, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Add to the local device, then review removal")
                }
            }
        }
        if (running) Text("Adding to the local device one at a time…")
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Batch Move step 1: local Adds, then journal each confirmed local job at TARGET_VERIFIED. */
internal suspend fun runBatchLocalMove(
    context: Context, remote: RTorrentRemote, aliasScope: String, items: List<BatchLocalCopy.Item>,
    /** Source jobs observed when the owner reviewed the batch. No guessing a new save path. */
    originalTransfers: List<RemoteTransfer> = emptyList()
): String {
    var journaled = 0
    var aliasCopied = 0
    var aliasFailed = 0
    val text = runBatchLocalCopy(context, remote, items) { report ->
        val store = TransferMoveStore(context)
        val aliases = TransferAliasStore(context)
        for ((item, result) in report.results) {
            if (result != BatchLocalCopy.Result.ADDED &&
                result != BatchLocalCopy.Result.ALREADY_ON_LOCAL) continue
            val original = originalTransfers.singleOrNull {
                it.hash.equals(item.infoHash, ignoreCase = true) &&
                    it.name == item.name && it.directory.startsWith("/")
            } ?: continue
            // An "already" refusal reason or an Add result is not an
            // independent inventory observation. Never journal a Move as
            // target-verified, or carry presentation, until the local device proves
            // this exact job is currently registered.
            if (!BatchLocalCopy.observedDestinationForMove(
                    result, TransferMoveObservations.local(item.infoHash))) continue
            val saved = store.save(io.github.gamesbyian.chunks.model.TransferMove.start(
                io.github.gamesbyian.chunks.model.TransferMove.Direction.SERVER_TO_LOCAL,
                item.infoHash, item.name,
                TransferMoveStore.scopeDigest(aliasScope), TransferMoveStore.hostLabel(aliasScope),
                sourceDirectory = original.directory
            ).copy(stage = io.github.gamesbyian.chunks.model.TransferMove.Stage.TARGET_VERIFIED))
            if (!saved) continue
            journaled++
            when (aliases.carryVerifiedMoveAlias(
                aliasScope, TransferCollectionStore.LOCAL_ENGINE_SCOPE, item.infoHash)) {
                TransferAliasStore.MoveAliasCarry.COPIED -> aliasCopied++
                TransferAliasStore.MoveAliasCarry.FAILED -> aliasFailed++
                else -> Unit
            }
        }
    }
    return "$text $journaled ready to finish moving: review removal of the original server jobs below." +
        (if (aliasCopied > 0) " $aliasCopied display aliases carried to this device." else "") +
        (if (aliasFailed > 0) " $aliasFailed display aliases could not be carried; edit them on the local device." else "")
}

/** The production runner: public-tracker magnets into the local device's verified default Place. */
internal suspend fun runBatchLocalCopy(
    context: Context, remote: RTorrentRemote, items: List<BatchLocalCopy.Item>,
    afterReport: suspend (BatchLocalCopy.Report) -> Unit = {}
): String {
    BatchLocalCopy.validate(items)?.let { return it }
    val folder = withContext(Dispatchers.IO) {
        LocalDownloadPlaces.resolve(context).selected?.let(LocalDownloadPlaces::materialize)
            ?.let { LocalOwnedAddFolder.prepare(it, "") }
    } ?: return "The local has no verified download location yet. Open local transfers to set one up; nothing was added."
    val report = BatchLocalCopy.run(items,
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
    return report.summary() + report.results.filter { it.second != BatchLocalCopy.Result.ADDED }
        .take(10).joinToString("") { "\n• " + it.first.name.take(80) + ": " + it.second.label }
}
