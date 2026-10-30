package io.github.gamesbyian.chunks.engine

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
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
     * Called before native Add. CREATE_NEW is an atomic exclusion boundary
     * for the target *name*: two engines cannot race separate shared temporary
     * files and overwrite one another's existing admission evidence. A reader
     * observing a partially written record returns UNKNOWN, never an adoption
     * grant. The channel must be synced before native Add can proceed.
     *
     * Unlike ATOMIC_MOVE, CREATE_NEW never replaces an already-existing target
     * whose admission mode differs. There is deliberately no fallback to a
     * truncating open or a replacement rename. A crash midway through writing
     * leaves an incomplete receipt that blocks restoration until re-review.
     */
    fun persist(directory: File?, hash: String, mode: Mode): Boolean {
        if (directory == null) return true // No native session restoration.
        if (!identity.matches(hash) || !directory.isDirectory) return false
        val path = receipt(directory, hash).toPath()
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
                !Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
                return read(directory, hash) == mode
            FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE).use { channel ->
                val payload = ByteBuffer.wrap(bytes(hash, mode))
                while (payload.hasRemaining()) {
                    if (channel.write(payload) <= 0)
                        return false
                }
                channel.force(true)
            }
            return read(directory, hash) == mode
        } catch (_: Exception) {
            // A conflicting creator, interrupted record or I/O error is
            // uncertainty, not permission to overwrite/adopt the prior mode.
            return false
        }
    }

    /** Only call after native Remove has been accepted and its resume removed. */
    fun retire(directory: File?, hash: String) {
        if (directory == null || !identity.matches(hash)) return
        try { Files.deleteIfExists(receipt(directory, hash).toPath()) }
        catch (_: Exception) { /* stale receipt is fail-closed, never ownership */ }
    }
}
