package io.github.gamesbyian.chunks.engine

import java.io.File

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
     * Stats each file under [saveDirectory]. A path that resolves outside the
     * save folder (".." or a symlink escape) is refused and reported, not read.
     */
    fun check(saveDirectory: File, files: List<LocalTransferFile>): Report {
        // Fail closed: without an inspectable save folder, "not started" files
        // would otherwise read as a clean report.
        val root = try {
            saveDirectory.canonicalFile.takeIf { it.isDirectory }
        } catch (_: Exception) { null }
        if (root == null) return Report(files.map { FileResult(it, Status.FOLDER_UNAVAILABLE, null) })
        return Report(files.map { file ->
            val observed = observe(root, file.path)
            FileResult(file, classify(file, observed), observed?.takeIf { it.exists && it.isFile }?.length)
        })
    }

    internal fun observe(root: File, relativePath: String): Observation? = try {
        val target = File(root, relativePath).canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) null
        else Observation(target.exists(), target.isFile, if (target.isFile) target.length() else 0L)
    } catch (_: SecurityException) {
        Observation(exists = false, isFile = false, length = 0, error = true)
    } catch (_: java.io.IOException) {
        Observation(exists = false, isFile = false, length = 0, error = true)
    }
}
