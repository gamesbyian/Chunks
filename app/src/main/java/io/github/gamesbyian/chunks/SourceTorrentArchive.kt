package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** The original is a distinct user/provider document; torrent payload is never involved. */
internal enum class OriginalTorrentHandling { KEEP, ARCHIVE, DELETE }
internal enum class OriginalTorrentOrigin { ANDROID_DOCUMENT, FETCHED_HTTPS, MAGNET }

/**
 * Only the caller which independently observed a NEW exact-environment Add
 * may furnish positive evidence. This model is not itself an RPC receipt.
 * "Already present" and lost acknowledgements must set verifiedNew=false.
 */
internal data class SourceAddVerification(
    val exactEnvironmentId: String,
    val expectedInfoHashV1: String,
    val verifiedNew: Boolean
)

internal sealed interface SourceArchiveOutcome {
    data object OriginalKept : SourceArchiveOutcome
    /** No deletion performed. The caller must separately obtain provider authority. */
    data object OriginalDeletionRequiresApproval : SourceArchiveOutcome
    /** Source original still exists. App uninstall can remove the copied bytes. */
    data class ArchivedCopy(val relativeName: String, val alreadyPresent: Boolean) : SourceArchiveOutcome
    /** A file may exist, but an interrupted directory sync prevents durability claims. */
    data object NeedsReconciliation : SourceArchiveOutcome
    data object NotEligible : SourceArchiveOutcome
    data object UnverifiedAdd : SourceArchiveOutcome
    data object IdentityMismatch : SourceArchiveOutcome
    data object CorruptOrUnsafeArchive : SourceArchiveOutcome
    data object ArchiveFull : SourceArchiveOutcome
    data object IoFailure : SourceArchiveOutcome
}

/**
 * Private, bounded archive *copy* only. Never calls ContentResolver.delete,
 * DocumentsContract.deleteDocument, any torrent engine or network client.
 *
 * Exact v1 metainfo is revalidated before staging. The file name is derived
 * only from canonical infohash and full SHA-256, never a source URI, tracker
 * passkey, provider filename or remote account. All operations are local.
 */
internal class SourceTorrentArchive(
    private val privateFilesDirectory: File,
    // Test-only, stage+exception-type diagnostic. Never passes path/URI,
    // metainfo, provider message, file bytes or exception message.
    private val diagnostic: ((String, String) -> Unit)? = null
) {
    companion object {
        const val DIRECTORY = "source-torrents"
        const val MAX_ARCHIVED_BYTES = 64L * 1024L * 1024L
        const val MAX_ARCHIVED_FILES = 256
        private val HASH_V1 = Regex("[a-f0-9]{40}")
        // All archive instances in the Android application share one physical
        // filesDir catalog. A per-instance @Synchronized lock permits two Adds
        // to pass a near-full quota concurrently; serialize the complete
        // catalog validation + no-replace publication across instances.
        // This is a process lock, not authorization for cross-process writers.
        private val PROCESS_ARCHIVE_LOCK = Any()
    }

    fun apply(
        handling: OriginalTorrentHandling,
        origin: OriginalTorrentOrigin,
        bytes: ByteArray?,
        proof: SourceAddVerification?
    ): SourceArchiveOutcome = synchronized(PROCESS_ARCHIVE_LOCK) {
        applyLocked(handling, origin, bytes, proof)
    }

    private fun applyLocked(
        handling: OriginalTorrentHandling,
        origin: OriginalTorrentOrigin,
        bytes: ByteArray?,
        proof: SourceAddVerification?
    ): SourceArchiveOutcome {
        if (handling == OriginalTorrentHandling.KEEP) return SourceArchiveOutcome.OriginalKept
        // The source's original ownership is never inferred from a fetched URL or magnet.
        if (origin != OriginalTorrentOrigin.ANDROID_DOCUMENT) return SourceArchiveOutcome.NotEligible
        if (proof == null || !proof.verifiedNew || proof.exactEnvironmentId.isBlank())
            return SourceArchiveOutcome.UnverifiedAdd
        val hash = proof.expectedInfoHashV1
        if (!HASH_V1.matches(hash) || bytes == null || bytes.isEmpty() ||
            bytes.size > TorrentMetainfoReader.MAX_BYTES) return SourceArchiveOutcome.IdentityMismatch
        val preview = try { TorrentMetainfoReader.preview(bytes) }
            catch (_: Exception) { return SourceArchiveOutcome.IdentityMismatch }
        if (preview.infoHashV1 != hash) return SourceArchiveOutcome.IdentityMismatch

        // DELETE is intentionally an *approval request*, not an implementation.
        // The original URI must be independently revalidated and SAF/provider
        // consent obtained after this result. No source or payload changes here.
        if (handling == OriginalTorrentHandling.DELETE)
            return SourceArchiveOutcome.OriginalDeletionRequiresApproval

        val root = privateFilesDirectory.toPath()
        val archive = root.resolve(DIRECTORY)
        val nofollow = LinkOption.NOFOLLOW_LINKS
        try {
            if (!Files.isDirectory(root, nofollow) || Files.isSymbolicLink(root))
                return SourceArchiveOutcome.CorruptOrUnsafeArchive
            if (!Files.exists(archive, nofollow)) Files.createDirectory(archive)
            if (!Files.isDirectory(archive, nofollow) || Files.isSymbolicLink(archive))
                return SourceArchiveOutcome.CorruptOrUnsafeArchive
        } catch (failure: Exception) {
            diagnostic?.invoke("prepare-directory", failure.javaClass.simpleName)
            return SourceArchiveOutcome.IoFailure
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        val name = hash + "-" + digest + ".torrent"
        val target = archive.resolve(name)
        // Even a previously verified copy must not bypass the catalog's
        // no-symlink, bounded-entry and unknown-content checks. Otherwise a
        // malicious/unexpected sibling silently defeats the fail-closed rule.
        // A full but otherwise valid catalog can still deduplicate an existing
        // identity, so enforce quota only AFTER this validation and readback.
        val inventory = try { inventory(archive) }
            catch (_: Exception) { return SourceArchiveOutcome.CorruptOrUnsafeArchive }
        val existing = verifyExisting(target, bytes)
        if (existing != null) {
            // The previous process could have published the link but lost
            // directory durability before returning a success receipt.
            // Re-fsync even an exact, deduplicated copy before claiming it
            // is safe to retain as the archive of record.
            if (existing is SourceArchiveOutcome.ArchivedCopy && !syncDirectory(archive))
                return SourceArchiveOutcome.NeedsReconciliation
            return existing
        }
        if (inventory.first >= MAX_ARCHIVED_FILES ||
            inventory.second > MAX_ARCHIVED_BYTES - bytes.size)
            return SourceArchiveOutcome.ArchiveFull

        var temporary: Path? = null
        var stage = "temp-create"
        try {
            val staged = Files.createTempFile(archive, ".pending-", ".tmp")
            temporary = staged
            stage = "write-fsync"
            FileChannel.open(staged, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use {
                val buf = ByteBuffer.wrap(bytes)
                while (buf.hasRemaining()) it.write(buf)
                it.force(true)
            }
            // Android app-private filesDir refuses hard-link publication on
            // API29 and API35 (observed AccessDeniedException). Keep an exact
            // no-existing-target check *inside* the shared process lock, then
            // atomically rename the fsynced staging inode in the same directory.
            // Only this application process may publish this private catalog;
            // cross-process writers are explicitly unsupported/unqualified.
            // Never fall back to a non-atomic copy or REPLACE_EXISTING.
            stage = "atomic-publish"
            val unexpected = verifyExisting(target, bytes)
            if (unexpected != null) {
                if (unexpected is SourceArchiveOutcome.ArchivedCopy &&
                    !syncDirectory(archive)) return SourceArchiveOutcome.NeedsReconciliation
                return unexpected
            }
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: FileAlreadyExistsException) {
                val concurrent = verifyExisting(target, bytes)
                    ?: return SourceArchiveOutcome.CorruptOrUnsafeArchive
                if (concurrent is SourceArchiveOutcome.ArchivedCopy &&
                    !syncDirectory(archive)) return SourceArchiveOutcome.NeedsReconciliation
                return concurrent
            }
            temporary = null // rename consumed the staging filename
            if (!syncDirectory(archive)) return SourceArchiveOutcome.NeedsReconciliation
            return verifyExisting(target, bytes, wasAlreadyPresent = false)
                ?: SourceArchiveOutcome.CorruptOrUnsafeArchive
        } catch (failure: Exception) {
            diagnostic?.invoke(stage, failure.javaClass.simpleName)
            return SourceArchiveOutcome.IoFailure
        } finally {
            // On failed writes, never delete a previously archived identity.
            // A failed temporary-file cleanup cannot authorize source deletion.
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun verifyExisting(
        path: Path, expected: ByteArray, wasAlreadyPresent: Boolean = true
    ): SourceArchiveOutcome? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            return SourceArchiveOutcome.CorruptOrUnsafeArchive
        val equal = try {
            Files.size(path) == expected.size.toLong() &&
                Files.readAllBytes(path).contentEquals(expected)
        } catch (_: Exception) { false }
        return if (equal) SourceArchiveOutcome.ArchivedCopy(path.fileName.toString(), wasAlreadyPresent)
            else SourceArchiveOutcome.CorruptOrUnsafeArchive
    }

    private fun inventory(directory: Path): Pair<Int, Long> {
        var count = 0
        var entries = 0
        var size = 0L
        Files.newDirectoryStream(directory).use { items ->
            for (item in items) {
                // A crash loop can leave .pending entries. Do not let an
                // unbounded catalog turn a 2 MiB Add into a filesystem scan.
                entries++
                require(entries <= MAX_ARCHIVED_FILES + 16) {
                    "Too many incomplete private archive entries"
                }
                val name = item.fileName.toString()
                val archived = Regex("[a-f0-9]{40}-[a-f0-9]{64}\\.torrent").matches(name)
                val temporary = Regex("\\.pending-[A-Za-z0-9_-]+\\.tmp").matches(name)
                require(archived || temporary) { "Unexpected private archive entry" }
                val attrs = Files.readAttributes(item, BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS)
                require(attrs.isRegularFile && !attrs.isSymbolicLink &&
                    attrs.size() in 0L..TorrentMetainfoReader.MAX_BYTES.toLong()) {
                    "Unsafe private archive entry"
                }
                require(size <= Long.MAX_VALUE - attrs.size()) { "Archive size overflow" }
                size += attrs.size()
                if (archived) count++
            }
        }
        return count to size
    }

    private fun syncDirectory(directory: Path): Boolean = try {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        true
    } catch (_: Exception) { false }
}
