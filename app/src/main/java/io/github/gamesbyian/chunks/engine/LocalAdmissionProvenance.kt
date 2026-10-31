package io.github.gamesbyian.chunks.engine

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
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                Files.size(file.toPath()) > 160) null
            else {
                val actual = Files.readAllBytes(file.toPath())
                Mode.entries.singleOrNull { actual.contentEquals(bytes(hash, it)) }
            }
        } catch (_: Exception) { null }
    }

    /**
     * Called before native Add. The app-private record is synced and atomically
     * installed before a restorable native job can exist. A conflicting prior
     * mode refuses rather than rewriting evidence of an earlier admission.
     * A crash that loses this record causes conservative UNKNOWN restoration.
     */
    fun persist(
        directory: File?, hash: String, mode: Mode,
        syncDirectory: (Path) -> Unit = { root ->
            FileChannel.open(root, StandardOpenOption.READ).use { it.force(true) }
        }
    ): Boolean {
        if (directory == null) return true // No native session restoration.
        if (!identity.matches(hash) || !directory.isDirectory) return false
        val path = receipt(directory, hash).toPath()
        val root = directory.toPath()
        var temporary: java.nio.file.Path? = null
        fun syncMatchingReceipt(): Boolean = try {
            if (read(directory, hash) != mode) false
            else { syncDirectory(root); true }
        } catch (_: Exception) { false }
        return try {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(root)) return false
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
                !Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
                return syncMatchingReceipt()

            // Never truncate a predictable .tmp path: an interrupted prior
            // transaction may have left a hardlink to unrelated bytes there.
            // CREATE_NEW selects a fresh, exclusive file in this private root.
            val fresh = Files.createTempFile(root, "$hash.admission.", ".tmp")
            temporary = fresh
            FileChannel.open(fresh, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use {
                val contents = ByteBuffer.wrap(bytes(hash, mode))
                while (contents.hasRemaining()) it.write(contents)
                it.force(true)
            }
            // A link is an atomic NO-REPLACE publication within one filesystem:
            // unlike ATOMIC_MOVE, it can never replace a concurrently installed
            // receipt, even on Linux where atomic rename replaces by default.
            Files.createLink(path, fresh)
            // Receipt must survive a crash before native resume state is
            // permitted to claim its admission mode after process restoration.
            syncDirectory(root)
            read(directory, hash) == mode
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            // Another writer won publication; accept only the exact same
            // immutable mode. Never convert checked to reviewed-existing.
            syncMatchingReceipt()
        } catch (_: Exception) {
            false
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /** Only call after native Remove has been accepted and its resume removed. */
    fun retire(directory: File?, hash: String) {
        if (directory == null || !identity.matches(hash)) return
        try { Files.deleteIfExists(receipt(directory, hash).toPath()) }
        catch (_: Exception) { /* stale receipt is fail-closed, never ownership */ }
    }
}
