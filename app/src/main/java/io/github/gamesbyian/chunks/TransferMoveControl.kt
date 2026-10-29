package io.github.gamesbyian.chunks

import android.content.Context
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.TransferMove
import io.github.gamesbyian.chunks.model.TransferMove.Direction
import io.github.gamesbyian.chunks.model.TransferMove.Observation
import io.github.gamesbyian.chunks.model.TransferMove.Stage
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Device-private Move journal; one entry per direction + transfer identity. */
internal class TransferMoveStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "chunks_transfer_moves_v1", Context.MODE_PRIVATE)

    fun get(direction: Direction, infoHash: String): TransferMove.Record? = try {
        prefs.getString(key(direction, infoHash), null)?.let(TransferMove::decode)
    } catch (_: RuntimeException) { null }

    @Synchronized
    fun save(record: TransferMove.Record): Boolean = try {
        prefs.edit().putString(key(record.direction, record.infoHash), record.encode()).commit()
    } catch (_: RuntimeException) { false }

    @Synchronized
    fun remove(direction: Direction, infoHash: String): Boolean = try {
        prefs.edit().remove(key(direction, infoHash)).commit()
    } catch (_: RuntimeException) { false }

    /** Every journaled move in one direction (malformed entries are skipped). */
    fun all(direction: Direction): List<TransferMove.Record> = try {
        prefs.all.filterKeys { it.startsWith(direction.name + "_") }.values
            .mapNotNull { (it as? String)?.let(TransferMove::decode) }
    } catch (_: RuntimeException) { emptyList() }

    private fun key(direction: Direction, infoHash: String) =
        direction.name + "_" + infoHash.lowercase(Locale.ROOT)

    companion object {
        /** Opaque identity of one rTorrent endpoint + account; the URL is not stored. */
        fun scopeDigest(aliasScope: String): String =
            MessageDigest.getInstance("SHA-256").digest(aliasScope.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun hostLabel(aliasScope: String): String = try {
            URI(aliasScope.substringBefore('\u0000').trim()).host ?: "your server"
        } catch (_: Exception) { "your server" }
    }
}

/** What one side of a Move can do; each call is a fresh observation or one request. */
internal class TransferMovePorts(
    /** Present/Absent/Unknown for the job on the destination. Read-only. */
    val observeTarget: (suspend () -> Observation)?,
    /** Present/Absent/Unknown for the job on the source. Read-only. */
    val observeSource: suspend () -> Observation,
    /** One source-job-only removal request; the caller then observes. */
    val removeSource: suspend () -> Unit,
    /** Guidance when [observeTarget] is unavailable (e.g. not connected). */
    val targetUnavailableReason: String
)

/**
 * Move Transfer control for one source transfer card. [onStartMove] opens the
 * existing reviewed destination Add (the same handoff as Copy); this control
 * owns only the journal and the separately confirmed source-job removal.
 */
@Composable
internal fun TransferMoveControl(
    direction: Direction,
    infoHash: String,
    name: String,
    /** Null when the rTorrent account identity is unknown: Move is not offered. */
    serverAliasScope: String?,
    busy: Boolean,
    ports: TransferMovePorts,
    onStartMove: () -> Unit,
    /** Gate for the source removal mutation; false means another change is in flight. */
    onMutationStarted: () -> Boolean = { true },
    onMutationSettled: () -> Unit = {},
    /** Bumped by batch Move actions so this card re-reads its journal entry. */
    revision: Int = 0
) {
    if (!Regex("[0-9a-fA-F]{40}").matches(infoHash)) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { TransferMoveStore(context) }
    var record by remember(direction, infoHash, revision) { mutableStateOf(store.get(direction, infoHash)) }
    var confirmingStart by remember(infoHash) { mutableStateOf(false) }
    var confirmingRemoval by remember(infoHash) { mutableStateOf(false) }
    var working by remember(infoHash) { mutableStateOf(false) }
    var message by remember(infoHash) { mutableStateOf<String?>(null) }
    val destination = if (direction == Direction.PHONE_TO_SERVER) "server" else "phone"
    val source = if (direction == Direction.PHONE_TO_SERVER) "phone" else "server"

    fun apply(next: TransferMove.Next): Boolean = when (next) {
        is TransferMove.Next.Save -> if (store.save(next.record)) {
            record = next.record; message = next.message; true
        } else { message = "Chunks could not save the Move journal. Nothing was removed."; false }
        is TransferMove.Next.Done -> {
            store.remove(direction, infoHash); record = null; message = next.message; true
        }
        is TransferMove.Next.Keep -> { message = next.message.ifEmpty { null }; false }
    }

    fun run(block: suspend () -> Unit) {
        working = true
        scope.launch {
            try { block() } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { message = "The Move step could not be confirmed. Nothing will be retried automatically." }
            finally { working = false }
        }
    }

    val current = record
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (current == null) {
            if (serverAliasScope == null) return@Column
            ActionTile("Move Transfer to $destination…", ChunksColors.Pink,
                onClick = { confirmingStart = !confirmingStart }, enabled = !busy && !working,
                modifier = Modifier.fillMaxWidth(), icon = R.drawable.chunks_ic_move, stacked = false)
            if (confirmingStart) {
                Text("Moves the torrent job, not its files. Chunks first opens the $destination's " +
                    "Add review for this torrent. After you add it there and Chunks confirms the new " +
                    "job, you will be asked separately whether to remove the original job from the " +
                    "$source. Downloaded files are not transferred: they stay on the $source and keep " +
                    "using its storage, and the $destination may download everything again.",
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        confirmingStart = false
                        val started = TransferMove.start(direction, infoHash, name,
                            TransferMoveStore.scopeDigest(serverAliasScope),
                            TransferMoveStore.hostLabel(serverAliasScope))
                        if (store.save(started)) { record = started; onStartMove() }
                        else message = "Chunks could not save the Move. Nothing changed."
                    }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("Review $destination Add")
                    }
                    OutlinedButton(onClick = { confirmingStart = false },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
                }
            }
        } else {
            val sameServer = serverAliasScope != null &&
                TransferMoveStore.scopeDigest(serverAliasScope) == current.serverScopeDigest
            Text(when (current.stage) {
                Stage.AWAITING_TARGET -> "Move to $destination started. Add it there, then check."
                Stage.TARGET_VERIFIED -> "Move to $destination: the new job is confirmed there. " +
                    "The original job on the $source is still here."
                Stage.SOURCE_REMOVAL_REQUESTED, Stage.SOURCE_REMOVAL_UNCERTAIN ->
                    "Move to $destination: removal of the original job is unconfirmed."
            }, style = MaterialTheme.typography.bodyMedium)
            if (!sameServer) Text("This Move belongs to ${current.serverLabel}. Connect to that " +
                "account to continue it.", style = MaterialTheme.typography.bodySmall)
            val observeTarget = ports.observeTarget
            when (current.stage) {
                Stage.AWAITING_TARGET -> OutlinedButton(onClick = {
                    run { apply(TransferMove.onTargetObserved(current, observeTarget!!())) }
                }, enabled = !busy && !working && sameServer && observeTarget != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Check the $destination")
                }
                Stage.TARGET_VERIFIED -> {
                    if (!confirmingRemoval) {
                        Button(onClick = { confirmingRemoval = true },
                            enabled = !busy && !working && sameServer && observeTarget != null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Remove original job from the $source…")
                        }
                    } else {
                        Text("Remove the original torrent job from the $source? Downloaded files " +
                            "stay on the $source and still use its storage; the $source stops " +
                            "sharing this torrent. The new job on the $destination is checked " +
                            "again first.", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                confirmingRemoval = false
                                if (!onMutationStarted()) {
                                    message = "Another change is still settling. Try again after it finishes."
                                    return@Button
                                }
                                run {
                                    try {
                                        val target = observeTarget!!()
                                        if (apply(TransferMove.beginRemoval(current, target)) &&
                                            record?.stage == Stage.SOURCE_REMOVAL_REQUESTED) {
                                            val requested = record!!
                                            try { ports.removeSource() } catch (cancel: kotlinx.coroutines.CancellationException) {
                                                throw cancel
                                            } catch (_: Exception) {}
                                            val after = try { ports.observeSource() } catch (_: Exception) { Observation.Unknown }
                                            apply(TransferMove.onRemovalResult(requested, after))
                                        }
                                    } finally { onMutationSettled() }
                                }
                            }, enabled = !busy && !working, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text("Remove original job")
                            }
                            OutlinedButton(onClick = { confirmingRemoval = false },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Keep it") }
                        }
                    }
                }
                Stage.SOURCE_REMOVAL_REQUESTED, Stage.SOURCE_REMOVAL_UNCERTAIN -> OutlinedButton(onClick = {
                    run {
                        val seen = try { ports.observeSource() } catch (_: Exception) { Observation.Unknown }
                        apply(TransferMove.onSourceObserved(TransferMove.recovered(current), seen))
                    }
                }, enabled = !working, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Check the original job")
                }
            }
            if (observeTarget == null && current.stage != Stage.SOURCE_REMOVAL_UNCERTAIN &&
                current.stage != Stage.SOURCE_REMOVAL_REQUESTED)
                Text(ports.targetUnavailableReason, style = MaterialTheme.typography.bodySmall)
            if (current.stage == Stage.AWAITING_TARGET || current.stage == Stage.TARGET_VERIFIED) {
                OutlinedButton(onClick = {
                    if (store.remove(direction, infoHash)) {
                        record = null
                        message = "Move cancelled. Both jobs, if any, were left as they are."
                    }
                }, enabled = !working, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Cancel Move (keep both jobs)")
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Helpers for the two concrete directions. */
internal object TransferMoveObservations {
    suspend fun phone(infoHash: String): Observation = withContext(Dispatchers.IO) {
        val engine = LocalEngineHolder.current() ?: return@withContext Observation.Unknown
        try {
            val inventory = engine.completeIdentityInventory() ?: return@withContext Observation.Unknown
            if (inventory.any { it.equals(infoHash, ignoreCase = true) }) Observation.Present
            else Observation.Absent
        } catch (_: Exception) { Observation.Unknown }
    }

    suspend fun server(remote: io.github.gamesbyian.chunks.rpc.RTorrentRemote, infoHash: String): Observation =
        try {
            val listed = remote.listTransfers("main")
            if (listed.any { it.hash.equals(infoHash, ignoreCase = true) }) Observation.Present
            else Observation.Absent
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { Observation.Unknown }
}
