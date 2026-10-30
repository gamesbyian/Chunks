package io.github.gamesbyian.chunks

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
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

    /** A false/unknown absence probe must never count as successful cleanup. */
    internal fun bothNamesDefinitelyAbsent(
        probe: java.nio.file.Path,
        renamed: java.nio.file.Path,
        notExists: (java.nio.file.Path) -> Boolean =
            { Files.notExists(it, LinkOption.NOFOLLOW_LINKS) }
    ): Boolean = notExists(probe) && notExists(renamed)

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
        val probePath = probe.toPath()
        val renamedPath = renamed.toPath()
        var ownsProbe = false
        var ownsRenamed = false
        try {
            // A collision with either generated name must NEVER permit the probe
            // to write, rename over, or remove someone else's existing bytes.
            if (!Files.notExists(renamedPath, LinkOption.NOFOLLOW_LINKS))
                return "Probe destination is already occupied or cannot be inspected"
            try {
                FileChannel.open(probePath, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS).use { channel ->
                    ownsProbe = true
                    fun writeAll(bytes: ByteArray) {
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) {
                            if (channel.write(buffer) <= 0)
                                throw java.io.IOException("Probe write did not progress")
                        }
                    }
                    channel.position(SPARSE_OFFSET)
                    writeAll(tail)
                    channel.position(0)
                    writeAll(head)
                    channel.force(true)
                }
            } catch (_: Exception) {
                return "Folder does not accept exclusive new files"
            }
            try {
                // A new read descriptor verifies the persisted bytes, but
                // never opens the path for writing or follows a planted link.
                FileChannel.open(probePath, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS).use { channel ->
                    if (channel.size() != SPARSE_OFFSET + BLOCK)
                        return "Written size did not read back"
                    fun readAt(offset: Long): ByteArray {
                        val buffer = ByteBuffer.allocate(BLOCK)
                        channel.position(offset)
                        while (buffer.hasRemaining()) {
                            if (channel.read(buffer) <= 0)
                                throw java.io.IOException("Probe read was incomplete")
                        }
                        return buffer.array()
                    }
                    if (!readAt(0).contentEquals(head) ||
                        !readAt(SPARSE_OFFSET).contentEquals(tail))
                        return "Written bytes did not read back exactly"
                }
            } catch (_: Exception) {
                return "Written file could not be read back"
            }
            // Files.move without REPLACE_EXISTING must refuse an occupied target.
            // A fresh existence probe narrows the window but cannot lock a
            // shared directory against another writer racing this operation.
            if (!Files.notExists(renamedPath, LinkOption.NOFOLLOW_LINKS))
                return "Probe destination is already occupied or cannot be inspected"
            try {
                Files.move(probePath, renamedPath)
                ownsProbe = false
                ownsRenamed = true
            } catch (_: Exception) {
                return "Folder does not allow non-replacing rename"
            }
            if (!Files.isRegularFile(renamedPath, LinkOption.NOFOLLOW_LINKS))
                return "Renamed probe file is not inspectable"
            try {
                Files.delete(renamedPath)
                ownsRenamed = false
            } catch (_: Exception) {
                return "Folder does not allow deleting Chunks' own files"
            }
        } finally {
            // A failed CREATE_NEW does not grant ownership; importantly,
            // never delete an existing name that the probe failed to create.
            if (ownsProbe) try { Files.deleteIfExists(probePath) } catch (_: Exception) {}
            if (ownsRenamed) try { Files.deleteIfExists(renamedPath) } catch (_: Exception) {}
        }
        return if (bothNamesDefinitelyAbsent(probePath, renamedPath)) null
            else "Probe cleanup could not be independently verified"
    }
}
