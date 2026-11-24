package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner
import io.github.gamesbyian.chunks.engine.FinishInOrderRecord
import io.github.gamesbyian.chunks.engine.FinishInOrderRunner
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteFile
import io.github.gamesbyian.chunks.rpc.RemoteFinishInOrderPort
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One runner step for a server transfer, serialized with every other policy edit. */
internal suspend fun stepRemoteFinishInOrder(
    remote: RTorrentRemote, store: FinishInOrderStore, hash: String, ownerCurrent: () -> Boolean = { true }
): FinishInOrderRunner.Step? = FinishInOrderController.exclusive {
    if (!ownerCurrent()) return@exclusive null
    store.get(hash)?.let { record ->
        FinishInOrderRunner(RemoteFinishInOrderPort(remote) { h, r -> ownerCurrent() && store.update(h, r) })
            .step(hash, record)
    }
}

/** One runner step for a shared-port client transfer, serialized with every other policy edit. */
internal suspend fun stepClientFinishInOrder(
    operations: io.github.gamesbyian.chunks.rpc.ClientOperations,
    storedLevels: Set<io.github.gamesbyian.chunks.rpc.ClientFilePriority>,
    store: FinishInOrderStore, hash: String, ownerCurrent: () -> Boolean = { true }
): FinishInOrderRunner.Step? = FinishInOrderController.exclusive {
    if (!ownerCurrent()) return@exclusive null
    store.get(hash)?.let { record ->
        FinishInOrderRunner(io.github.gamesbyian.chunks.rpc.ClientFinishInOrderPort(operations, storedLevels) { h, r ->
            ownerCurrent() && store.update(h, r)
        }).step(hash, record)
    }
}

/** Finish in Order storage scopes: one per exact authenticated client identity. */
internal object FinishInOrderScopes {
    fun protocol(clientId: String): String {
        require(clientId.isNotBlank())
        return "protocol:$clientId"
    }
}

/** Server files in the planner's terms: index = rTorrent file index, priority mapped to bands. */
internal fun remoteFilesAsLocal(files: List<RemoteFile>): List<LocalTransferFile> =
    files.mapIndexed { index, file ->
        LocalTransferFile(index, file.path, file.sizeBytes, 0L, file.priority != 0,
            RemoteFinishInOrderPort.toPlanner(file.priority))
    }

/**
 * Server Add review option: after a verified stopped Add (and before any
 * Start), create a Balanced policy from the detected order and apply its
 * first verified step. Best effort with a plain result; it never retries the
 * Add or Start, and nothing is set up when no order is detected.
 */
internal suspend fun enableRemoteFinishInOrderAfterAdd(
    context: android.content.Context, remote: RTorrentRemote, scope: String, hash: String
): String {
    val files = try { withContext(Dispatchers.IO) { remote.listFiles(hash) } }
        catch (_: Exception) { return "Finish in Order was not set up: the server's file list could not be read." }
    val asLocal = remoteFilesAsLocal(files)
    val suggestion = FinishInOrderPlanner.suggest(asLocal)
        ?: return "Finish in Order was not set up: no episode or numbered order was found. You can arrange one from the transfer."
    val store = FinishInOrderStore(context, scope)
    val policy = FinishInOrderPlanner.policyFor(FinishInOrderPlanner.Mode.BALANCED, asLocal, suggestion.order)
    if (!FinishInOrderController.exclusive { withContext(Dispatchers.IO) { store.save(hash, FinishInOrderRecord(policy)) } })
        return "Finish in Order could not be saved; priorities are unchanged."
    return when (val step = try { stepRemoteFinishInOrder(remote, store, hash) } catch (_: Exception) { null }) {
        is FinishInOrderRunner.Step.Applied, is FinishInOrderRunner.Step.Idle ->
            "Finish in Order is on for ${suggestion.order.size} files."
        is FinishInOrderRunner.Step.Suspended -> "Finish in Order is paused: " + step.record.suspendedReason
        else -> "Finish in Order is saved; the server did not confirm its first change. Check the transfer before relying on it."
    }
}

/**
 * Finish in Order for one rTorrent transfer. rTorrent offers only off/normal/
 * high, so the current file is High and the rest stay Normal; it advances
 * only while Chunks is open and connected to this account.
 */
@Composable
internal fun RemoteFinishInOrderPanel(
    remote: RTorrentRemote,
    transfer: RemoteTransfer,
    scope: String,
    files: List<RemoteFile>,
    stale: Boolean,
    onMutationStarted: () -> Boolean,
    onSettled: () -> Unit
) {
    if (files.size < 2 || !Regex("[0-9a-fA-F]{40}").matches(transfer.hash)) return
    val asLocal = remember(files) { remoteFilesAsLocal(files) }
    ServerFinishInOrderPanel(
        hash = transfer.hash, scope = scope, asLocal = asLocal, stale = stale,
        intro = "On rTorrent the current file gets High and the rest stay Normal. The server " +
            "only moves on while Chunks is open and connected to this account.",
        isActive = { index -> files.getOrNull(index)?.priority == 2 },
        twoBands = true,
        step = { store -> stepRemoteFinishInOrder(remote, store, transfer.hash) },
        onMutationStarted = onMutationStarted, onSettled = onSettled)
}

/**
 * Finish in Order controls for one server-side transfer (rTorrent or any
 * shared-port client). Every policy edit is serialized with the runner; each
 * write is one guarded, verified step and is never retried.
 */
@Composable
internal fun ServerFinishInOrderPanel(
    hash: String,
    scope: String,
    /** The transfer's files in the planner's terms. */
    asLocal: List<LocalTransferFile>,
    stale: Boolean,
    /** Shown before the order review: how this client's levels and advancing work. */
    intro: String,
    /** True for the file the client currently has at its highest Finish in Order level. */
    isActive: (Int) -> Boolean,
    /** The client stores no distinct Low, so no mode that promises Low is offered. */
    twoBands: Boolean,
    step: suspend (FinishInOrderStore) -> FinishInOrderRunner.Step?,
    onMutationStarted: () -> Boolean,
    onSettled: () -> Unit
) {
    if (asLocal.size < 2) return
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    val store = remember(scope) { FinishInOrderStore(context, scope) }
    var record by remember(hash, scope) { mutableStateOf<FinishInOrderRecord?>(null) }
    var reviewing by remember(hash) { mutableStateOf(false) }
    var working by remember(hash) { mutableStateOf(false) }
    var message by remember(hash) { mutableStateOf<String?>(null) }
    val names = remember(asLocal) { asLocal.associate { it.index to it.path.substringAfterLast('/') } }
    LaunchedEffect(hash, scope, asLocal) {
        record = withContext(Dispatchers.IO) { store.get(hash) }
    }

    fun act(block: suspend () -> String?) {
        if (!onMutationStarted()) {
            message = "Another server change is still settling. Try again after it finishes."
            return
        }
        working = true
        coroutines.launch {
            try {
                message = try { block() } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    "The server did not confirm this change. Refresh before trying again; nothing is retried."
                }
                record = withContext(Dispatchers.IO) { store.get(hash) }
            } finally {
                working = false
                onSettled()
            }
        }
    }

    fun describe(step: FinishInOrderRunner.Step?): String? = when (step) {
        null, FinishInOrderRunner.Step.NotObservable -> "The server's file list could not be read; nothing changed."
        is FinishInOrderRunner.Step.Applied -> "File priorities updated and confirmed by the server."
        is FinishInOrderRunner.Step.Idle -> null
        is FinishInOrderRunner.Step.Reconciled -> "The last interrupted change was checked; nothing was repeated."
        is FinishInOrderRunner.Step.Suspended -> step.record.suspendedReason
        is FinishInOrderRunner.Step.StorageFailed ->
            if (step.record.pending.isNotEmpty())
                "Chunks could not save recovery of the interrupted change. Its earlier outcome is still unresolved; nothing was repeated."
            else "Chunks could not save its change journal, so nothing changed."
    }

    val current = record
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (current == null) {
            if (!reviewing) OutlinedButton(onClick = { reviewing = true }, enabled = !stale && !working,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Finish in Order…") }
            else {
                val suggestion = remember(asLocal) { FinishInOrderPlanner.suggest(asLocal) }
                val initial = suggestion?.order ?: asLocal.filter { it.wanted }
                    .sortedWith { a, b -> FinishInOrderPlanner.naturalCompare(a.path, b.path) }.map { it.index }
                Text(intro, style = MaterialTheme.typography.bodySmall)
                if (initial.size >= 2) FinishInOrderReview(asLocal, initial, suggestion?.source, stale || working,
                    twoBands = twoBands,
                    onConfirm = { mode, order ->
                        reviewing = false
                        act {
                            val policy = FinishInOrderPlanner.policyFor(mode, asLocal, order)
                            val saved = FinishInOrderController.exclusive {
                                withContext(Dispatchers.IO) { store.save(hash, FinishInOrderRecord(policy)) }
                            }
                            if (!saved) "Chunks could not save Finish in Order. Nothing changed."
                            else describe(step(store))
                        }
                    }, onCancel = { reviewing = false })
                else Text("Finish in Order needs at least two selected files.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(finishInOrderStatus(current, null, names) + " Advances while Chunks is open and connected.",
                style = MaterialTheme.typography.bodySmall)
            // The server reports no per-file completion here, so "up next" is the
            // order after the file it currently has at High; a finished file
            // chosen anyway is simply skipped by the next verified step.
            val order = current.policy.order.map { it.index }
            val activePosition = order.indexOfFirst(isActive).coerceAtLeast(0)
            val upNext = order.drop(activePosition + 1).take(4)
            if (current.pending.isEmpty() && current.suspendedReason == null && upNext.isNotEmpty()) {
                Text("Up next", style = MaterialTheme.typography.labelLarge)
                upNext.forEach { index ->
                    val name = names[index] ?: "file ${index + 1}"
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            act {
                                val saved = FinishInOrderController.exclusive {
                                    withContext(Dispatchers.IO) {
                                        val next = store.get(hash)?.finishNext(index)
                                        next != null && store.save(hash, next)
                                    }
                                }
                                if (!saved) "This file could not be moved first now. Nothing changed."
                                else describe(step(store))
                                    ?: "$name is next."
                            }
                        }, enabled = !stale && !working, modifier = Modifier.heightIn(min = 48.dp)
                            .semantics { contentDescription = "Finish $name next" }) { Text("Finish this next") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current.suspendedReason != null) Button(onClick = {
                    act {
                        val resumed = FinishInOrderController.exclusive {
                            store.get(hash)?.let { store.save(hash, it.copy(suspendedReason = null)) } == true
                        }
                        if (!resumed) "Chunks could not save the change. Nothing changed."
                        else describe(step(store))
                    }
                }, enabled = !stale && !working, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Resume") }
                OutlinedButton(onClick = {
                    coroutines.launch {
                        val removed = FinishInOrderController.exclusive { store.remove(hash) }
                        message = if (removed) "Finish in Order is off. Server file priorities were left as they are."
                            else "Chunks could not turn Finish in Order off. Try again."
                        record = store.get(hash)
                    }
                }, enabled = !working, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Turn off") }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
