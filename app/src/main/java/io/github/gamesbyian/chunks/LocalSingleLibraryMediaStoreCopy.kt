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
    internal val supportedName =
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
        expectedNativeReviewToken: String? = null,
        cancelled: () -> Boolean = { false }
    ): Result {
        if (cancelled()) return blocked("Cancelled before destination creation.")
        val transfer = try { engine.status(infoHash) } catch (_: Exception) { null }
            ?: return blocked("This transfer is no longer available.")
        val token = transfer.nativeReviewToken
        if (!transfer.infoHash.equals(infoHash, ignoreCase = true) || token.isBlank() ||
            (expectedNativeReviewToken != null && token != expectedNativeReviewToken))
            return blocked("The reviewed transfer changed. No destination was created.")
        if (transfer.error != null || !transfer.metadataKnown || transfer.progressPpm != 1_000_000 ||
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
        if (refreshed.infoHash != transfer.infoHash || refreshed.nativeReviewToken != token ||
            !refreshed.metadataKnown || refreshed.error != null || refreshed.progressPpm != 1_000_000 ||
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
        val latest = runCatching { engine.status(infoHash) }.getOrNull()
        val latestSource = latest?.takeIf {
            it.infoHash == transfer.infoHash && it.nativeReviewToken == token &&
                it.metadataKnown && it.error == null && it.progressPpm == 1_000_000 &&
                it.state in setOf(LocalTransferState.FINISHED, LocalTransferState.SEEDING)
        }?.let { runCatching { LocalLibraryCopySource.of(engine, it) }.getOrNull() }
        if (latestSource == null || latestSource.sourceRoot != source.sourceRoot ||
            latestSource.files != source.files)
            return blocked("The reviewed source changed before destination creation.")
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

    /**
     * Open only a regular read-only source without waiting on a substituted
     * FIFO. The earlier pathname survey is point-in-time; an attacker can
     * swap a regular file for a FIFO between the survey and this open.
     * O_NOFOLLOW rejects a leaf symlink, O_NONBLOCK prevents FIFO-open hangs,
     * and fstat refuses every non-regular descriptor before any reads.
     *
     * Caller must close the returned pinned descriptor and independently
     * verify expected source inode, size and digest. This does not confer
     * provider write/delete authority or solve arbitrary parent-path races.
     */
    internal fun openPinnedRegularSource(file: File): java.io.FileDescriptor {
        val flags = android.system.OsConstants.O_RDONLY or
            android.system.OsConstants.O_CLOEXEC or
            android.system.OsConstants.O_NOFOLLOW or
            android.system.OsConstants.O_NONBLOCK
        val fd = android.system.Os.open(file.absolutePath, flags, 0)
        try {
            if (!android.system.OsConstants.S_ISREG(android.system.Os.fstat(fd).st_mode))
                throw IOException("Library source is not a regular file")
            return fd
        } catch (failure: Exception) {
            android.system.Os.close(fd)
            throw failure
        }
    }

    internal fun readAndRecheck(
        sourceRoot: File,
        file: File,
        expectedLength: Long,
        cancelled: () -> Boolean
    ): ByteArray {
        if (expectedLength !in 1..MAX_BYTES.toLong()) throw IOException("Unsafe source length")
        return LocalPinnedPayloadRead.read(sourceRoot, file, expectedLength,
            captureLimit = expectedLength.toInt(), cancelled = cancelled).captured
    }
}

/**
 * Shared read-only source boundary for library copies and independent checksums.
 * Two complete passes over the SAME pinned descriptor must agree; a stable
 * filename or an engine completion flag alone does not prove stable bytes.
 * This is bounded observation, not protection against every external writer
 * or permission to delete, publish or reattach files.
 */
internal object LocalPinnedPayloadRead {
    const val MAX_BYTES = 64L * 1024 * 1024 * 1024
    const val MAX_CAPTURE = LocalSingleLibraryMediaStoreCopy.MAX_BYTES

    data class Result(val sha256: String, val captured: ByteArray,
        val legacy: Map<LocalChecksumComparison.Algorithm, String> = emptyMap())

    fun read(
        sourceRoot: File,
        file: File,
        expectedLength: Long,
        captureLimit: Int,
        cancelled: () -> Boolean,
        includeLegacy: Boolean = false,
        onProgress: (Long) -> Unit = {}
    ): Result {
        if (expectedLength !in 0..MAX_BYTES || captureLimit !in 0..MAX_CAPTURE)
            throw IOException("Unsafe source length or capture limit")
        val root = sourceRoot.toPath()
        val path = file.toPath()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
            !path.toRealPath().startsWith(root.toRealPath()))
            throw IOException("Untrusted source path")

        val fd = LocalSingleLibraryMediaStoreCopy.openPinnedRegularSource(file)
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
            val captureBytes = minOf(captureLimit.toLong(), expectedLength).toInt()
            val output = ByteArrayOutputStream(captureBytes)
            val digestFirst = MessageDigest.getInstance("SHA-256")
            val digestSecond = MessageDigest.getInstance("SHA-256")
            // Legacy results are opt-in and derive from the first pass, whose
            // exact bytes are independently checked by the second SHA-256 pass.
            val sha1 = if (includeLegacy) MessageDigest.getInstance("SHA-1") else null
            val md5 = if (includeLegacy) MessageDigest.getInstance("MD5") else null
            val crc = if (includeLegacy) java.util.zip.CRC32() else null
            fun pass(hash: MessageDigest, capture: Boolean, base: Long) {
                var offset = 0L
                var lastProgress = 0L
                while (offset < expectedLength) {
                    if (cancelled()) throw IOException("Read cancelled")
                    val want = minOf(buffer.size.toLong(), expectedLength - offset).toInt()
                    val count = android.system.Os.pread(fd, buffer, 0, want, offset)
                    if (count <= 0 || count > want) throw IOException("Source stopped making progress")
                    hash.update(buffer, 0, count)
                    if (capture) {
                        sha1?.update(buffer, 0, count)
                        md5?.update(buffer, 0, count)
                        crc?.update(buffer, 0, count)
                    }
                    if (capture && output.size() < captureBytes)
                        output.write(buffer, 0, minOf(count, captureBytes - output.size()))
                    offset += count
                    if (offset - lastProgress >= 4L * 1024 * 1024 || offset == expectedLength) {
                        lastProgress = offset
                        onProgress(base + offset)
                    }
                }
                if (android.system.Os.pread(fd, buffer, 0, 1, expectedLength) != 0)
                    throw IOException("Source grew during read")
            }
            pass(digestFirst, true, 0L)
            pass(digestSecond, false, expectedLength)
            val first = digestFirst.digest()
            val namedAfter = android.system.Os.lstat(file.absolutePath)
            val after = android.system.Os.fstat(fd)
            if (!sameObject(namedAfter) || !sameObject(after) ||
                !MessageDigest.isEqual(first, digestSecond.digest()))
                throw IOException("Source was replaced or changed during read")
            fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val legacy = if (includeLegacy) mapOf(
                LocalChecksumComparison.Algorithm.SHA1 to hex(requireNotNull(sha1).digest()),
                LocalChecksumComparison.Algorithm.MD5 to hex(requireNotNull(md5).digest()),
                LocalChecksumComparison.Algorithm.CRC32 to requireNotNull(crc).value.toString(16).padStart(8, '0')
            ) else emptyMap()
            return Result(hex(first), output.toByteArray(), legacy)
        } finally {
            android.system.Os.close(fd)
        }
    }
}
