package io.github.gamesbyian.chunks.engine

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

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

    /** Null is intentionally UNKNOWN, never an implicit raw/adopt grant. */
    fun read(directory: File?, hash: String): Mode? {
        if (directory == null || !identity.matches(hash)) return null
        val file = receipt(directory, hash)
        return try {
            // Read through the descriptor opened with NOFOLLOW. A separate
            // pathname survey followed by readAllBytes could follow a swapped
            // symlink between the two calls.
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) null
            else FileChannel.open(file.toPath(), StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS).use { channel ->
                val length = channel.size()
                if (length <= 0 || length > 160) null
                else {
                    val buffer = ByteBuffer.allocate(length.toInt())
                    while (buffer.hasRemaining()) {
                        if (channel.read(buffer) < 0) return@use null
                    }
                    val actual = buffer.array()
                    Mode.entries.singleOrNull { actual.contentEquals(bytes(hash, it)) }
                }
            }
        } catch (_: Exception) { null }
    }

    /**
     * Called before native Add. The app-private record is synced and atomically
     * installed before a restorable native job can exist. A conflicting prior
     * mode refuses rather than rewriting evidence of an earlier admission.
     * A crash that loses this record causes conservative UNKNOWN restoration.
     */
    /**
     * Android's private app storage does not guarantee hardlink creation (both
     * API29 and API35 denied Files.createLink in the receipt diagnostic).
     * A final-path CREATE_NEW is the same no-replace primitive without a link.
     * Keep the initially opened descriptor through write and force: never
     * close and reopen a temporary pathname that can be substituted.
     *
     * If the process dies after exclusive creation but before the final fsync,
     * a partial receipt stays UNKNOWN. This deliberately trades recoverability
     * of a failed Add for preserving other files; it does NOT grant ownership.
     */
    private fun forceDirectory(root: Path) {
        if (Build.VERSION.SDK_INT > 0) {
            // Android's NIO directory FileChannel.force is not portable.
            // Use a real directory descriptor and a checked kernel fsync.
            val flags = OsConstants.O_RDONLY or OsConstants.O_DIRECTORY or
                OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC
            val fd = Os.open(root.toString(), flags, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        } else {
            FileChannel.open(root, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    /** Fsync the already-published exact receipt before accepting a retry. */
    private fun forceExistingFile(path: Path) {
        if (Build.VERSION.SDK_INT > 0) {
            val fd = Os.open(path.toString(), OsConstants.O_RDONLY or
                OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        } else {
            FileChannel.open(path, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        }
    }

    fun persist(
        directory: File?, hash: String, mode: Mode,
        syncDirectory: (Path) -> Unit = ::forceDirectory,
        // Controlled deterministic race seam; production leaves it empty.
        afterExclusiveCreate: (Path) -> Unit = {}
    ): Boolean {
        if (directory == null) return true // No native session restoration.
        if (!identity.matches(hash) || !directory.isDirectory) return false
        val path = receipt(directory, hash).toPath()
        val root = directory.toPath()
        fun syncMatchingReceipt(): Boolean = try {
            if (read(directory, hash) != mode) false
            else {
                // A simultaneous same-mode creator may have written the bytes
                // but not yet issued force(). Do not accept just a pathname
                // match as proof that its data survived a crash.
                forceExistingFile(path)
                syncDirectory(root)
                read(directory, hash) == mode
            }
        } catch (_: Exception) { false }

        return try {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(root)) return false
            // CREATE_NEW and NOFOLLOW are required. A pre-existing (even
            // matching) receipt is separately fsynced; a symlink or an
            // invalid/incomplete receipt cannot be silently replaced.
            FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                afterExclusiveCreate(path)
                val contents = ByteBuffer.wrap(bytes(hash, mode))
                while (contents.hasRemaining()) channel.write(contents)
                channel.force(true)
            }
            syncDirectory(root)
            read(directory, hash) == mode
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            syncMatchingReceipt()
        } catch (_: Exception) {
            // Deliberately leave a possibly incomplete new final receipt
            // untouched. Deleting it by pathname after an unknown failure
            // could unlink another writer's replacement; a future retry
            // treats the incomplete receipt as UNKNOWN and refuses.
            false
        }
    }

    /** Only call after native Remove has been accepted and its resume removed. */
    fun retire(directory: File?, hash: String) {
        if (directory == null || !identity.matches(hash)) return
        try { Files.deleteIfExists(receipt(directory, hash).toPath()) }
        catch (_: Exception) { /* stale receipt is fail-closed, never ownership */ }
    }
}
