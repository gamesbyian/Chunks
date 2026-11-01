package io.github.gamesbyian.chunks

import android.content.Context
import android.net.Uri
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.storage.OwnedMediaStoreDocumentPublication
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * The same qualified *fresh owned MediaStore row* adapter used by standalone ZIP
 * publication, now reading ONE verified completed wanted local torrent file.
 *
 * No generic SAF, Replace, path deletion, archive modification, or relocation.
 * This does not assume that native complete == on-disk cache flushed: require
 * the production exact-hash flush and recheck before independently reading.
 */
internal object LocalSingleLibraryMediaStoreCopy {
    const val MAX_BYTES = 8 * 1024 * 1024
    private val supportedName =
        Regex("[A-Za-z0-9][A-Za-z0-9._() -]{0,155}\\.(json|chunksnap|torrent|txt|bin)")

    enum class State { COPIED, BLOCKED, NEEDS_ATTENTION }
    data class Result(
        val state: State,
        val message: String,
        val publishedName: String? = null,
        val publishedUri: Uri? = null
    )

    private fun blocked(reason: String) = Result(State.BLOCKED, reason)

    fun copyNew(
        context: Context,
        engine: LocalTorrentEngine,
        infoHash: String,
        cancelled: () -> Boolean = { false }
    ): Result {
        if (cancelled()) return blocked("Cancelled before destination creation.")
        val transfer = try { engine.status(infoHash) } catch (_: Exception) { null }
            ?: return blocked("This transfer is no longer available.")
        if (!transfer.metadataKnown || transfer.progressPpm != 1_000_000 ||
            transfer.state !in setOf(LocalTransferState.FINISHED, LocalTransferState.SEEDING))
            return blocked("The transfer's finished bytes are not ready for independent copying.")
        val source = try { LocalLibraryCopySource.of(engine, transfer) } catch (_: Exception) { null }
            ?: return blocked("The complete wanted files could not be enumerated.")
        val file = source.files.singleOrNull()
            ?: return blocked("Multi-file library copies remain unavailable in this adapter.")
        val relative = file.outputPath
        if (!supportedName.matches(relative) ||
            file.sizeBytes !in 1L..MAX_BYTES.toLong())
            return blocked("Only one flat, completed .txt, .json, .bin, .torrent or .chunksnap file up to 8 MiB is supported.")
        if (cancelled()) return blocked("Cancelled before reading completed bytes.")

        // A seeding/completed libtorrent snapshot may precede a durable on-disk
        // cache flush; do not trust a completed UI status as source-read proof.
        if (try { !engine.flushCompletedForLibraryRead(infoHash, 30_000L) }
            catch (_: Exception) { true })
            return blocked("The native cache could not be confirmed flushed. No destination was created.")

        val refreshed = try { engine.status(infoHash) } catch (_: Exception) { null }
            ?: return blocked("The transfer disappeared before copying.")
        if (refreshed.infoHash != transfer.infoHash || refreshed.progressPpm != 1_000_000 ||
            refreshed.state !in setOf(LocalTransferState.FINISHED, LocalTransferState.SEEDING))
            return blocked("The transfer changed before the copy.")
        val checked = try { LocalLibraryCopySource.of(engine, refreshed) } catch (_: Exception) { null }
        if (checked == null || checked.files.size != 1 ||
            checked.files.single().outputPath != relative ||
            checked.files.single().sizeBytes != file.sizeBytes ||
            checked.files.single().source != file.source)
            return blocked("The source inventory changed before the copy.")

        val payload = try { readAndRecheck(source.sourceRoot, file.source, file.sizeBytes, cancelled) }
            catch (_: Exception) { return blocked("The original file changed or could not be read safely. No destination was created.") }
        if (cancelled()) return blocked("Cancelled before destination creation.")
        val suffix = relative.substringAfterLast('.')
        val mime = when (suffix) {
            "txt" -> "text/plain"
            "json" -> "application/json"
            "torrent" -> "application/x-bittorrent"
            else -> "application/octet-stream"
        }
        return try {
            val receipt = OwnedMediaStoreDocumentPublication.publish(
                context, relative, mime, payload, cancelled)
            Result(State.COPIED, "A distinct verified Downloads copy was created; the original file remains available for seeding.",
                receipt.publishedName, receipt.uri)
        } catch (_: Exception) {
            // MediaStore may have inserted a pending row. The journal retains
            // exact identity for read-only reconciliation; never delete or retry.
            Result(State.NEEDS_ATTENTION,
                "Copy publication is uncertain. Review Downloads/Chunks Exports and the saved operation record. The original was not removed.")
        }
    }

    private fun readAndRecheck(
        sourceRoot: File,
        file: File,
        expectedLength: Long,
        cancelled: () -> Boolean
    ): ByteArray {
        if (expectedLength !in 1..MAX_BYTES.toLong()) throw IOException("Unsafe source length")
        val root = sourceRoot.toPath()
        val path = file.toPath()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
            !path.toRealPath().startsWith(root.toRealPath()))
            throw IOException("Untrusted library source path")

        // Pin ONE original file descriptor through both independent reads.
        // Reopening the path for a second pass could read a substituted file
        // whose name was the same but whose inode was unrelated.
        val fd = android.system.Os.open(file.absolutePath,
            android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_CLOEXEC or
                android.system.OsConstants.O_NOFOLLOW, 0)
        try {
            val before = android.system.Os.fstat(fd)
            val namedBefore = android.system.Os.lstat(file.absolutePath)
            fun sameObject(stat: android.system.StructStat): Boolean =
                stat.st_dev == before.st_dev && stat.st_ino == before.st_ino &&
                    android.system.OsConstants.S_ISREG(stat.st_mode) &&
                    stat.st_size == expectedLength
            if (!sameObject(before) || !sameObject(namedBefore))
                throw IOException("Source file was substituted or did not have expected size")
            val buffer = ByteArray(64 * 1024)
            val output = ByteArrayOutputStream(expectedLength.toInt())
            val digestFirst = MessageDigest.getInstance("SHA-256")
            val digestSecond = MessageDigest.getInstance("SHA-256")
            fun pass(hash: MessageDigest, saveBytes: Boolean) {
                var offset = 0L
                while (offset < expectedLength) {
                    if (cancelled()) throw IOException("Copy cancelled")
                    val want = minOf(buffer.size.toLong(), expectedLength - offset).toInt()
                    val count = android.system.Os.pread(fd, buffer, 0, want, offset)
                    if (count <= 0) throw IOException("Source stopped making progress")
                    hash.update(buffer, 0, count)
                    if (saveBytes) output.write(buffer, 0, count)
                    offset += count
                }
                if (android.system.Os.pread(fd, buffer, 0, 1, expectedLength) != 0)
                    throw IOException("Source grew during copy")
            }
            pass(digestFirst, true)
            pass(digestSecond, false)
            val namedAfter = android.system.Os.lstat(file.absolutePath)
            val after = android.system.Os.fstat(fd)
            if (!sameObject(namedAfter) || !sameObject(after) ||
                !MessageDigest.isEqual(digestFirst.digest(), digestSecond.digest()))
                throw IOException("Source was replaced or changed during copy")
            return output.toByteArray()
        } finally {
            android.system.Os.close(fd)
        }
    }
}
