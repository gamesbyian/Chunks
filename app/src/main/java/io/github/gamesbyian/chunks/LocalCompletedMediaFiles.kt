package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalFileDiskCheck
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.media.MediaFileHint
import io.github.gamesbyian.chunks.media.inspection.FileMediaByteSource
import io.github.gamesbyian.chunks.media.inspection.MediaByteSource
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Read-only access to a phone transfer's own *completed* files for media evidence (a cover image,
 * embedded album art or tags). A file qualifies only when the engine reports every byte verified
 * and an on-demand, no-follow check finds a regular file of exactly that size inside the real save
 * folder (same rules as [LocalFileDiskCheck]). A path string alone never grants access. Never
 * writes, renames or deletes; remote transfers never get this.
 */
internal class LocalCompletedMediaFiles(private val savePath: String, files: List<LocalTransferFile>) {
    private val byPath = files.filter { it.sizeBytes > 0 && it.doneBytes >= it.sizeBytes }.associateBy { it.path }

    /** Hints for recognition and cover discovery: every listed file, completed or not. */
    val hints: List<MediaFileHint> = files.map { MediaFileHint(it.path, it.sizeBytes) }

    /** Call off the main thread. */
    fun resolve(hint: MediaFileHint): File? {
        val file = byPath[hint.path] ?: return null
        val root = try {
            val selected = File(savePath).toPath().toAbsolutePath()
            if (!File(savePath).isAbsolute || Files.isSymbolicLink(selected) ||
                !Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS)) return null
            selected.toRealPath().toFile()
        } catch (_: Exception) { return null }
        val observed = LocalFileDiskCheck.observe(root, file.path) ?: return null
        if (LocalFileDiskCheck.classify(file, observed) != LocalFileDiskCheck.Status.PRESENT) return null
        return File(root, file.path)
    }

    /** Bounded whole-file read for a small image; null when unavailable or larger than [limit]. */
    fun read(hint: MediaFileHint, limit: Int = 6 * 1024 * 1024): ByteArray? {
        val f = resolve(hint) ?: return null
        if (f.length() > limit) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    fun open(hint: MediaFileHint): MediaByteSource? = resolve(hint)?.let { FileMediaByteSource(it) }

    /**
     * The transfer's main inspectable media file: the largest *completed* Matroska/MP4-family file
     * (the container formats the header inspector understands). Null when none qualifies.
     */
    val primaryInspectable: MediaFileHint? = byPath.values
        .filter { it.path.substringAfterLast('.', "").lowercase() in INSPECTABLE }
        .maxByOrNull { it.sizeBytes }?.let { MediaFileHint(it.path, it.sizeBytes) }

    companion object {
        val INSPECTABLE = setOf("mkv", "mka", "webm", "mp4", "m4v", "m4a", "m4b", "mov")
    }
}
