package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalFileDiskCheck
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import java.io.File
import java.util.Locale

/**
 * Independent local read-only file inspection (F03.13). First capability is
 * completed wanted files of a fully complete local job. A native review token
 * and exact file index/path/size identify the source, not a display alias.
 */
internal object LocalFileInspection {
    const val MAX_PREVIEW_BYTES = 64 * 1024

    data class Review(
        val infoHash: String,
        val nativeToken: String,
        val savePath: String,
        val files: List<LocalTransferFile>
    )

    sealed interface Outcome {
        data class Observed(
            val file: LocalTransferFile,
            val sha256: String,
            val textPreview: String?,
            val previewTruncated: Boolean
        ) : Outcome
        data class Refused(val reason: String) : Outcome
    }

    private fun ready(status: LocalTransferStatus, infoHash: String) =
        status.infoHash.equals(infoHash, ignoreCase = true) &&
            status.nativeReviewToken.isNotBlank() && status.metadataKnown &&
            status.error == null && status.progressPpm == 1_000_000 &&
            status.state in setOf(LocalTransferState.FINISHED, LocalTransferState.SEEDING) &&
            File(status.savePath).isAbsolute

    private fun matches(status: LocalTransferStatus, review: Review) =
        ready(status, review.infoHash) && status.nativeReviewToken == review.nativeToken &&
            status.savePath == review.savePath

    fun review(engine: LocalTorrentEngine, infoHash: String): Review? = try {
        val status = engine.status(infoHash)?.takeIf { ready(it, infoHash) }
        if (status == null) null
        else {
            val rows = engine.files(infoHash, LocalLibraryCopySelection.PROBE_LIMIT)
            if (rows == null || rows.size > LocalLibraryCopySelection.MAX_FILES ||
                rows.map { it.index }.toSet().size != rows.size ||
                rows.map { it.path }.toSet().size != rows.size) null
            else {
                val files = rows.filter {
                    it.wanted && it.sizeBytes in 0..LocalPinnedPayloadRead.MAX_BYTES &&
                        it.doneBytes == it.sizeBytes
                }
                val review = Review(status.infoHash, status.nativeReviewToken, status.savePath, files)
                review.takeIf { files.isNotEmpty() &&
                    engine.status(infoHash)?.let { current -> matches(current, review) } == true }
            }
        }
    } catch (_: Exception) { null }

    fun inspect(
        engine: LocalTorrentEngine,
        review: Review,
        file: LocalTransferFile,
        cancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {}
    ): Outcome {
        fun refused(reason: String) = Outcome.Refused(reason)
        fun stillReviewed(): Boolean {
            val fresh = review(engine, review.infoHash) ?: return false
            return fresh.nativeToken == review.nativeToken && fresh.savePath == review.savePath &&
                fresh.files.singleOrNull { it.index == file.index } == file
        }
        if (cancelled() || file !in review.files || !stillReviewed())
            return refused("The reviewed file or transfer is no longer current. Inspect the file list again.")
        // Completion alone does not establish a durable native cache flush.
        if (!runCatching { engine.flushCompletedForLibraryRead(review.infoHash, 30_000L) }.getOrDefault(false))
            return refused("The native file cache could not be confirmed ready for reading.")
        if (cancelled() || !stillReviewed())
            return refused("The inspection was cancelled or the reviewed file changed.")
        return try {
            val selectedRoot = File(review.savePath)
            fun onDisk() = LocalFileDiskCheck.check(selectedRoot, listOf(file))
                .results.singleOrNull()?.status == LocalFileDiskCheck.Status.PRESENT
            if (!onDisk()) return refused("The exact regular file could not be found at its expected size.")
            val root = selectedRoot.canonicalFile
            val source = File(root, file.path)
            val preview = wantsPreview(file.path)
            val observed = LocalPinnedPayloadRead.read(
                root, source, file.sizeBytes,
                captureLimit = if (preview) MAX_PREVIEW_BYTES else 0,
                cancelled = cancelled, onProgress = onProgress
            )
            if (cancelled() || !stillReviewed() || !onDisk() || selectedRoot.canonicalFile != root)
                return refused("The inspection was cancelled or the source changed during reading.")
            Outcome.Observed(file, observed.sha256,
                if (preview) inertText(observed.captured) else null,
                preview && file.sizeBytes > observed.captured.size.toLong())
        } catch (_: Exception) {
            refused("The file could not be read twice with matching bytes. No checksum was accepted.")
        }
    }

    internal fun wantsPreview(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase(Locale.ROOT) in
            setOf("nfo", "txt", "sfv", "md5", "sha1", "sha256")

    /** Render as ordinary text, never HTML, a link, a command or a provider query. */
    internal fun inertText(bytes: ByteArray): String =
        bytes.toString(Charsets.UTF_8).map { ch ->
            if ((ch.isISOControl() && ch !in "\n\r\t") ||
                Character.getType(ch) == Character.FORMAT.toInt()) '\uFFFD' else ch
        }.joinToString("")

    internal enum class Comparison { NONE, INVALID, MATCHES, DIFFERS }
    internal fun compare(observed: String, supplied: String): Comparison {
        val value = supplied.trim()
        if (value.isEmpty()) return Comparison.NONE
        if (!value.matches(Regex("[0-9a-fA-F]{64}"))) return Comparison.INVALID
        return if (value.equals(observed, ignoreCase = true)) Comparison.MATCHES else Comparison.DIFFERS
    }
}
