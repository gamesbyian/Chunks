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
        val before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS)
        if (!before.isRegularFile || before.size() != expectedLength)
            throw IOException("Source is not a completed regular file")
        fun readBounded(): ByteArray {
            val output = ByteArrayOutputStream(expectedLength.toInt())
            java.nio.channels.FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = java.nio.ByteBuffer.allocate(64 * 1024)
                while (true) {
                    if (cancelled()) throw IOException("Copy cancelled")
                    buffer.clear()
                    val count = channel.read(buffer)
                    if (count < 0) break
                    if (count == 0 || count.toLong() > expectedLength - output.size())
                        throw IOException("Source changed size or stopped making progress")
                    output.write(buffer.array(), 0, count)
                }
            }
            if (output.size() != expectedLength.toInt()) throw IOException("Incomplete source")
            return output.toByteArray()
        }
        val payload = readBounded()
        val second = readBounded()
        val after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS)
        if (!after.isRegularFile || after.size() != expectedLength ||
            (before.fileKey() != null && before.fileKey() != after.fileKey()) ||
            !MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload),
                MessageDigest.getInstance("SHA-256").digest(second)))
            throw IOException("Source changed while copying")
        return payload
    }
}
