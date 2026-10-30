package io.github.gamesbyian.chunks.engine

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

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
    fun persist(directory: File?, hash: String, mode: Mode): Boolean {
        if (directory == null) return true // No native session restoration.
        if (!identity.matches(hash) || !directory.isDirectory) return false
        val target = receipt(directory, hash)
        val path = target.toPath()
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
                !Files.notExists(path, LinkOption.NOFOLLOW_LINKS))
                return read(directory, hash) == mode
            val temporary = File(directory, "$hash.admission.tmp")
            if (Files.isSymbolicLink(temporary.toPath())) return false
            FileOutputStream(temporary).use {
                it.write(bytes(hash, mode))
                it.fd.sync()
            }
            Files.move(temporary.toPath(), path, StandardCopyOption.ATOMIC_MOVE)
            return read(directory, hash) == mode
        } catch (_: Exception) {
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
