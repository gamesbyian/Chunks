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
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.gamesbyian.chunks.engine.LocalFileBatchOutcome
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The exact-identity engine calls canonical local Details may make; production uses [LocalTorrentEngine]. */
internal interface LocalDetailsPort {
    fun status(infoHash: String): LocalTransferStatus?
    fun files(infoHash: String): List<LocalTransferFile>?
    fun setFileWanted(infoHash: String, index: Int, wanted: Boolean): LocalCommandOutcome
    fun setFilePriority(infoHash: String, reviewed: LocalTransferFile, priority: Int): LocalFileBatchOutcome
    fun setFilesWantedBatch(infoHash: String, reviewed: List<LocalTransferFile>, wanted: Boolean): LocalFileBatchOutcome
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
    override fun removeKeepingDataIfMatching(infoHash: String, name: String, savePath: String, token: String) =
        engine.removeKeepingDataIfMatching(infoHash, name, savePath, token)
    override fun removeWithDataIfMatching(infoHash: String, name: String, savePath: String, token: String) =
        engine.removeWithDataIfMatching(infoHash, name, savePath, token)
}

/**
 * Full-Details parity for a Local Device job (NEXT-PUSH-UX-RECONCILIATION "one
 * full Details experience"): files & priorities and Remove live in canonical
 * Details, as they already do for an authenticated server job. These reuse the
 * device screen's existing guarded components and exact-identity engine calls.
 * Every write takes the one process-wide local change lock, writes nothing when it
 * is held, reads back once and never retries.
 */
@Composable
internal fun LocalDetailsOperations(
    infoHash: String,
    /** The canonical list is out of date or another local change is in flight. */
    stale: Boolean,
    engine: () -> LocalDetailsPort? = { LocalEngineHolder.current()?.let(::EngineLocalDetailsPort) }
) {
    val scope = rememberCoroutineScope()
    var busy by remember(infoHash) { mutableStateOf(false) }
    var note by remember(infoHash) { mutableStateOf<String?>(null) }
    val locked = stale || busy

    /** One fenced, single-attempt local change; [block] runs on IO and returns the outcome text. */
    fun change(block: suspend () -> String?) {
        if (busy) return
        if (!LocalMutationFence.revision.beginMutation()) {
            note = "Another change to this device's transfers is in progress. Nothing was changed."
            return
        }
        busy = true
        scope.launch {
            try {
                note = try { withContext(Dispatchers.IO) { block() } }
                    catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (_: Exception) { "The result could not be confirmed. Refresh before another change; nothing was retried." }
            } finally {
                LocalMutationFence.revision.settledMutation()
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LocalDetailsFiles(infoHash, locked, engine, ::change)
        LocalDetailsRemove(infoHash, locked, engine, ::change)
        note?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
private fun LocalDetailsFiles(
    infoHash: String,
    locked: Boolean,
    engine: () -> LocalDetailsPort?,
    change: (suspend () -> String?) -> Unit
) {
    var expanded by rememberSaveable(infoHash) { mutableStateOf(false) }
    var files by remember(infoHash) { mutableStateOf<List<LocalTransferFile>?>(null) }
    var revision by remember(infoHash) { mutableIntStateOf(0) }
    LaunchedEffect(infoHash, expanded, revision, locked) {
        if (!expanded) { files = null; return@LaunchedEffect }
        // A lock means a change is settling: keep the last list visible, read-only.
        if (locked) return@LaunchedEffect
        files = withContext(Dispatchers.IO) { runCatching { engine()?.files(infoHash) }.getOrNull() }
    }
    fun write(block: (LocalDetailsPort) -> String?) = change {
        val current = engine() ?: return@change "This transfer is no longer on this device."
        block(current).also { revision++ }
    }
    OutlinedButton(onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag(ChunksSemantics.DETAILS_LOCAL_FILES)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
        Text(if (expanded) "Hide files & priorities" else "Files & priorities…")
    }
    if (expanded) LocalFileSelection(
        files, locked, transferKey = infoHash,
        onBatchFileWanted = { reviewed, want ->
            write { localFileBatchFeedback(it.setFilesWantedBatch(infoHash, reviewed, want), want) }
        },
        onFilePriority = { reviewed, priority ->
            write { localFilePriorityFeedback(it.setFilePriority(infoHash, reviewed, priority)) }
        },
        onFileWanted = { index, want ->
            write { localFileWantedFeedback(it.setFileWanted(infoHash, index, want)) }
        },
        finishInOrder = { shownFiles ->
            FinishInOrderPanel(infoHash, shownFiles, locked, onPrioritiesChanged = { revision++ })
        }
    )
}

@Composable
private fun LocalDetailsRemove(
    infoHash: String,
    locked: Boolean,
    engine: () -> LocalDetailsPort?,
    change: (suspend () -> String?) -> Unit
) {
    // The exact identity shown in the confirmation is the identity removed.
    var reviewed by remember(infoHash) { mutableStateOf<LocalTransferStatus?>(null) }
    var reading by remember(infoHash) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val shown = reviewed
    if (shown == null) {
        OutlinedButton(
            onClick = {
                reading = true
                scope.launch {
                    reviewed = withContext(Dispatchers.IO) { runCatching { engine()?.status(infoHash) }.getOrNull() }
                    reading = false
                }
            },
            enabled = !locked && !reading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(ChunksSemantics.DETAILS_LOCAL_REMOVE)
        ) { Text("Remove from this device…") }
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
            change {
                val current = engine() ?: return@change "This transfer is no longer on this device."
                val acknowledged = runCatching {
                    current.removeKeepingDataIfMatching(shown.infoHash, shown.name, shown.savePath,
                        shown.nativeReviewToken)
                }.getOrNull()
                val stillListed = runCatching { current.status(shown.infoHash) != null }.getOrNull()
                localRemoveReadbackFeedback(acknowledged, stillListed)
                    ?: "Removed from this device. Downloaded files were kept."
            }
        },
        onCancel = { reviewed = null })
    val dataRemovable = shown.metadataKnown && shown.infoHash.length == 40
    OutlinedButton(
        onClick = {
            reviewed = null
            change {
                val current = engine() ?: return@change "This transfer is no longer on this device."
                val receipt = runCatching {
                    current.removeWithDataIfMatching(shown.infoHash, shown.name, shown.savePath,
                        shown.nativeReviewToken)
                }.getOrElse { LocalDeleteDataReceipt(LocalDeleteDataDispatch.UNCERTAIN) }
                localDeleteDataFeedback(receipt, awaitDeleteReadback(receipt))
            }
        },
        enabled = !locked && dataRemovable,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag(ChunksSemantics.DETAILS_LOCAL_REMOVE_DATA)
    ) { Text(if (dataRemovable) "Remove transfer and data" else "Remove transfer and data (unavailable)") }
    Text(if (!dataRemovable)
        "Delete-data is unavailable while this transfer's file mapping cannot be verified. Keeping data remains available."
        else "This removes the transfer and deletes its exact torrent files in this folder, including matching " +
            "files that were already there before Add. Unrelated files are kept. This cannot be undone. The result " +
            "is independently checked; an interrupted or partial cleanup will not be described as successful.",
        style = MaterialTheme.typography.bodySmall)
}

/** Bounded wait for the asynchronous native deletion's independent readback (never a retry). */
internal fun awaitDeleteReadback(receipt: LocalDeleteDataReceipt, timeoutMs: Long = 15_000L): LocalDeleteDataReadback {
    if (receipt.dispatch !in setOf(LocalDeleteDataDispatch.REQUESTED, LocalDeleteDataDispatch.UNCERTAIN) ||
        receipt.nativeCompletion == null) return LocalDeleteDataReadback.UNAVAILABLE
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    var value = receipt.payloadReadback()
    while (value == LocalDeleteDataReadback.UNAVAILABLE && System.nanoTime() < deadline) {
        Thread.sleep(150)
        value = receipt.payloadReadback()
    }
    return value
}

internal fun localDeleteDataFeedback(receipt: LocalDeleteDataReceipt, readback: LocalDeleteDataReadback): String =
    when (receipt.dispatch) {
        LocalDeleteDataDispatch.REFUSED ->
            "Deletion was not requested: " + (receipt.reason ?: "The transfer or its destination needs fresh review.")
        LocalDeleteDataDispatch.NOT_FOUND ->
            "Deletion was not requested: this transfer is no longer in the local device engine."
        LocalDeleteDataDispatch.REQUESTED, LocalDeleteDataDispatch.UNCERTAIN -> when (readback) {
            LocalDeleteDataReadback.ABSENT ->
                "Verified: the transfer was removed, native deletion completed and all its exact payload files are absent. Unrelated folder contents were not requested for deletion."
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

internal fun localFileBatchFeedback(outcome: LocalFileBatchOutcome, wanted: Boolean): String = when (outcome) {
    LocalFileBatchOutcome.VERIFIED -> if (wanted) "Page-level file selection verified."
        else "Page-level file selection verified. Previously downloaded bytes were kept."
    LocalFileBatchOutcome.UNCHANGED -> "The selected files already have that priority. Nothing was changed."
    LocalFileBatchOutcome.STALE_REVIEW -> "File selection changed since review. No batch write was submitted; refresh the file list."
    LocalFileBatchOutcome.NOT_FOUND -> "Torrent metadata unavailable. No file batch was submitted."
    LocalFileBatchOutcome.UNCERTAIN -> "Batch selection outcome uncertain: some priorities may have changed. " +
        "Wait for fresh file readback before considering another action."
}
