package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.model.RarVolumeCandidates

/** One bounded exact native registration observation. Archive filenames and
 * engine-reported completion counters are hints, not archive verification. */
internal object LocalArchiveInventory {
    data class Review(
        val infoHash: String,
        val nativeToken: String,
        val savePath: String,
        val torrentProgressPpm: Int,
        val candidates: List<RarVolumeCandidates.Candidate>
    )

    fun review(engine: LocalTorrentEngine, infoHash: String): Review? {
        return try {
        fun usable(status: LocalTransferStatus) =
            status.infoHash.equals(infoHash, ignoreCase = true) &&
                status.nativeReviewToken.isNotBlank() && status.metadataKnown &&
                status.error == null && status.progressPpm in 0..1_000_000 &&
                status.state !in setOf(LocalTransferState.CHECKING,
                    LocalTransferState.DOWNLOADING_METADATA, LocalTransferState.UNKNOWN)

        val before = engine.status(infoHash)?.takeIf(::usable) ?: return null
        // First bound native file vectors and get exact metadata/priority rows.
        // Then read counters, never interpret them as verified byte ranges.
        val layout = engine.readFilePriorities(infoHash, RarVolumeCandidates.MAX_FILES) ?: return null
        val files = engine.files(infoHash, RarVolumeCandidates.MAX_FILES + 1) ?: return null
        if (files.map { it.copy(doneBytes = 0L) } != layout) return null
        val candidates = RarVolumeCandidates.identify(files) ?: return null
        val after = engine.status(infoHash)?.takeIf(::usable) ?: return null
        if (after.nativeReviewToken != before.nativeReviewToken || after.savePath != before.savePath)
            return null
        Review(after.infoHash, after.nativeReviewToken, after.savePath,
            after.progressPpm, candidates)
        } catch (_: Exception) { null }
    }
}
