package io.github.gamesbyian.chunks

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.SecureRandom
import java.util.Random

/**
 * Proves that a candidate phone download Place accepts the kind of file I/O
 * the native engine performs: a sparse write beyond end-of-file, a write at
 * offset zero, fsync, independent readback, rename and delete. It never
 * inspects or touches any existing file other than its own hidden probe.
 *
 * Passing this probe is necessary but NOT sufficient to claim a Place works
 * for torrents: the native engine's own write/resume/seed behaviour must be
 * proven separately (see LocalPublicDownloadsPlaceInstrumentedTest).
 */
internal object LocalPlaceWriteProbe {
    private const val SPARSE_OFFSET = 1L shl 20
    private const val BLOCK = 4096

    /**
     * Lazily and idempotently creates exactly one folder level inside an
     * existing [parent]. A symlink, a non-directory or anything resolving
     * outside [parent] is refused, so a planted link cannot redirect payloads.
     */
    fun ensureChild(parent: File, name: String): File? {
        if (name.isEmpty() || name == "." || name == ".." ||
            name.any { it == '/' || it == '\\' || it.code < 32 }) return null
        return try {
            if (!parent.isDirectory || Files.isSymbolicLink(parent.toPath())) return null
            val child = File(parent, name)
            val path = child.toPath()
            if (Files.isSymbolicLink(path)) return null
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !child.mkdir() &&
                !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return null
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return null
            if (child.canonicalFile.parentFile != parent.canonicalFile) return null
            child
        } catch (_: Exception) {
            null
        }
    }

    /** Returns null when [folder] passed every check, otherwise a short reason. */
    fun verify(folder: File, random: Random = SecureRandom()): String? {
        if (!folder.isAbsolute) return "Folder path is not absolute"
        try {
            if (Files.isSymbolicLink(folder.toPath())) return "Folder is a symbolic link"
            if (!folder.isDirectory) return "Folder does not exist"
        } catch (_: Exception) {
            return "Folder cannot be inspected"
        }
        val token = ByteArray(8).also(random::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val probe = File(folder, ".chunks-write-probe-$token.tmp")
        val renamed = File(folder, ".chunks-write-probe-$token.ok")
        val head = ByteArray(BLOCK).also(random::nextBytes)
        val tail = ByteArray(BLOCK).also(random::nextBytes)
        try {
            try {
                RandomAccessFile(probe, "rw").use { file ->
                    file.seek(SPARSE_OFFSET)
                    file.write(tail)
                    file.seek(0)
                    file.write(head)
                    file.fd.sync()
                }
            } catch (_: Exception) {
                return "Folder does not accept new files"
            }
            try {
                RandomAccessFile(probe, "r").use { file ->
                    if (file.length() != SPARSE_OFFSET + BLOCK) return "Written size did not read back"
                    val readHead = ByteArray(BLOCK)
                    val readTail = ByteArray(BLOCK)
                    file.readFully(readHead)
                    file.seek(SPARSE_OFFSET)
                    file.readFully(readTail)
                    if (!readHead.contentEquals(head) || !readTail.contentEquals(tail))
                        return "Written bytes did not read back exactly"
                }
            } catch (_: Exception) {
                return "Written file could not be read back"
            }
            if (!probe.renameTo(renamed) || !renamed.isFile) return "Folder does not allow renaming files"
            if (!renamed.delete()) return "Folder does not allow deleting Chunks' own files"
        } finally {
            probe.delete()
            renamed.delete()
        }
        return if (probe.exists() || renamed.exists()) "Probe file could not be removed" else null
    }
}
