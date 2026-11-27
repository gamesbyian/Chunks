package io.github.gamesbyian.chunks

import android.system.Os
import io.github.gamesbyian.chunks.engine.LocalFileDiskCheck
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.media.MediaFileHint
import io.github.gamesbyian.chunks.media.inspection.MediaByteSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale

/**
 * Bounded read-only evidence from the exact completed file row in a Local
 * inventory. No remote reads, filesystem mutations or native piece recheck.
 * Never return a pathname-only reader: every range checks the same physical
 * observation on a nonblocking, no-follow, regular-file descriptor.
 */
internal class LocalCompletedMediaFiles(private val savePath: String, files: List<LocalTransferFile>) {
    private val byPath = eligible(files)

    /** Hints for recognition and cover discovery: every listed file, completed or not. */
    val hints: List<MediaFileHint> = files.map { MediaFileHint(it.path, it.sizeBytes) }

    private fun row(hint: MediaFileHint): LocalTransferFile? =
        byPath[hint.path]?.takeIf { hint.sizeBytes == null || hint.sizeBytes == it.sizeBytes }

    private fun root(): File? = try {
        val selected = File(savePath).toPath()
        if (!File(savePath).isAbsolute || Files.isSymbolicLink(selected) ||
            !Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS)) null
        else selected.toRealPath().toFile()
    } catch (_: Exception) { null }

    private fun present(root: File, file: LocalTransferFile): Boolean =
        LocalFileDiskCheck.classify(file, LocalFileDiskCheck.observe(root, file.path)) ==
            LocalFileDiskCheck.Status.PRESENT

    /** Non-authorizing diagnostic only. No reader may use the result as an
     * unpinned filename. Kept for point-in-time selection/path tests. */
    internal fun resolve(hint: MediaFileHint): File? {
        val file = row(hint) ?: return null
        val root = root() ?: return null
        return File(root, file.path).takeIf { present(root, file) }
    }

    /** Bounded whole-file read for a small cover. Never File.readBytes(),
     * which can allocate beyond the reviewed limit if the file grows. */
    fun read(hint: MediaFileHint, limit: Int = MAX_COVER_BYTES): ByteArray? {
        val row = row(hint) ?: return null
        if (limit !in 1..MAX_COVER_BYTES || row.sizeBytes > limit) return null
        return try {
            val source = open(hint) ?: return null
            source.read(0, row.sizeBytes.toInt()).takeIf { it.size.toLong() == row.sizeBytes }
        } catch (_: Exception) { null }
    }

    fun open(hint: MediaFileHint): MediaByteSource? {
        val file = row(hint) ?: return null
        val root = root() ?: return null
        if (!present(root, file)) return null
        val path = File(root, file.path)
        return try {
            val named = Os.lstat(path.absolutePath)
            val identity = LocalPinnedReadIdentity.from(named)
            if (identity.size != file.sizeBytes || !identity.matches(named)) return null
            val source = ReviewedSource(root, file, path, identity)
            source.prove()
            source
        } catch (_: Exception) { null }
    }

    private inner class ReviewedSource(
        private val reviewedRoot: File,
        private val file: LocalTransferFile,
        private val path: File,
        private val identity: LocalPinnedReadIdentity
    ) : MediaByteSource {
        override val size = identity.size
        private var refused = false

        private fun current(): Boolean =
            root() == reviewedRoot && present(reviewedRoot, file) &&
                identity.matches(Os.lstat(path.absolutePath))

        private fun <T> pinned(block: (java.io.FileDescriptor) -> T): T {
            if (refused) throw IOException("Media observation expired")
            try {
                if (!current()) throw IOException("Media source changed")
                val fd = LocalSingleLibraryMediaStoreCopy.openPinnedRegularSource(path)
                try {
                    if (!identity.matches(Os.fstat(fd)) || !current())
                        throw IOException("Media source changed before reading")
                    val result = block(fd)
                    if (!identity.matches(Os.fstat(fd)) || !current())
                        throw IOException("Media source changed during reading")
                    return result
                } finally { Os.close(fd) }
            } catch (_: Exception) {
                // Even if the original path later reappears, a partially
                // consumed observation must never silently rebind.
                refused = true
                throw IOException("Media source identity or read could not be confirmed")
            }
        }

        @Synchronized
        fun prove() = pinned { Unit }

        @Synchronized
        override fun read(offset: Long, length: Int): ByteArray {
            if (offset < 0 || length !in 0..MAX_RANGE_BYTES)
                throw IOException("Invalid or excessive media range")
            return pinned { fd ->
                val count = if (offset >= size) 0 else minOf(length.toLong(), size - offset).toInt()
                val result = ByteArray(count)
                var filled = 0
                while (filled < count) {
                    val n = Os.pread(fd, result, filled, count - filled, offset + filled)
                    if (n <= 0 || n > count - filled) throw IOException("Incomplete media read")
                    filled += n
                }
                result
            }
        }
    }

    /** Largest completed file of a supported header-only format. */
    val primaryInspectable: MediaFileHint? = byPath.values
        .filter { it.path.substringAfterLast('.', "").lowercase(Locale.ROOT) in INSPECTABLE }
        .maxByOrNull { it.sizeBytes }?.let { MediaFileHint(it.path, it.sizeBytes) }

    companion object {
        const val MAX_COVER_BYTES = 6 * 1024 * 1024
        private const val MAX_RANGE_BYTES = 8 * 1024 * 1024
        val INSPECTABLE = setOf("mkv", "mka", "webm", "mp4", "m4v", "m4a", "m4b", "mov")

        internal fun eligible(files: List<LocalTransferFile>): Map<String, LocalTransferFile> {
            if (files.map { it.index }.toSet().size != files.size ||
                files.map { it.path }.toSet().size != files.size) return emptyMap()
            return files.filter { it.sizeBytes > 0 && it.doneBytes == it.sizeBytes }.associateBy { it.path }
        }
    }
}
