package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.model.RarVolumeCandidates

/** Bounded exact native registration and verified-piece observation.
 * Filename candidates do not establish archive identity, durable disk bytes,
 * final volume, or safe extraction. */
internal object LocalArchiveInventory {
    data class Review(
        val infoHash: String,
        val nativeToken: String,
        val savePath: String,
        val torrentProgressPpm: Int,
        val allFilesVerified: Boolean,
        val candidates: List<RarVolumeCandidates.Candidate>
    )

    fun review(engine: LocalTorrentEngine, infoHash: String): Review? {
        return try {
            val inventory = engine.readVerifiedFileInventory(infoHash, RarVolumeCandidates.MAX_FILES)
                ?: return null
            val status = inventory.status
            val candidates = RarVolumeCandidates.identify(inventory.files) ?: return null
            Review(status.infoHash, status.nativeReviewToken, status.savePath,
                status.progressPpm, inventory.files.all { it.doneBytes == it.sizeBytes }, candidates)
        } catch (_: Exception) { null }
    }
}
