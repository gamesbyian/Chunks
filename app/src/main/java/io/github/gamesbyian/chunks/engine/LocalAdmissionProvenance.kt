package io.github.gamesbyian.chunks.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Persisted admission *type*, not filesystem ownership. A resumed native
 * torrent doesn't expose whether its original Add went through the reviewed
 * checked-download path or an explicit existing-data seeding workflow.
 *
 * A missing, malformed, swapped or conflicting receipt is UNKNOWN and must
 * never be silently upgraded to the trusted existing-data path on restore.
 * Actual backing files still require writer-level descriptor ownership (#568).
 */
internal object LocalAdmissionProvenance {
    enum class Mode(val token: String) {
        CHECKED("checked-download"),
        REVIEWED_EXISTING("reviewed-existing")
    }

    private val identity = Regex("^[a-f0-9]{40}([a-f0-9]{24})?$")

    private fun receipt(directory: File, hash: String): File =
        File(directory, "$hash.admission")

    private fun bytes(hash: String, mode: Mode): ByteArray =
        "chunks-native-admission-v1\n$hash\n${mode.token}\n".toByteArray(Charsets.UTF_8)

    /**
     * A no-follow open pins the receipt to a descriptor throughout its read.
     * Separate isRegularFile/readAllBytes path operations could inspect one
     * inode and then follow a newly substituted symlink to another.
     *
     * This is admission provenance only, not native payload inode ownership.
     */
    fun read(directory: File?, hash: String): Mode? {
        if (directory == null || !identity.matches(hash) ||
            !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val file = receipt(directory, hash).toPath()
        return try {
            FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val size = channel.size()
                if (size <= 0 || size > 160) return null
                val result = ByteArray(size.toInt())
                val buffer = ByteBuffer.wrap(result)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) <= 0) return null
                }
                Mode.entries.singleOrNull { result.contentEquals(bytes(hash, it)) }
            }
        } catch (_: Exception) { null }
    }

    /** A receipt is accepted only after its containing directory can be synced. */
    private fun syncDirectory(parent: java.nio.file.Path): Boolean = try {
        FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Create and sync a uniquely named private receipt first, then publish it
     * with an atomic, exclusive hard link. ATOMIC_MOVE alone can replace a
     * concurrently published destination on POSIX, even without REPLACE_EXISTING.
     *
     * The link claim is never overwritten: a competing mode wins or loses
     * atomically, and the loser re-reads the winner without modifying it.
     * Sync the parent after publication so successful admission is not based
     * on a merely cached directory entry. No filesystem support = fail closed.
     */
    fun persist(directory: File?, hash: String, mode: Mode): Boolean {
        if (directory == null) return true // No native session restoration.
        if (!identity.matches(hash)) return false
        val parent = directory.toPath()
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return false
        val destination = receipt(directory, hash).toPath()
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) ||
            !Files.notExists(destination, LinkOption.NOFOLLOW_LINKS))
            return read(directory, hash) == mode && syncDirectory(parent)

        val temporary = File(directory, "$hash.admission.${UUID.randomUUID()}.pending").toPath()
        var created = false
        try {
            FileChannel.open(temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS).use { channel ->
                created = true
                val buffer = ByteBuffer.wrap(bytes(hash, mode))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.createLink(destination, temporary)
            } catch (_: FileAlreadyExistsException) {
                return read(directory, hash) == mode
            }
            if (!syncDirectory(parent)) return false
            return read(directory, hash) == mode
        } catch (_: Exception) {
            return false
        } finally {
            if (created) {
                try { Files.deleteIfExists(temporary) }
                catch (_: Exception) { /* stale private staging is not admission */ }
            }
        }
    }

    /** Only call after native Remove has been accepted and its resume removed. */
    fun retire(directory: File?, hash: String) {
        if (directory == null || !identity.matches(hash)) return
        try { Files.deleteIfExists(receipt(directory, hash).toPath()) }
        catch (_: Exception) { /* stale receipt is fail-closed, never ownership */ }
    }
}
