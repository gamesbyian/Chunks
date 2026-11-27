package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.media.MediaFileHint
import io.github.gamesbyian.chunks.media.inspection.MediaByteSource
import io.github.gamesbyian.chunks.model.RarVolumeCandidates
import io.github.gamesbyian.chunks.model.RarVolumeHeaderReader
import io.github.gamesbyian.chunks.model.RarVolumeStructure
import java.io.IOException

/** Bounded exact native registration and verified-piece observation.
 * Filename candidates do not establish archive identity, durable disk bytes,
 * final volume, or safe extraction. */
internal object LocalArchiveInventory {
    private const val MAX_HEADER_VOLUMES = 32
    private const val MAX_TOTAL_HEADER_BYTES = 8 * 1024 * 1024

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

    fun canInspectHeaders(candidate: RarVolumeCandidates.Candidate): Boolean =
        !candidate.ambiguous && candidate.missingCount == 0 &&
            candidate.volumes.size in 1..MAX_HEADER_VOLUMES && candidate.allListedComplete &&
            candidate.volumes.all { it.file.wanted }

    /** Cache readback and pinned header ranges for one reviewed group. No decoder or destination access. */
    fun inspectHeaders(
        engine: LocalTorrentEngine,
        reviewed: Review,
        candidate: RarVolumeCandidates.Candidate
    ): RarVolumeStructure {
        val unconfirmed = RarVolumeStructure.UNCONFIRMED
        if (candidate !in reviewed.candidates || !canInspectHeaders(candidate)) return unconfirmed
        val files = candidate.volumes.map { it.file }
        return try {
            if (!engine.flushVerifiedFilesForRead(reviewed.infoHash, reviewed.nativeToken, reviewed.savePath, files))
                return unconfirmed
            val reader = LocalCompletedMediaFiles(reviewed.savePath, files)
            val sources = files.map { file ->
                reader.open(MediaFileHint(file.path, file.sizeBytes)) ?: return unconfirmed
            }
            var remaining = MAX_TOTAL_HEADER_BYTES
            val headers = sources.map { source ->
                RarVolumeHeaderReader.inspect(object : MediaByteSource {
                    override val size = source.size
                    override fun read(offset: Long, length: Int): ByteArray {
                        if (Thread.currentThread().isInterrupted || length > remaining)
                            throw IOException("RAR header review interrupted or over budget")
                        remaining -= length
                        return source.read(offset, length)
                    }
                })
            }
            // An empty range still rechecks the original physical observation.
            sources.forEach { it.read(0, 0) }
            val after = review(engine, reviewed.infoHash) ?: return unconfirmed
            if (after.nativeToken != reviewed.nativeToken || after.savePath != reviewed.savePath ||
                candidate !in after.candidates) return unconfirmed
            RarVolumeStructure.inspect(headers)
        } catch (_: Exception) { unconfirmed }
    }
}
