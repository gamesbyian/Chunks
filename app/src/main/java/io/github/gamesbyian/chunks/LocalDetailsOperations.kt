package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalDeleteDataDispatch
import io.github.gamesbyian.chunks.engine.LocalDeleteDataReadback
import io.github.gamesbyian.chunks.engine.LocalDeleteDataReceipt
import io.github.gamesbyian.chunks.engine.LocalEpisodeFocusPlanner
import io.github.gamesbyian.chunks.engine.LocalFileBatchOutcome
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The exact-identity engine calls canonical local Details may make; production uses [LocalTorrentEngine]. */
internal interface LocalDetailsPort {
    fun status(infoHash: String): LocalTransferStatus?
    fun files(infoHash: String): List<LocalTransferFile>?
    fun setFileWanted(infoHash: String, index: Int, wanted: Boolean): LocalCommandOutcome
    fun setFilePriority(infoHash: String, reviewed: LocalTransferFile, priority: Int): LocalFileBatchOutcome
    fun setFilesWantedBatch(infoHash: String, reviewed: List<LocalTransferFile>, wanted: Boolean): LocalFileBatchOutcome
    fun focusEpisodesOnce(infoHash: String, plan: LocalEpisodeFocusPlanner.Plan): LocalFileBatchOutcome
    fun removeKeepingDataIfMatching(infoHash: String, name: String, savePath: String, token: String): Boolean
    fun removeWithDataIfMatching(infoHash: String, name: String, savePath: String, token: String): LocalDeleteDataReceipt
}

internal class EngineLocalDetailsPort(private val engine: LocalTorrentEngine) : LocalDetailsPort {
    override fun status(infoHash: String) = engine.status(infoHash)
    override fun files(infoHash: String) = engine.files(infoHash)
    override fun setFileWanted(infoHash: String, index: Int, wanted: Boolean) =
        engine.setFileWanted(infoHash, index, wanted)
    override fun setFilePriority(infoHash: String, reviewed: LocalTransferFile, priority: Int) =
        engine.setFilePriority(infoHash, reviewed, priority)
    override fun setFilesWantedBatch(infoHash: String, reviewed: List<LocalTransferFile>, wanted: Boolean) =
        engine.setFilesWantedBatch(infoHash, reviewed, wanted)
    override fun focusEpisodesOnce(infoHash: String, plan: LocalEpisodeFocusPlanner.Plan) =
        engine.focusEpisodesOnce(infoHash, plan)
    override fun removeKeepingDataIfMatching(infoHash: String, name: String, savePath: String, token: String) =
        engine.removeKeepingDataIfMatching(infoHash, name, savePath, token)
    override fun removeWithDataIfMatching(infoHash: String, name: String, savePath: String, token: String) =
        engine.removeWithDataIfMatching(infoHash, name, savePath, token)
}

/**
 * Process-scoped local Details changes, per exact infoHash. A Remove makes its own
 * Details disappear with the job; the run and its outcome (including an incomplete or
 * uncertain deletion) must outlive that, like [LocalRecheckRuns]. One fenced attempt,
 * one readback, never a retry.
 */
internal object LocalDetailsRuns {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val running = mutableStateMapOf<String, Boolean>()
    val outcomes = mutableStateMapOf<String, String>()
    /** Bumped after each settled change so an open file list reads back. */
    val revisions = mutableStateMapOf<String, Int>()

    const val ENGINE_UNAVAILABLE = "The local engine is not running. Nothing was changed."
    const val LOCKED = "Another change to this device's transfers is in progress. Nothing was changed."

    /** False (and nothing written) when another local change holds the shared lock. */
    fun run(infoHash: String, block: suspend () -> String?): Boolean {
        synchronized(this) {
            if (running[infoHash] == true) return false
            if (!LocalMutationFence.revision.beginMutation()) {
                outcomes[infoHash] = LOCKED
                return false
            }
            running[infoHash] = true
        }
        outcomes.remove(infoHash)
        scope.launch {
            try {
                val text = try { block() }
                    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (_: Exception) { "The result could not be confirmed. Refresh before another change; nothing was retried." }
                if (text != null) outcomes[infoHash] = text
            } finally {
                LocalMutationFence.revision.settledMutation()
                synchronized(this@LocalDetailsRuns) { running.remove(infoHash) }
                revisions[infoHash] = (revisions[infoHash] ?: 0) + 1
            }
        }
        return true
    }
}

/**
 * Full-Details parity for a Local Device job (NEXT-PUSH-UX-RECONCILIATION "one
 * full Details experience"): files & priorities, episode focus, Finish in Order and
 * Remove live in canonical Details, as they do for an authenticated server job,
 * reusing the device screen's existing guarded components and exact-identity calls.
 * When [current] is false only a retained outcome is shown.
 */
@Composable
internal fun LocalDetailsOperations(
    infoHash: String,
    current: Boolean,
    /** The canonical list is out of date. The shared local lock is observed here too. */
    stale: Boolean,
    engine: () -> LocalDetailsPort? = { LocalEngineHolder.current()?.let(::EngineLocalDetailsPort) }
) {
    val lockHeld by LocalMutationFence.revision.blockingState
    val running = LocalDetailsRuns.running[infoHash] == true
    val locked = !current || stale || lockHeld || running
    val outcome = LocalDetailsRuns.outcomes[infoHash]
    // Retained across the job leaving the list; dropped when the person leaves Details
    // (unless a change is still settling), so a later same-hash job starts clean.
    androidx.compose.runtime.DisposableEffect(infoHash) {
        onDispose {
            if (LocalDetailsRuns.running[infoHash] != true) LocalDetailsRuns.outcomes.remove(infoHash)
        }
    }
    fun change(block: (LocalDetailsPort) -> String?) {
        LocalDetailsRuns.run(infoHash) {
            val port = engine() ?: return@run LocalDetailsRuns.ENGINE_UNAVAILABLE
            block(port)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (current) {
            LocalDetailsFiles(infoHash, locked, engine, ::change)
            LocalDetailsRemove(infoHash, locked, engine, ::change)
        }
        outcome?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(ChunksSemantics.DETAILS_LOCAL_OUTCOME)
                    .semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
private fun LocalDetailsFiles(
    infoHash: String,
    locked: Boolean,
    engine: () -> LocalDetailsPort?,
    change: ((LocalDetailsPort) -> String?) -> Unit
) {
    var expanded by rememberSaveable(infoHash) { mutableStateOf(false) }
    var files by remember(infoHash) { mutableStateOf<List<LocalTransferFile>?>(null) }
    var readProblem by remember(infoHash) { mutableStateOf<String?>(null) }
    val revision = LocalDetailsRuns.revisions[infoHash] ?: 0
    // Reading writes nothing, so it is not gated on the write lock: the list stays
    // visible while a change settles and reads back after each settled change.
    LaunchedEffect(infoHash, expanded, revision) {
        if (!expanded) { files = null; readProblem = null; return@LaunchedEffect }
        val port = withContext(Dispatchers.IO) { engine() }
        if (port == null) { readProblem = LocalDetailsRuns.ENGINE_UNAVAILABLE; return@LaunchedEffect }
        val read = withContext(Dispatchers.IO) { runCatching { port.files(infoHash) }.getOrNull() }
        files = read
        readProblem = if (read == null) "This transfer's files could not be read yet." else null
    }
    OutlinedButton(onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag(ChunksSemantics.DETAILS_LOCAL_FILES)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
        Text(if (expanded) "Hide files & priorities" else "Files & priorities…")
    }
    if (!expanded) return
    readProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (readProblem != null && files == null) return
    LocalFileSelection(
        files, locked, transferKey = infoHash,
        onBatchFileWanted = { reviewed, want ->
            change { localFileBatchFeedback(it.setFilesWantedBatch(infoHash, reviewed, want)) }
        },
        onFilePriority = { reviewed, priority ->
            change { localFilePriorityFeedback(it.setFilePriority(infoHash, reviewed, priority)) }
        },
        onFocusEpisodes = { plan -> change { localEpisodeFocusFeedback(it.focusEpisodesOnce(infoHash, plan)) } },
        // Finish in Order manages its own journaled priority writes, as in the device screen.
        finishInOrder = { shownFiles ->
            FinishInOrderPanel(infoHash, shownFiles, locked, onPrioritiesChanged = {
                LocalDetailsRuns.revisions[infoHash] = (LocalDetailsRuns.revisions[infoHash] ?: 0) + 1
            })
        },
        onFileWanted = { index, want ->
            change { localFileWantedFeedback(it.setFileWanted(infoHash, index, want)) }
        }
    )
    // Same read-only file tools as the device screen's Files section.
    val shownFiles = files ?: return
    val savePath = savePathOf(infoHash, engine) ?: return
    LocalFilesOnDiskCheck(infoHash, savePath, shownFiles)
    val inspectable = remember(savePath, shownFiles) { LocalCompletedMediaFiles(savePath, shownFiles) }
    inspectable.primaryInspectable?.let { hint ->
        io.github.gamesbyian.chunks.media.ui.MediaTechnicalDetails(
            fileName = hint.path.substringAfterLast('/'), open = { inspectable.open(hint) })
    }
}

/** The job's engine-reported folder, read off the UI thread; null until known. */
@Composable
private fun savePathOf(infoHash: String, engine: () -> LocalDetailsPort?): String? {
    var path by remember(infoHash) { mutableStateOf<String?>(null) }
    LaunchedEffect(infoHash) {
        path = withContext(Dispatchers.IO) { runCatching { engine()?.status(infoHash)?.savePath }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
    }
    return path
}

@Composable
private fun LocalDetailsRemove(
    infoHash: String,
    locked: Boolean,
    engine: () -> LocalDetailsPort?,
    change: ((LocalDetailsPort) -> String?) -> Unit
) {
    // The exact identity shown in the confirmation is the identity removed.
    var reviewed by remember(infoHash) { mutableStateOf<LocalTransferStatus?>(null) }
    var reading by remember(infoHash) { mutableStateOf(false) }
    var problem by remember(infoHash) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val shown = reviewed
    if (shown == null) {
        OutlinedButton(
            onClick = {
                reading = true
                problem = null
                scope.launch {
                    val port = withContext(Dispatchers.IO) { engine() }
                    val status = if (port == null) null
                        else withContext(Dispatchers.IO) { runCatching { port.status(infoHash) }.getOrNull() }
                    reviewed = status
                    problem = when {
                        port == null -> LocalDetailsRuns.ENGINE_UNAVAILABLE
                        status == null -> "This transfer's current identity could not be read. Nothing was removed."
                        else -> null
                    }
                    reading = false
                }
            },
            enabled = !locked && !reading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(ChunksSemantics.DETAILS_LOCAL_REMOVE)
        ) { Text("Remove from this device…") }
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        return
    }
    Text("Remove from this device: " + shown.name, style = MaterialTheme.typography.titleSmall)
    Text("Infohash: " + shown.infoHash, style = MaterialTheme.typography.bodySmall)
    Text("Current folder: " + shown.savePath, style = MaterialTheme.typography.bodySmall)
    Text("Keeping data stops this torrent in Chunks but leaves downloaded files in their current folder.",
        style = MaterialTheme.typography.bodyMedium)
    LocalTransferConfirmationActions(
        confirmLabel = "Remove transfer, keep data",
        cancelLabel = "Cancel",
        enabled = !locked,
        onConfirm = {
            reviewed = null
            change { port ->
                val acknowledged = runCatching {
                    port.removeKeepingDataIfMatching(shown.infoHash, shown.name, shown.savePath, shown.nativeReviewToken)
                }.getOrNull()
                val stillListed = runCatching { port.status(shown.infoHash) != null }.getOrNull()
                localRemoveReadbackFeedback(acknowledged, stillListed)
                    ?: "Removed from this device. Downloaded files were kept."
            }
        },
        onCancel = { reviewed = null })
    val dataRemovable = shown.metadataKnown && shown.infoHash.length == 40
    OutlinedButton(
        onClick = {
            reviewed = null
            LocalDetailsRuns.run(infoHash) {
                val port = engine() ?: return@run LocalDetailsRuns.ENGINE_UNAVAILABLE
                val receipt = runCatching {
                    port.removeWithDataIfMatching(shown.infoHash, shown.name, shown.savePath, shown.nativeReviewToken)
                }.getOrElse { LocalDeleteDataReceipt(LocalDeleteDataDispatch.UNCERTAIN) }
                localDeleteDataFeedback(receipt, awaitDeleteReadback(receipt))
            }
        },
        enabled = !locked && dataRemovable,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag(ChunksSemantics.DETAILS_LOCAL_REMOVE_DATA)
    ) { Text(if (dataRemovable) "Remove transfer and data" else "Remove transfer and data (unavailable)") }
    Text(if (!dataRemovable)
        "Delete-data is unavailable for this transfer while its file mapping or inventory cannot be verified. Keeping data remains available."
        else "This removes the transfer and deletes its exact torrent files in this folder, including matching " +
            "files that were already there before Add. Unrelated files are kept. This cannot be undone. The result " +
            "is independently checked; an interrupted or partial cleanup will not be described as successful.",
        style = MaterialTheme.typography.bodySmall)
}

/** Bounded, suspending wait for the asynchronous native deletion's independent readback (never a retry). */
internal suspend fun awaitDeleteReadback(receipt: LocalDeleteDataReceipt, timeoutMs: Long = 15_000L): LocalDeleteDataReadback {
    if (receipt.dispatch !in setOf(LocalDeleteDataDispatch.REQUESTED, LocalDeleteDataDispatch.UNCERTAIN) ||
        receipt.nativeCompletion == null) return LocalDeleteDataReadback.UNAVAILABLE
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    var value = receipt.payloadReadback()
    while (value == LocalDeleteDataReadback.UNAVAILABLE && System.nanoTime() < deadline) {
        delay(150)
        value = receipt.payloadReadback()
    }
    return value
}

// One wording for the device screen and canonical Details.

internal fun localDeleteDataFeedback(receipt: LocalDeleteDataReceipt, readback: LocalDeleteDataReadback): String =
    when (receipt.dispatch) {
        LocalDeleteDataDispatch.REFUSED ->
            "Deletion was not requested: " + (receipt.reason ?: "The transfer or its destination needs fresh review.")
        LocalDeleteDataDispatch.NOT_FOUND ->
            "Deletion was not requested: this transfer is no longer in the local device engine."
        LocalDeleteDataDispatch.REQUESTED, LocalDeleteDataDispatch.UNCERTAIN -> when (readback) {
            LocalDeleteDataReadback.ABSENT ->
                "Verified: the reviewed transfer was removed, native deletion completed and all its exact payload files are absent. Unrelated folder contents were not requested for deletion."
            LocalDeleteDataReadback.STILL_PRESENT ->
                "Native deletion finished but at least one reviewed payload file remains. The operation is incomplete. Recheck the folder before any new action."
            LocalDeleteDataReadback.NATIVE_FAILED ->
                "Native deletion failed. No retry was sent; inspect the remaining files and transfer state before trying again."
            LocalDeleteDataReadback.UNAVAILABLE ->
                "Deletion outcome is still uncertain. The transfer may already be removed. No automatic retry will be sent; refresh the folder and engine state before acting."
        }
    }

internal fun localFileWantedFeedback(outcome: LocalCommandOutcome): String? = when (outcome) {
    LocalCommandOutcome.VERIFIED -> null
    LocalCommandOutcome.REFUSED -> "File selection was refused. Inspect the transfer before changing files."
    LocalCommandOutcome.NOT_FOUND -> "This transfer or file is no longer available."
    LocalCommandOutcome.UNCERTAIN -> "File selection is unconfirmed. Wait for fresh status before trying again."
}

internal fun localFilePriorityFeedback(outcome: LocalFileBatchOutcome): String = when (outcome) {
    LocalFileBatchOutcome.VERIFIED -> "Native file download priority verified. No downloaded data was deleted."
    LocalFileBatchOutcome.UNCHANGED -> "This file already has that priority. Nothing changed."
    LocalFileBatchOutcome.STALE_REVIEW -> "File identity or priority changed during review. Refresh files first."
    LocalFileBatchOutcome.NOT_FOUND -> "The target file or transfer was not found."
    LocalFileBatchOutcome.UNCERTAIN -> "Native file priority change not confirmed. Refresh status before another change."
}

internal fun localFileBatchFeedback(outcome: LocalFileBatchOutcome): String = when (outcome) {
    LocalFileBatchOutcome.VERIFIED -> "Page-level file selection verified. Previously downloaded bytes were kept."
    LocalFileBatchOutcome.UNCHANGED -> "The selected files already have that priority. Nothing was changed."
    LocalFileBatchOutcome.STALE_REVIEW -> "File selection changed since review. No batch write was submitted; refresh the file list."
    LocalFileBatchOutcome.NOT_FOUND -> "Torrent metadata unavailable. No file batch was submitted."
    LocalFileBatchOutcome.UNCERTAIN -> "Batch selection outcome uncertain: some priorities may have changed. " +
        "Wait for fresh file readback before considering another action."
}

internal fun localEpisodeFocusFeedback(outcome: LocalFileBatchOutcome): String = when (outcome) {
    LocalFileBatchOutcome.VERIFIED ->
        "Episode-first priorities verified. The first unfinished episode is High; later episodes receive " +
            "background priority. This is a one-time focus, not automatic completion scheduling."
    LocalFileBatchOutcome.UNCHANGED -> "Episodes already have this focus. No priorities changed."
    LocalFileBatchOutcome.STALE_REVIEW -> "Episode files or priorities changed. Refresh the file list and review the order again."
    LocalFileBatchOutcome.NOT_FOUND -> "Transfer is no longer available on the local device."
    LocalFileBatchOutcome.UNCERTAIN -> "Some episode priorities may have changed. Refresh the exact file list before " +
        "taking another action; no automatic retry."
}
