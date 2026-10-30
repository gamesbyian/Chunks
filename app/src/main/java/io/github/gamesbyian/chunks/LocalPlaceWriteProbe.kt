package io.github.gamesbyian.chunks

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.LinkOption
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.FileDescriptor
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Random

/**
 * Proves that a candidate phone download Place accepts the kind of file I/O
 * the native engine performs: a sparse write beyond end-of-file, a write at
 * offset zero, fsync, independent readback, exclusive publication and delete. It never
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
    fun verify(
        folder: File,
        random: Random = SecureRandom(),
        openExclusive: (Path, () -> Unit) -> ExclusiveProbeFile = ::openAndroidExclusive,
        // Test-only interleaving after exclusive open, before descriptor fstat.
        afterCreate: () -> Unit = {},
        // Inject a competing foreign destination AFTER the final pathname
        // check. Exclusive publication must still refuse without modifying it.
        beforePublish: () -> Unit = {},
        // Keep this original last parameter for callers that use a trailing
        // lambda to inject races at the pre-publication rename boundary.
        beforeRename: () -> Unit = {}
    ): String? {
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
        var created: ExclusiveProbeFile? = null
        var published: ExclusiveProbeFile? = null
        // Identity comes from the exclusively opened descriptor, never a later
        // pathname lookup. Retain that descriptor through cleanup so its inode
        // cannot be recycled while we compare dev/inode against lstat.
        fun isStillOwned(path: Path): Boolean = try {
            created?.owns(path) == true
        } catch (_: Exception) { false }
        fun isStillPublished(path: Path): Boolean = try {
            published?.owns(path) == true
        } catch (_: Exception) { false }
        try {
            // A collision with either generated name must NEVER permit the probe
            // to write, rename over, or remove someone else's existing bytes.
            if (!Files.notExists(renamedPath, LinkOption.NOFOLLOW_LINKS))
                return "Probe destination is already occupied or cannot be inspected"
            try {
                val opened = openExclusive(probePath, afterCreate)
                created = opened
                ownsProbe = true
                opened.writeAt(SPARSE_OFFSET, tail)
                opened.writeAt(0, head)
                opened.sync()
            } catch (_: Exception) {
                return "Folder does not accept exclusive new files"
            }
            if (!isStillOwned(probePath))
                return "Probe source identity changed after write"
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
            if (!isStillOwned(probePath))
                return "Probe source identity changed during readback"
            // Do not rename over a path checked only at a point in time.
            // Standard Files.move without REPLACE_EXISTING is not uniformly
            // non-replacing on POSIX providers under a concurrent race.
            // A second exclusively created no-follow descriptor instead has
            // genuine fail-on-existing-name semantics, including on providers
            // where hard links are unsupported (e.g. emulated shared storage).
            if (!Files.notExists(renamedPath, LinkOption.NOFOLLOW_LINKS))
                return "Probe destination is already occupied or cannot be inspected"
            try {
                if (!isStillOwned(probePath))
                    return "Probe source identity changed before publication"
                beforeRename()
                if (!isStillOwned(probePath) ||
                    !Files.notExists(renamedPath, LinkOption.NOFOLLOW_LINKS))
                    return "Probe source or destination changed before publication"
                beforePublish()
                // If another app creates this name after the last survey,
                // CREATE_NEW/O_EXCL refuses atomically instead of truncating
                // or replacing its bytes. The new inode has distinct ownership
                // from the still-pinned source probe descriptor.
                val destination = openExclusive(renamedPath) {}
                published = destination
                ownsRenamed = true
                destination.writeAt(SPARSE_OFFSET, tail)
                destination.writeAt(0, head)
                destination.sync()
                if (!isStillPublished(renamedPath) || !isStillOwned(probePath))
                    return "Probe source or destination ownership changed"
                // Verify a separately opened read descriptor, not merely the
                // in-memory bytes or an acknowledgement from writeAt().
                FileChannel.open(renamedPath, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS).use { channel ->
                    if (channel.size() != SPARSE_OFFSET + BLOCK)
                        return "Published probe size could not be verified"
                    fun readAt(offset: Long): ByteArray {
                        val buffer = ByteBuffer.allocate(BLOCK)
                        channel.position(offset)
                        while (buffer.hasRemaining()) {
                            if (channel.read(buffer) <= 0)
                                throw java.io.IOException("Published readback incomplete")
                        }
                        return buffer.array()
                    }
                    if (!readAt(0).contentEquals(head) ||
                        !readAt(SPARSE_OFFSET).contentEquals(tail))
                        return "Published probe bytes failed readback"
                }
                if (!isStillPublished(renamedPath) || !isStillOwned(probePath))
                    return "Probe ownership changed before cleanup"
                Files.delete(probePath)
                ownsProbe = false
            } catch (_: Exception) {
                return "Folder does not allow exclusive probe publication"
            }
            if (!isStillPublished(renamedPath))
                return "Published probe ownership cannot be verified"
            try {
                if (!isStillPublished(renamedPath))
                    return "Published probe identity changed before cleanup"
                Files.delete(renamedPath)
                ownsRenamed = false
            } catch (_: Exception) {
                return "Folder does not allow deleting Chunks' owned probe"
            }
        } finally {
            // A failed CREATE_NEW does not grant ownership; importantly,
            // never delete an existing name that the probe failed to create.
            if (ownsProbe && isStillOwned(probePath))
                try { Files.deleteIfExists(probePath) } catch (_: Exception) {}
            if (ownsRenamed && isStillPublished(renamedPath))
                try { Files.deleteIfExists(renamedPath) } catch (_: Exception) {}
            try { published?.close() } catch (_: Exception) {}
            try { created?.close() } catch (_: Exception) {}
        }
        return if (bothNamesDefinitelyAbsent(probePath, renamedPath)) null
            else "Probe cleanup could not be independently verified"
    }

    /** JVM fixtures supply a synthetic implementation; Android uses fd identity. */
    internal interface ExclusiveProbeFile : Closeable {
        fun writeAt(position: Long, bytes: ByteArray)
        fun sync()
        fun owns(path: Path): Boolean
    }

    private fun openAndroidExclusive(path: Path, afterCreate: () -> Unit): ExclusiveProbeFile {
        val descriptor = Os.open(path.toString(), OsConstants.O_CREAT or
            OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_RDWR,
            OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        try {
            afterCreate()
            val stat = Os.fstat(descriptor)
            if (!OsConstants.S_ISREG(stat.st_mode))
                throw java.io.IOException("Created probe descriptor is not a regular file")
            return AndroidExclusiveProbeFile(descriptor, stat.st_dev, stat.st_ino)
        } catch (failure: Throwable) {
            try { Os.close(descriptor) } catch (_: Exception) {}
            throw failure
        }
    }

    private class AndroidExclusiveProbeFile(
        private val descriptor: FileDescriptor,
        private val device: Long,
        private val inode: Long
    ) : ExclusiveProbeFile {
        override fun writeAt(position: Long, bytes: ByteArray) {
            var written = 0
            while (written < bytes.size) {
                val count = Os.pwrite(descriptor, bytes, written,
                    bytes.size - written, position + written)
                if (count <= 0) throw java.io.IOException("Probe write did not progress")
                written += count
            }
        }
        override fun sync() = Os.fsync(descriptor)
        override fun owns(path: Path): Boolean {
            val stat = Os.lstat(path.toString())
            return OsConstants.S_ISREG(stat.st_mode) &&
                stat.st_dev == device && stat.st_ino == inode
        }
        override fun close() = Os.close(descriptor)
    }
}
