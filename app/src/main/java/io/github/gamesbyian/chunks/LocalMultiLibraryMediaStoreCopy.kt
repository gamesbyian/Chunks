package io.github.gamesbyian.chunks

import android.content.Context
import android.net.Uri
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.storage.OwnedMediaStoreDocumentPublication
import io.github.gamesbyian.chunks.storage.OwnedMediaStoreZipBatchJournal

/**
 * Explicit, bounded Keep both publication of every wanted file of a finished
 * Local transfer. All original bytes are checked before any Downloads row is
 * inserted. Each new row and the batch intent use existing durable journals.
 * No generic SAF, nested output, Replace, payload deletion or automatic retry.
 */
internal object LocalMultiLibraryMediaStoreCopy {
    const val MAX_FILES = 8
    const val MAX_TOTAL_BYTES = 16 * 1024 * 1024
    enum class State { COPIED, PARTIAL, BLOCKED, NEEDS_ATTENTION }
    data class Published(val originalName: String, val publishedName: String, val uri: Uri)
    data class Result(
        val state: State,
        val message: String,
        val published: List<Published> = emptyList(),
        val remaining: Int = 0
    )
    private data class Member(val name: String, val bytes: ByteArray)
    private fun blocked(message: String) = Result(State.BLOCKED, message)
    private fun eligible(source: LocalLibraryCopySource): Boolean =
        source.files.size in 2..MAX_FILES &&
            source.bytes in 1L..MAX_TOTAL_BYTES.toLong() &&
            source.files.map { it.outputPath }.distinct().size == source.files.size &&
            source.files.all {
                LocalSingleLibraryMediaStoreCopy.supportedName.matches(it.outputPath) &&
                    it.sizeBytes in 1L..LocalSingleLibraryMediaStoreCopy.MAX_BYTES.toLong()
            }

    fun copyNew(
        context: Context,
        engine: LocalTorrentEngine,
        infoHash: String,
        cancelled: () -> Boolean = { false }
    ): Result {
        if (cancelled()) return blocked("Cancelled before destination creation.")
        fun snapshot(): LocalLibraryCopySource? {
            val transfer = engine.status(infoHash) ?: return null
            if (!transfer.infoHash.equals(infoHash, ignoreCase = true) ||
                !transfer.metadataKnown || transfer.progressPpm != 1_000_000 ||
                transfer.state !in setOf(LocalTransferState.FINISHED, LocalTransferState.SEEDING))
                return null
            return LocalLibraryCopySource.of(engine, transfer)
        }
        val source = runCatching { snapshot() }.getOrNull()
            ?: return blocked("The transfer's complete wanted files are not available.")
        if (!eligible(source))
            return blocked("Only 2–8 completed flat supported files, up to 8 MiB each and 16 MiB total, can be copied. No destination was created.")
        fun sameInventory(): Boolean = runCatching {
            val now = snapshot()
            now != null && now.sourceRoot == source.sourceRoot && now.files == source.files
        }.getOrDefault(false)
        if (cancelled() || !runCatching { engine.flushCompletedForLibraryRead(infoHash, 30_000L) }
                .getOrDefault(false) || !sameInventory())
            return blocked("The exact completed transfer or its native cache could not be confirmed. No destination was created.")

        // No row may be inserted for a rejected, changed, or cancelled source.
        // The existing pinned FD reader verifies type, identity, size and two
        // independent SHA-256 passes before returning each immutable payload.
        val members = try {
            source.files.map {
                Member(it.outputPath, LocalSingleLibraryMediaStoreCopy.readAndRecheck(
                    source.sourceRoot, it.source, it.sizeBytes, cancelled))
            }
        } catch (_: Exception) {
            return blocked("A source file changed, was cancelled or could not be safely read. No destination was created.")
        }
        if (cancelled() || !sameInventory())
            return blocked("The source selection changed or was cancelled before creation.")
        val batch = try {
            OwnedMediaStoreZipBatchJournal.begin(context, members.map { it.name },
                OwnedMediaStoreZipBatchJournal.Kind.LIBRARY)
        } catch (_: Exception) {
            return blocked("The complete copy intent could not be saved durably. Nothing was published.")
        }
        return batch.use { journal ->
            val receipts = ArrayList<Published>(members.size)
            for ((index, member) in members.withIndex()) {
                if (cancelled() || !sameInventory()) {
                    val recorded = runCatching { journal.stopped(index) }.isSuccess
                    return@use Result(if (recorded) State.PARTIAL else State.NEEDS_ATTENTION,
                        "Stopped after " + receipts.size + " verified copies. Earlier files and originals were preserved.",
                        receipts.toList(), members.size - index)
                }
                val mime = when (member.name.substringAfterLast('.')) {
                    "txt" -> "text/plain"
                    "json" -> "application/json"
                    "torrent" -> "application/x-bittorrent"
                    else -> "application/octet-stream"
                }
                try {
                    val row = OwnedMediaStoreDocumentPublication.publish(
                        context, member.name, mime, member.bytes, cancelled)
                    receipts += Published(member.name, row.publishedName, row.uri)
                    journal.rowPublished(index, row)
                } catch (_: Exception) {
                    runCatching { journal.uncertain(index) }
                    return@use Result(State.NEEDS_ATTENTION,
                        "Earlier copies and originals were preserved. Review the uncertain row or batch record. No automatic cleanup or retry occurred.",
                        receipts.toList(), members.size - receipts.size)
                }
            }
            try {
                journal.finished()
                Result(State.COPIED, "Created " + receipts.size +
                    " distinct verified Downloads copies. Original files remain available for seeding.",
                    receipts.toList(), 0)
            } catch (_: Exception) {
                Result(State.NEEDS_ATTENTION,
                    "All files have verified receipts, but batch completion could not be saved. Review the operation record.",
                    receipts.toList(), 0)
            }
        }
    }
}
