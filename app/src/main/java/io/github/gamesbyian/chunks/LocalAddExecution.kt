package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandResult
import io.github.gamesbyian.chunks.engine.LocalFileBatchOutcome
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferFile

/**
 * The ONE native Local `.torrent` Add sequence, shared by the device screen's
 * Universal Add and the shared-port [LocalTorrentClient]. The caller owns
 * authorization (ticket, Place, Local mutation fence) and calls this once,
 * off the main thread, inside that authorization:
 *
 * checked stopped Add → reviewed file skips matched to the registered job's
 * own files (nothing written on a mismatch) → [beforeStart] while still
 * stopped (e.g. Finish in Order) → the requested Start only once the skips
 * are confirmed; otherwise "Stopped after verification" of files already at
 * the exact torrent paths. Never retried.
 */
internal object LocalAddExecution {
    data class TorrentResult(
        val added: LocalAddResult,
        /** null when no skips were requested, or their write never happened. */
        val skipOutcome: LocalFileBatchOutcome?,
        val skipsConfirmed: Boolean,
        /** Non-null only when Start was actually sent. */
        val start: LocalCommandResult?,
        /** libtorrent is checking existing files and stops itself afterwards. */
        val verifyingExisting: Boolean,
        /** Worst outcome of the reviewed Low/High writes; null when none were requested. */
        val priorityOutcome: LocalFileBatchOutcome? = null
    )

    suspend fun torrent(
        engine: LocalTorrentEngine,
        bytes: ByteArray,
        folder: java.io.File,
        skipsRequested: Boolean,
        /** The exact registered files to skip, or null when the layout differs from the review. */
        reviewedSkips: (List<LocalTransferFile>) -> List<LocalTransferFile>?,
        startNow: Boolean,
        beforeStart: suspend (String) -> Unit = {},
        onAdded: (LocalAddResult) -> Unit = {},
        /**
         * Reviewed Low/High for wanted files: registered index → native level
         * (1 or 7), or null when the registered layout differs from the review.
         */
        reviewedPriorities: ((List<LocalTransferFile>) -> Map<Int, Int>?)? = null
    ): TorrentResult {
        val added = engine.addStoppedChecked(bytes, folder).also(onAdded)
        if (added !is LocalAddResult.AddedStopped)
            return TorrentResult(added, null, !skipsRequested, null, false)
        val hash = added.status.infoHash
        var skipOutcome: LocalFileBatchOutcome? = null
        val skipsConfirmed = if (!skipsRequested) true else {
            val reviewed = runCatching { engine.files(hash) }.getOrNull()?.let(reviewedSkips)
            skipOutcome = reviewed?.let { runCatching { engine.setFilesWantedBatch(hash, it, false) }.getOrNull() }
            AddFileChoices.confirmed(skipOutcome)
        }
        // Reviewed Low/High, still stopped, only after the skips were confirmed.
        val priorityOutcome = if (reviewedPriorities == null || !skipsConfirmed) null else {
            val files = runCatching { engine.files(hash) }.getOrNull()
            val levels = files?.let(reviewedPriorities)
            if (files == null || levels == null) LocalFileBatchOutcome.STALE_REVIEW else {
                var worst = LocalFileBatchOutcome.UNCHANGED
                for ((index, level) in levels.toSortedMap()) {
                    val file = runCatching { engine.files(hash) }.getOrNull()?.singleOrNull { it.index == index }
                    val outcome = if (file == null) LocalFileBatchOutcome.STALE_REVIEW
                        else runCatching { engine.setFilePriority(hash, file, level) }
                            .getOrDefault(LocalFileBatchOutcome.UNCERTAIN)
                    if (outcome == LocalFileBatchOutcome.VERIFIED && worst == LocalFileBatchOutcome.UNCHANGED)
                        worst = outcome
                    else if (!AddFileChoices.confirmed(outcome)) { worst = outcome; break }
                }
                worst
            }
        }
        val choicesConfirmed = skipsConfirmed && (priorityOutcome == null || AddFileChoices.confirmed(priorityOutcome))
        if (choicesConfirmed) beforeStart(hash)
        if (startNow && choicesConfirmed)
            return TorrentResult(added, skipOutcome, true, engine.resume(hash), false, priorityOutcome)
        val verifying = choicesConfirmed && runCatching { engine.beginStoppedVerification(hash) }.getOrDefault(false)
        return TorrentResult(added, skipOutcome, skipsConfirmed, null, verifying, priorityOutcome)
    }
}
