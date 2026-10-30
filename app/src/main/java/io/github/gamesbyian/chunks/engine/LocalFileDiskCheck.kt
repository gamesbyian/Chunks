package io.github.gamesbyian.chunks.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Read-only "is this transfer's data where the engine expects it?" check
 * (TRANSFER-FILE-RECOVERY, "Diagnose before assuming"). It only stats files
 * under the transfer's own save folder: it never hashes, opens for writing,
 * renames, creates or deletes anything, and a size match is never reported
 * as verified content.
 */
object LocalFileDiskCheck {
    enum class Status(val label: String, val problem: Boolean) {
        /** Engine counts verified bytes here, and the file is the expected size on disk. */
        PRESENT("on disk at the expected size (contents not re-checked)", false),
        /** No verified bytes yet and no file: normal for a file not started. */
        NOT_STARTED("not downloaded yet", false),
        /** Skipped by the user and absent: nothing expected. */
        SKIPPED("skipped", false),
        /** Exists but shorter than the torrent says, while some bytes are still to come. */
        PARTIAL("partly on disk", false),
        /** The engine has verified bytes for this file, but nothing is at its path. */
        MISSING("missing: the engine has data for it but no file is at its path", true),
        /** A finished file whose size on disk differs from the torrent. */
        SIZE_MISMATCH("size on disk differs from the torrent", true),
        /** Something other than a regular file is at the path. */
        NOT_A_FILE("something other than a file is at its path", true),
        /** The path could not be inspected (permission or storage error). */
        UNREADABLE("could not be inspected (storage access or permission)", true),
        /** The transfer's save folder is missing, not a folder or cannot be inspected. */
        FOLDER_UNAVAILABLE("its save folder is missing, not a folder or cannot be inspected", true),
        /** The torrent's path escapes the save folder; refused, never followed. */
        UNSAFE_PATH("path leaves the transfer's folder; not inspected", true)
    }

    data class Observation(val exists: Boolean, val isFile: Boolean, val length: Long, val error: Boolean = false)

    data class FileResult(val file: LocalTransferFile, val status: Status, val observedBytes: Long?)

    data class Report(val results: List<FileResult>) {
        val problems: List<FileResult> get() = results.filter { it.status.problem }
        fun count(status: Status) = results.count { it.status == status }

        fun summary(): String {
            if (results.isEmpty()) return "No files to check."
            val parts = Status.entries.mapNotNull { s -> count(s).takeIf { it > 0 }?.let { "$it ${s.label}" } }
            val lead = if (problems.isEmpty()) "No problems found on disk." else
                "${problems.size} of ${results.size} files need attention."
            return lead + " " + parts.joinToString("; ") + ". This checks presence and size only, not contents."
        }
    }

    fun classify(file: LocalTransferFile, observed: Observation?): Status {
        if (observed == null) return Status.UNSAFE_PATH
        if (observed.error) return Status.UNREADABLE
        if (!observed.exists) return when {
            file.doneBytes > 0 -> Status.MISSING
            !file.wanted -> Status.SKIPPED
            else -> Status.NOT_STARTED
        }
        if (!observed.isFile) return Status.NOT_A_FILE
        val complete = file.sizeBytes > 0 && file.doneBytes >= file.sizeBytes
        return when {
            observed.length == file.sizeBytes -> Status.PRESENT
            complete -> Status.SIZE_MISMATCH
            observed.length < file.sizeBytes -> Status.PARTIAL
            else -> Status.SIZE_MISMATCH
        }
    }

    /**
     * Diagnose file status without following torrent-declared link components.
     * Android-managed ancestors can be aliases, so canonicalize the root,
     * but do not permit the user-selected root itself to be a symlink.
     *
     * This is read-only point-in-time evidence, never a hash check or
     * authorization to reattach, reuse, overwrite, move or delete content.
     */
    fun check(saveDirectory: File, files: List<LocalTransferFile>): Report {
        val root = try {
            val selected = saveDirectory.toPath().toAbsolutePath()
            if (!saveDirectory.isAbsolute || Files.isSymbolicLink(selected) ||
                !Files.isDirectory(selected, LinkOption.NOFOLLOW_LINKS)) null
            else selected.toRealPath().takeIf {
                Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS)
            }
        } catch (_: Exception) { null }
        if (root == null)
            return Report(files.map { FileResult(it, Status.FOLDER_UNAVAILABLE, null) })
        return Report(files.map { file ->
            val observed = observe(root.toFile(), file.path)
            FileResult(file, classify(file, observed),
                observed?.takeIf { it.exists && it.isFile && !it.error }?.length)
        })
    }

    internal fun observe(
        root: File,
        relativePath: String,
        readAttrs: (Path) -> BasicFileAttributes = {
            Files.readAttributes(it, BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS)
        }
    ): Observation? {
        if (relativePath.isBlank() || relativePath.startsWith('/') ||
            relativePath.contains('\\') ||
            relativePath.any { it.code < 32 || it.code == 127 })
            return null
        val segments = relativePath.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        val base = root.toPath()
        var path: Path = base
        for ((index, segment) in segments.withIndex()) {
            path = path.resolve(segment)
            if (!path.startsWith(base)) return null
            val attrs = try {
                readAttrs(path)
            } catch (_: NoSuchFileException) {
                return Observation(exists = false, isFile = false, length = 0)
            } catch (_: Exception) {
                // Inaccessible or indeterminate is not equivalent to absent.
                return Observation(exists = false, isFile = false,
                    length = 0, error = true)
            }
            if (attrs.isSymbolicLink) return null
            if (index < segments.lastIndex) {
                if (!attrs.isDirectory) return null
            } else {
                return Observation(
                    exists = true, isFile = attrs.isRegularFile,
                    length = if (attrs.isRegularFile) attrs.size() else 0L
                )
            }
        }
        return null
    }
}
