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

/** Device-private Move journals, exact endpoint+account+job identity.
 *
 * Older entries were indexed only by direction+hash. Preserve those bytes and
 * look them up only when their decoded exact server scope matches; a second
 * account with the same hash receives an independent new key. No migration
 * clears an uncertain Move or invents a successful source removal.
 */
internal class TransferMoveStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "chunks_transfer_moves_v1", Context.MODE_PRIVATE)

    private fun legacyKey(direction: Direction, infoHash: String) =
        direction.name + "_" + infoHash.lowercase(Locale.ROOT)

    private fun scopedKey(direction: Direction, infoHash: String, serverDigest: String) =
        direction.name + "_" + serverDigest + "_" + infoHash.lowercase(Locale.ROOT)

    private fun scopedKey(record: TransferMove.Record) =
        scopedKey(record.direction, record.infoHash, record.serverScopeDigest)

    private fun exactlyMatches(record: TransferMove.Record?, direction: Direction,
                               hash: String, serverDigest: String?): Boolean =
        record != null && record.direction == direction &&
            record.infoHash.equals(hash, ignoreCase = true) &&
            (serverDigest == null || record.serverScopeDigest == serverDigest)

    /** An exact account finds only its own Move. Disconnected UI may show a
     * single unambiguous journal for read-only reconnect guidance. */
    @Synchronized
    fun get(direction: Direction, infoHash: String,
            serverScopeDigest: String? = null): TransferMove.Record? {
        if (!Regex("[0-9a-fA-F]{40}").matches(infoHash) ||
            (serverScopeDigest != null && !Regex("[0-9a-f]{64}").matches(serverScopeDigest)))
            return null
        if (serverScopeDigest == null)
            return all(direction).filter { it.infoHash.equals(infoHash, ignoreCase = true) }
                .singleOrNull()
        return try {
            val scoped = scopedKey(direction, infoHash, serverScopeDigest)
            // An existing malformed or changed scoped entry must never fall
            // through to a legacy record and authorize removal of its newer job.
            if (prefs.contains(scoped)) {
                prefs.getString(scoped, null)?.let(TransferMove::decode)
                    .takeIf { exactlyMatches(it, direction, infoHash, serverScopeDigest) }
            } else {
                val old = prefs.getString(legacyKey(direction, infoHash), null)
                    ?.let(TransferMove::decode)
                old.takeIf { exactlyMatches(it, direction, infoHash, serverScopeDigest) }
            }
        } catch (_: RuntimeException) { null }
    }

    @Synchronized
    fun save(record: TransferMove.Record): Boolean = synchronized(JOURNAL_LOCK) {
        try {
            val scopedPresent = prefs.contains(scopedKey(record))
            val previous = get(record.direction, record.infoHash, record.serverScopeDigest)
            // Never replace a corrupt existing scoped journal: its attempted
            // write and recovery state are unknown until explicitly repaired.
            // A replacement source generation/name/directory cannot silently
            // take over an unresolved journal for the same account and hash.
            // Fresh source re-observation must precede another removal attempt.
            val clobbers = previous != null && (
                previous.name != record.name ||
                previous.sourceReviewToken != record.sourceReviewToken ||
                previous.sourceDirectory != record.sourceDirectory ||
                (previous.stage == Stage.SOURCE_REMOVAL_UNCERTAIN &&
                    record.stage in setOf(Stage.AWAITING_TARGET, Stage.SOURCE_REMOVAL_REQUESTED))
            )
            if (clobbers || (scopedPresent && previous == null)) false else {
                val old = prefs.getString(legacyKey(record.direction, record.infoHash), null)
                    ?.let(TransferMove::decode)
                val edit = prefs.edit().putString(scopedKey(record), record.encode())
                // Matching legacy record is retired atomically with its new key.
                // Another account's legacy journal remains entirely untouched.
                if (exactlyMatches(old, record.direction, record.infoHash, record.serverScopeDigest))
                    edit.remove(legacyKey(record.direction, record.infoHash))
                edit.commit()
            }
        } catch (_: RuntimeException) { false }
    }

    /** Forget one exact journal only, never another account's same-hash job. */
    @Synchronized
    fun remove(record: TransferMove.Record): Boolean = synchronized(JOURNAL_LOCK) { try {
        // A stale callback cannot discard a newer stage or a replacement
        // source review, even within the same exact server/hash identity.
        if (get(record.direction, record.infoHash, record.serverScopeDigest) !=
            TransferMove.recovered(record)) false
        else {
            val old = prefs.getString(legacyKey(record.direction, record.infoHash), null)
                ?.let(TransferMove::decode)
            val edit = prefs.edit().remove(scopedKey(record))
            if (exactlyMatches(old, record.direction, record.infoHash, record.serverScopeDigest))
                edit.remove(legacyKey(record.direction, record.infoHash))
            edit.commit()
        }
    } catch (_: RuntimeException) { false } }

    /** Compatibility for existing batch callers until they pass exact records.
     * Multiple accounts sharing a hash must fail closed, not guess which one. */
    @Synchronized
    fun remove(direction: Direction, infoHash: String): Boolean {
        return synchronized(JOURNAL_LOCK) {
            val only = all(direction).filter { it.infoHash.equals(infoHash, ignoreCase = true) }
                .singleOrNull() ?: return@synchronized false
            remove(only)
        }
    }

    /** Enumerate every account's valid journal with exact-key precedence over
     * a duplicated legacy key. Neither list order nor a hash alone grants a
     * deletion or changes a remote profile. */
    @Synchronized
    fun all(direction: Direction): List<TransferMove.Record> = try {
        val snapshot = prefs.all
        snapshot.entries.mapNotNull { (key, raw) ->
            val record = (raw as? String)?.let(TransferMove::decode)
                ?: return@mapNotNull null
            val legacy = key == legacyKey(direction, record.infoHash)
            if (record.direction != direction ||
                (key != scopedKey(record) && !legacy) ||
                // A broken or newer exact scoped entry must not let an older
                // legacy value be resurrected for batch source retirement.
                (legacy && snapshot.containsKey(scopedKey(record))))
                null else key to record
        }.sortedBy { (key, record) -> if (key == scopedKey(record)) 0 else 1 }
            .map { it.second }
            .distinctBy { Triple(it.direction, it.infoHash, it.serverScopeDigest) }
    } catch (_: RuntimeException) { emptyList() }

    companion object {
        // MainActivity and local/Combined cards instantiate independent Store
        // wrappers. Serialize read-modify-commit across those instances.
        private val JOURNAL_LOCK = Any()

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
 * A matching transfer name/hash is insufficient when the currently selected
 * server profile changed. Recheck the exact account scope at the final source
 * retirement boundary, even after a confirmation dialog is already open.
 */
internal fun moveRetirementScopeConfirmed(
    record: TransferMove.Record,
    activeServerScope: String?,
    currentName: String,
    currentSourceToken: String?,
    currentSourceDirectory: String?
): Boolean = activeServerScope != null &&
    TransferMoveStore.scopeDigest(activeServerScope) == record.serverScopeDigest &&
    TransferMove.sourceIdentityMatches(
        record, currentName, currentSourceToken, currentSourceDirectory
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
    /** Exact ephemeral native Add generation for a local-origin Move. */
    sourceReviewToken: String? = null,
    /** Exact original rTorrent directory at server-origin Move review. */
    sourceDirectory: String? = null,
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
    val aliasStore = remember(context) { TransferAliasStore(context) }
    val serverDigest = serverAliasScope?.let(TransferMoveStore::scopeDigest)
    var record by remember(direction, infoHash, revision, serverDigest) {
        mutableStateOf(store.get(direction, infoHash, serverDigest))
    }
    var confirmingStart by remember(infoHash, serverDigest) { mutableStateOf(false) }
    var confirmingRemoval by remember(infoHash, sourceReviewToken, sourceDirectory, serverDigest) {
        mutableStateOf(false)
    }
    var working by remember(infoHash) { mutableStateOf(false) }
    var message by remember(infoHash) { mutableStateOf<String?>(null) }
    val destination = if (direction == Direction.LOCAL_TO_SERVER) "server" else "local"
    val destinationDisplay = if (direction == Direction.LOCAL_TO_SERVER) "server" else "the local device"
    val source = if (direction == Direction.LOCAL_TO_SERVER) "local device" else "server"

    fun apply(next: TransferMove.Next): Boolean = when (next) {
        is TransferMove.Next.Save -> {
            val firstConfirmedTarget = record?.stage == Stage.AWAITING_TARGET &&
                next.record.stage == Stage.TARGET_VERIFIED &&
                moveRetirementScopeConfirmed(next.record, serverAliasScope,
                    name, sourceReviewToken, sourceDirectory)
            if (store.save(next.record)) {
                // The destination is already independently observed by the
                // Move model before this stage. Only local Chunks presentation
                // follows; target user labels always win, source job/files stay.
                val carried = if (firstConfirmedTarget && serverAliasScope != null) {
                    val source = if (direction == Direction.LOCAL_TO_SERVER)
                        TransferCollectionStore.LOCAL_ENGINE_SCOPE else serverAliasScope
                    val target = if (direction == Direction.LOCAL_TO_SERVER)
                        serverAliasScope else TransferCollectionStore.LOCAL_ENGINE_SCOPE
                    aliasStore.carryVerifiedMoveAlias(source, target, infoHash)
                } else null
                record = next.record
                message = next.message + if (carried == TransferAliasStore.MoveAliasCarry.FAILED)
                    " The job was verified, but Chunks could not carry its display alias. You can edit the destination alias."
                    else ""
                true
            } else {
                message = "Chunks could not save the Move journal. Nothing was removed."
                false
            }
        }
        is TransferMove.Next.Done -> {
            if (record?.let { store.remove(it) } == true) {
                record = null; message = next.message; true
            } else {
                message = "The original job was checked, but the Move journal could not be cleared. Recheck it; no mutation will be repeated."
                false
            }
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
            ActionTile("Move Transfer to $destinationDisplay…", ChunksColors.Pink,
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
                            TransferMoveStore.hostLabel(serverAliasScope),
                            sourceReviewToken, sourceDirectory)
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
            val sameSource = TransferMove.sourceIdentityMatches(
                current, name, sourceReviewToken, sourceDirectory)
            val canRetire = moveRetirementScopeConfirmed(
                current, serverAliasScope, name, sourceReviewToken, sourceDirectory)
            Text(when (current.stage) {
                Stage.AWAITING_TARGET -> "Move to $destination started. Add it there, then check."
                Stage.TARGET_VERIFIED -> "Move to $destination: the new job is confirmed there. " +
                    "The original job on the $source is still here."
                Stage.SOURCE_REMOVAL_REQUESTED, Stage.SOURCE_REMOVAL_UNCERTAIN ->
                    "Move to $destination: removal of the original job is unconfirmed."
            }, style = MaterialTheme.typography.bodyMedium)
            if (!sameServer) Text("This Move belongs to ${current.serverLabel}. Connect to that " +
                "account to continue it.", style = MaterialTheme.typography.bodySmall)
            if (!sameSource) Text("The original transfer has changed or this older Move " +
                "cannot identify its original source. Nothing will be removed. Cancel this " +
                "Move and start a new one from the current transfer.",
                style = MaterialTheme.typography.bodySmall)
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
                            enabled = !busy && !working && canRetire && observeTarget != null,
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
                                if (!moveRetirementScopeConfirmed(
                                        current, serverAliasScope, name, sourceReviewToken, sourceDirectory) ||
                                        observeTarget == null) {
                                    message = "The server account, original transfer or destination changed. No Remove was sent."
                                    return@Button
                                }
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
                            }, enabled = !busy && !working && canRetire && observeTarget != null,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text("Remove original job")
                            }
                            OutlinedButton(onClick = { confirmingRemoval = false },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Keep it") }
                        }
                    }
                }
                Stage.SOURCE_REMOVAL_REQUESTED, Stage.SOURCE_REMOVAL_UNCERTAIN -> OutlinedButton(onClick = {
                    if (!sameServer) {
                        message = "Reconnect to the Move's original server account before checking its job."
                        return@OutlinedButton
                    }
                    run {
                        val seen = try { ports.observeSource() } catch (_: Exception) { Observation.Unknown }
                        apply(TransferMove.onSourceObserved(TransferMove.recovered(current), seen))
                    }
                }, enabled = !working && sameServer, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Check the original job")
                }
            }
            if (observeTarget == null && current.stage != Stage.SOURCE_REMOVAL_UNCERTAIN &&
                current.stage != Stage.SOURCE_REMOVAL_REQUESTED)
                Text(ports.targetUnavailableReason, style = MaterialTheme.typography.bodySmall)
            if (current.stage == Stage.AWAITING_TARGET || current.stage == Stage.TARGET_VERIFIED) {
                OutlinedButton(onClick = {
                    if (!sameServer) {
                        message = "Reconnect to the Move's original server account before cancelling its journal."
                        return@OutlinedButton
                    }
                    if (store.remove(current)) {
                        record = null
                        message = "Move cancelled. Both jobs, if any, were left as they are."
                    }
                }, enabled = !working && sameServer, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Cancel Move (keep both jobs)")
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Helpers for the two concrete directions. */
internal object TransferMoveObservations {
    suspend fun local(infoHash: String): Observation = withContext(Dispatchers.IO) {
        val engine = LocalEngineHolder.current() ?: return@withContext Observation.Unknown
        try {
            val inventory = engine.completeIdentityInventory() ?: return@withContext Observation.Unknown
            if (inventory.any { it.equals(infoHash, ignoreCase = true) }) Observation.Present
            else Observation.Absent
        } catch (_: Exception) { Observation.Unknown }
    }

    /** Exact session membership, independent of a potentially filtered `main` view.
     * rTorrent d.name is keyed by infohash; only its specific unknown-hash
     * fault establishes absence. Any other RPC failure remains Unknown. */
    suspend fun server(remote: io.github.gamesbyian.chunks.rpc.RTorrentRemote, infoHash: String): Observation =
        try {
            if (remote.lookupName(infoHash) == null) Observation.Absent else Observation.Present
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { Observation.Unknown }
}
