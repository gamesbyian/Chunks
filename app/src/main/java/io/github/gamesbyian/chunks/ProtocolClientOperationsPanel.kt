package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.ClientCapability
import io.github.gamesbyian.chunks.rpc.ClientFile
import io.github.gamesbyian.chunks.rpc.ClientFilePriority
import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome
import io.github.gamesbyian.chunks.rpc.ClientSessionInfo
import io.github.gamesbyian.chunks.rpc.ClientOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Another connected client a job may be copied or moved to. */
internal data class ProtocolMoveTarget(
    val id: String,
    val label: String,
    val createOperations: () -> ClientOperations
)

/** Plain-language, credential-free outcome text shared by every protocol. */
internal object ProtocolOperationText {
    fun describe(action: String, outcome: ClientOperationOutcome, clientLabel: String): String = when (outcome) {
        is ClientOperationOutcome.Verified -> "$action: confirmed on $clientLabel."
        is ClientOperationOutcome.Pending -> "$action: requested, not yet confirmed (" +
            outcome.reason.lowercase() + "). Refresh to check."
        is ClientOperationOutcome.Uncertain -> "$action: the reply was lost, so the result is unknown. " +
            "Refresh before trying again; nothing was retried."
        is ClientOperationOutcome.Refused -> "$action: not changed. " + outcome.reason + "."
    }
}

/**
 * Details operations for one exact Transmission / qBittorrent / Deluge
 * transfer. Every command goes through the shared [ClientOperations]
 * workflow (exact precondition, one write, exact readback). Offered actions
 * come from the client's own session capabilities. An uncertain result
 * locks further commands until a fresh list is read.
 */
@Composable
internal fun ProtocolClientOperationsPanel(
    hash: String,
    clientLabel: String,
    /** Stable authenticated client identity, never a display label. */
    sourceClientId: String,
    observedStarted: Boolean?,
    /** The list row is current, complete and privacy-visible. */
    current: Boolean,
    /** Changes whenever a fresh inventory is accepted. */
    inventoryWitness: Any?,
    createOperations: () -> ClientOperations,
    onChanged: () -> Unit,
    /** Other connected clients this job may be copied/moved to (job only). */
    moveTargets: List<ProtocolMoveTarget> = emptyList(),
    /** The rTorrent Details surface already owns its verified lifecycle/file controls. */
    moveOnly: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val operations = remember(hash, createOperations) { createOperations() }
    var session by remember(operations) { mutableStateOf<ClientSessionInfo?>(null) }
    var sessionError by remember(operations) { mutableStateOf<String?>(null) }
    var busy by remember(hash) { mutableStateOf(false) }
    var notice by remember(hash) { mutableStateOf<String?>(null) }
    // Uncertain latch: cleared only by a newer accepted inventory.
    var uncertainAt by remember(hash) { mutableStateOf<Any?>(null) }
    val latched = uncertainAt != null && uncertainAt == inventoryWitness
    var confirmRemove by remember(hash) { mutableStateOf(false) }
    var deleteData by remember(hash) { mutableStateOf(false) }
    var files by remember(hash) { mutableStateOf<List<ClientFile>?>(null) }
    var showMove by remember(hash) { mutableStateOf(false) }
    var copiedTo by remember(hash, sourceClientId) { mutableStateOf<ProtocolMoveTarget?>(null) }
    var copiedTransfer by remember(hash, sourceClientId) { mutableStateOf<io.github.gamesbyian.chunks.rpc.ClientJobTransfer?>(null) }
    var copiedReceipt by remember(hash, sourceClientId) {
        mutableStateOf<io.github.gamesbyian.chunks.rpc.ClientJobTransfer.CopyResult.Copied?>(null)
    }
    var draft by remember(hash) { mutableStateOf<Map<Int, ClientFilePriority>>(emptyMap()) }
    // Change folder: the client's own set-location, verified by exact readback.
    var relocating by remember(hash) { mutableStateOf(false) }
    var relocateTo by remember(hash) { mutableStateOf("") }
    var moveFiles by remember(hash) { mutableStateOf(false) }
    var currentFolder by remember(hash) { mutableStateOf<String?>(null) }

    LaunchedEffect(operations) {
        try {
            session = operations.session()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            sessionError = "Controls unavailable: " + RemoteProtocolConnect.explain(failure)
        }
    }

    fun run(action: String, block: suspend () -> ClientOperationOutcome) {
        if (busy || latched || !current) return
        busy = true
        notice = "$action…"
        scope.launch {
            val outcome = try { block() } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { ClientOperationOutcome.Uncertain(hash, "unexpected failure") }
            notice = ProtocolOperationText.describe(action, outcome, clientLabel)
            if (outcome is ClientOperationOutcome.Uncertain) uncertainAt = inventoryWitness
            busy = false
            onChanged()
        }
    }

    val caps = session?.capabilities.orEmpty()
    val enabled = current && !busy && !latched && session != null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag(ChunksSemantics.PROTOCOL_OPERATIONS)) {
        Text(if (moveOnly) "Copy or move from $clientLabel" else "Controls on $clientLabel",
            style = MaterialTheme.typography.titleSmall)
        sessionError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ChunksColors.Yellow) }
        if (!current) Text("The transfer list is out of date or locked. Refresh before changing this transfer.",
            style = MaterialTheme.typography.bodySmall)
        if (!moveOnly) ResponsiveActionGroup(maxColumns = 2) { cell ->
            if (ClientCapability.START_STOP in caps) {
                val start = observedStarted != true
                OutlinedButton(
                    onClick = { run(if (start) "Start" else "Stop") { operations.setStarted(hash, start) } },
                    enabled = enabled && observedStarted != null,
                    modifier = cell.heightIn(min = 48.dp).testTag(ChunksSemantics.PROTOCOL_START_STOP)
                ) { Text(if (start) "Start" else "Stop") }
            }
            if (ClientCapability.RECHECK in caps) OutlinedButton(
                onClick = { run("Recheck") { operations.recheck(hash) } },
                enabled = enabled, modifier = cell.heightIn(min = 48.dp)
            ) { Text("Recheck") }
            if (ClientCapability.FILE_PRIORITIES in caps) OutlinedButton(
                onClick = {
                    if (files != null) { files = null; draft = emptyMap() } else if (!busy) {
                        busy = true
                        scope.launch {
                            files = try {
                                // Files are an explicit, bounded read of the exact hash.
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    filesOf(operations, hash)
                                }
                            } catch (cancel: CancellationException) { throw cancel } catch (failure: Exception) {
                                notice = "Files: " + RemoteProtocolConnect.explain(failure); null
                            }
                            draft = files.orEmpty().associate { it.index to it.priority }
                            busy = false
                        }
                    }
                },
                enabled = current && !busy, modifier = cell.heightIn(min = 48.dp)
            ) { Text(if (files == null) "Files" else "Hide files") }
            if (ClientCapability.SET_LOCATION in caps) OutlinedButton(
                onClick = {
                    relocating = !relocating
                    moveFiles = false
                    if (relocating) scope.launch {
                        // The exact job's own reported folder, read just now.
                        currentFolder = try { operations.client.torrent(hash)?.downloadDirectory }
                            catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                    }
                },
                enabled = enabled, modifier = cell.heightIn(min = 48.dp)
                    .testTag(ChunksSemantics.PROTOCOL_SET_LOCATION)
            ) { Text(if (relocating) "Cancel" else "Change folder…") }
            if (ClientCapability.REMOVE in caps) OutlinedButton(
                onClick = { confirmRemove = !confirmRemove; deleteData = false },
                enabled = enabled, modifier = cell.heightIn(min = 48.dp)
                    .testTag(ChunksSemantics.PROTOCOL_REMOVE)
            ) { Text(if (confirmRemove) "Cancel remove" else "Remove…") }
        }
        if (ClientCapability.EXPORT_JOB in caps && moveTargets.isNotEmpty()) {
            OutlinedButton(onClick = { showMove = !showMove }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag(ChunksSemantics.PROTOCOL_MOVE)) {
                Text(if (showMove) "Hide copy or move" else "Copy or move job to another client…")
            }
        }
        if (showMove) {
            Text("Copies only the torrent job. The other client downloads its files itself; " +
                "nothing on $clientLabel is changed unless you then remove it here.",
                style = MaterialTheme.typography.bodySmall)
            moveTargets.forEach { target ->
                OutlinedButton(onClick = {
                    if (busy || latched) return@OutlinedButton
                    busy = true
                    copiedTo = null; copiedTransfer = null; copiedReceipt = null
                    notice = "Copying job to ${target.label}…"
                    scope.launch {
                        val transfer = try {
                            val dst = target.createOperations()
                            io.github.gamesbyian.chunks.rpc.ClientJobTransfer(
                                operations, operations.client, dst, dst.client,
                                sourceClientId = sourceClientId, targetClientId = target.id
                            )
                        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                        val result = try {
                            transfer?.copy(hash, targetDirectory = null, startOnTarget = observedStarted == true)
                        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                        when (result) {
                            is io.github.gamesbyian.chunks.rpc.ClientJobTransfer.CopyResult.Copied -> {
                                copiedTo = target; copiedTransfer = transfer; copiedReceipt = result
                                notice = "Job copied to ${target.label} (confirmed). Its files download there separately."
                            }
                            is io.github.gamesbyian.chunks.rpc.ClientJobTransfer.CopyResult.NotCopied -> {
                                notice = ProtocolOperationText.describe("Copy to ${target.label}", result.outcome, target.label)
                                if (result.outcome is ClientOperationOutcome.Uncertain) uncertainAt = inventoryWitness
                            }
                            is io.github.gamesbyian.chunks.rpc.ClientJobTransfer.CopyResult.Refused ->
                                notice = "Copy to ${target.label}: not changed. ${result.reason}."
                            null -> notice = "Copy to ${target.label}: could not reach both clients. Nothing was changed."
                        }
                        busy = false
                        onChanged()
                    }
                }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag(ChunksSemantics.PROTOCOL_MOVE + "." + target.id)) {
                    Text("Copy job to ${target.label}")
                }
            }
            val done = copiedTo
            val transfer = copiedTransfer
            val receipt = copiedReceipt
            if (done != null && transfer != null && receipt != null) Button(onClick = {
                run("Remove from $clientLabel after moving to ${done.label}") {
                    transfer.retireSource(hash, receipt)
                }
                copiedTo = null; copiedTransfer = null; copiedReceipt = null
            }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag(ChunksSemantics.PROTOCOL_MOVE_RETIRE)) {
                Text("Finish move: remove from $clientLabel (keep files)")
            }
        }
        val shown = files
        if (!moveOnly && shown != null) {
            val levels = session?.filePriorities.orEmpty()
            shown.sortedBy { it.index }.forEach { file ->
                val chosen = draft[file.index] ?: file.priority
                OutlinedButton(
                    onClick = {
                        val order = ClientFilePriority.entries.filter { it == ClientFilePriority.SKIP || it in levels }
                        draft = draft + (file.index to order[(order.indexOf(chosen) + 1) % order.size])
                    },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        contentDescription = file.path.substringAfterLast('/') + ", priority " +
                            chosen.name.lowercase() + ". Tap to change"
                    }
                ) { Text(file.path.substringAfterLast('/') + " · " + priorityLabel(chosen)) }
            }
            val changed = draft.filter { (index, value) -> shown.firstOrNull { it.index == index }?.priority != value }
            Button(
                onClick = { run("File priorities") { operations.setFilePriorities(hash, changed) }; files = null },
                enabled = enabled && changed.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text("Apply ${changed.size} priority change" + if (changed.size == 1) "" else "s") }
        }
        if (!moveOnly && relocating) {
            val target = relocateTo.trim()
            val valid = runCatching { io.github.gamesbyian.chunks.rpc.ClientHashes.requireDirectory(target) }.isSuccess &&
                !io.github.gamesbyian.chunks.rpc.ClientHashes.sameDirectory(target, currentFolder)
            Text("Current folder on $clientLabel: " + (currentFolder ?: "not reported"),
                style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.OutlinedTextField(
                value = relocateTo, onValueChange = { relocateTo = it }, singleLine = true,
                label = { Text("New absolute folder on $clientLabel") }, enabled = !busy,
                isError = target.isNotEmpty() && !valid,
                modifier = Modifier.fillMaxWidth().testTag(ChunksSemantics.PROTOCOL_SET_LOCATION_FOLDER))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                value = moveFiles, enabled = !busy, role = Role.Checkbox, onValueChange = { moveFiles = it })
                .testTag(ChunksSemantics.PROTOCOL_SET_LOCATION_MOVE_FILES),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = moveFiles, onCheckedChange = null)
                Text("Move the downloaded files there too", modifier = Modifier.padding(start = 8.dp))
            }
            Text(if (moveFiles) "$clientLabel itself moves this transfer's files into the new folder. " +
                    "Nothing is deleted by Chunks; check the result before removing anything."
                else "$clientLabel only looks for this transfer's files in the new folder; no files are moved. " +
                    "Use Recheck afterwards if the files are already there.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val dir = target
                relocating = false
                run(if (moveFiles) "Move files to the new folder" else "Change folder") {
                    operations.setLocation(hash, dir, moveFiles)
                }
            }, enabled = enabled && valid, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag(ChunksSemantics.PROTOCOL_SET_LOCATION_CONFIRM)) {
                Text(if (moveFiles) "Move files to this folder" else "Use this folder")
            }
        }
        if (!moveOnly && confirmRemove) {
            Text("Remove this transfer from $clientLabel? By default its downloaded files stay on the server.",
                style = MaterialTheme.typography.bodySmall)
            if (ClientCapability.REMOVE_WITH_DATA in caps) Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = deleteData, enabled = enabled, role = Role.Checkbox,
                    onValueChange = { deleteData = it }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = deleteData, onCheckedChange = null)
                Text("Also delete its downloaded files on the server", modifier = Modifier.padding(start = 8.dp))
            }
            Button(
                onClick = {
                    val withData = deleteData
                    confirmRemove = false
                    run(if (withData) "Remove and delete files" else "Remove") { operations.remove(hash, withData) }
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag(ChunksSemantics.PROTOCOL_REMOVE_CONFIRM)
            ) { Text(if (deleteData) "Remove and delete files" else "Remove, keep files") }
        }
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(ChunksSemantics.PROTOCOL_OPERATION_STATUS))
        }
        if (latched) Text("Commands are paused until the list refreshes.",
            style = MaterialTheme.typography.bodySmall, color = ChunksColors.Yellow)
    }
}

private fun priorityLabel(p: ClientFilePriority) = when (p) {
    ClientFilePriority.SKIP -> "Skip"
    ClientFilePriority.LOW -> "Low"
    ClientFilePriority.NORMAL -> "Normal"
    ClientFilePriority.HIGH -> "High"
}

private suspend fun filesOf(operations: ClientOperations, hash: String): List<ClientFile> =
    operations.files(hash)
