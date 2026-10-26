package io.github.gamesbyian.chunks.model

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** One finished payload file to copy; [outputPath] is relative to the new folder. */
data class LibraryCopyFile(val source: File, val outputPath: String, val sizeBytes: Long)

data class LibraryCopyRequest(
    /** Every source must be a regular file inside this directory; nothing outside is read. */
    val sourceRoot: File,
    val files: List<LibraryCopyFile>,
    /** Human label (the transfer's name); only proposes the new folder's name. */
    val label: String,
    /** Opaque, nonsecret identifier the caller can later resolve back to the target. */
    val targetId: String,
    /** True only after the user saw that destination free space is unknown. */
    val allowUnknownFreeSpace: Boolean = false
)

/**
 * Copies a finished transfer's files into a **new** folder inside a destination
 * the user chose, leaving the originals untouched so seeding continues.
 *
 * Uses the extractor's protocol and journal format: write-ahead journal, a
 * randomly named partial folder, one non-replacing publish rename, rollback of
 * only journaled entries, and [ZipExtractor.reconcile] after process death.
 * Each source is hashed while copying and re-hashed afterwards; a source that
 * changed during the copy fails the operation instead of publishing a mismatch.
 * Both reads explicitly reject a last-component symlink swapped after preflight.
 * This does not replace full openat-style parent path/ownership fencing.
 */
object LibraryCopier {
    const val MAX_FILES = 10_000
    private const val MAX_SEGMENT_UTF8_BYTES = 255

    fun <N : Any> copy(
        request: LibraryCopyRequest,
        target: ZipExtractionTarget<N>,
        journal: ZipExtractionJournal,
        cancelled: () -> Boolean = { false },
        operationId: String = UUID.randomUUID().toString().replace("-", "")
    ): ZipExtractionResult {
        require(operationId.matches(Regex("^[a-f0-9]{8,64}$"))) { "Invalid operation id" }
        // Unsigned-looking provider lengths can overflow a signed Long in
        // aggregate, even when each individual file length is nonnegative.
        // Never create a destination folder with a wrapped space forecast.
        val expected = LibraryCopyByteTotals.checked(request.files)
            ?: return blocked("Total file size is invalid or exceeds the supported size")
        validate(request)?.let { return blocked(it) }

        val free = try { target.availableBytes() } catch (_: Exception) { null }
        if (free == null && !request.allowUnknownFreeSpace) return blocked("Destination free space is unknown")
        if (free != null) {
            val reserve = maxOf(16L * 1024 * 1024, expected / 20)
            if (free < 0 || expected > free - minOf(free, reserve))
                return blocked("Not enough free space at the destination for " + expected + " bytes plus a safety reserve")
        }
        if (cancelled()) return ZipExtractionResult(ZipExtractionStatus.CANCELLED, null, null, 0, 0, "Cancelled before writing")

        val partialName = ZipExtractor.PARTIAL_PREFIX + operationId
        val baseName = ZipExtractor.folderBaseName(request.label)
        if (target.child(target.root, partialName) != null)
            return blocked("A partial folder for this operation already exists")

        journal.append(listOf("BEGIN", operationId, request.targetId, partialName, baseName))
        val partial = (try { target.createDirectory(target.root, partialName) } catch (_: Exception) { null })
            ?: return ZipExtractor.finish(journal, target, partialName, operationId,
                ZipExtractionStatus.FAILED_ROLLED_BACK, "Could not create a new folder at the destination", 0, 0)

        var files = 0
        var bytes = 0L
        val failure: Pair<ZipExtractionStatus, String>? = try {
            writeFiles(request, target, partial, journal, cancelled) { f, b -> files = f; bytes = b }
        } catch (_: IOException) {
            ZipExtractionStatus.FAILED_ROLLED_BACK to "Copying to the destination failed"
        } catch (_: SecurityException) {
            ZipExtractionStatus.FAILED_ROLLED_BACK to "Destination access was revoked"
        } catch (_: RuntimeException) {
            ZipExtractionStatus.FAILED_ROLLED_BACK to "The destination provider failed unexpectedly"
        }
        if (failure != null)
            return ZipExtractor.finish(journal, target, partialName, operationId, failure.first, failure.second, files, bytes)
        if (files != request.files.size || bytes != expected)
            return ZipExtractor.finish(journal, target, partialName, operationId, ZipExtractionStatus.FAILED_ROLLED_BACK,
                "Copied output differs from the finished files", files, bytes)
        return ZipExtractor.publish(target, journal, partial, partialName, baseName, operationId, files, bytes, cancelled)
    }

    /** Refuses anything that could read outside the transfer or collide inside the new folder. */
    private fun validate(request: LibraryCopyRequest): String? {
        if (request.files.isEmpty()) return "There are no finished files to copy"
        if (request.files.size > MAX_FILES) return "Too many files to copy at once"
        val root = try { request.sourceRoot.toPath().toRealPath() } catch (_: IOException) {
            return "The downloaded files could not be found"
        }
        val seen = HashSet<String>()
        for (file in request.files) {
            val parts = file.outputPath.split("/")
            if (parts.any { it.isEmpty() || it == "." || it == ".." || it.contains('\\') || it.any(Char::isISOControl) })
                return "A file has an unsafe name"
            if (parts.any { it.toByteArray(Charsets.UTF_8).size > MAX_SEGMENT_UTF8_BYTES })
                return "A file name is too long for Android storage"
            if (!seen.add(file.outputPath.lowercase(Locale.ROOT))) return "Two files would have the same name"
            if (file.sizeBytes < 0) return "A file has an invalid size"
            val path = file.source.toPath()
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return "A downloaded file is missing or not a plain file"
            val real = try { path.toRealPath() } catch (_: IOException) {
                return "A downloaded file could not be read"
            }
            if (!real.startsWith(root)) return "A file lies outside the transfer's folder"
            if (Files.size(real) != file.sizeBytes) return "A file is not finished downloading"
        }
        // A file and a folder must not claim the same name ("a" and "a/b").
        val folders = request.files.flatMap { f ->
            val parts = f.outputPath.split("/")
            (1 until parts.size).map { parts.take(it).joinToString("/").lowercase(Locale.ROOT) }
        }.toSet()
        if (request.files.any { it.outputPath.lowercase(Locale.ROOT) in folders }) return "A file and a folder would have the same name"
        return null
    }

    private fun <N : Any> writeFiles(
        request: LibraryCopyRequest,
        target: ZipExtractionTarget<N>,
        partial: N,
        journal: ZipExtractionJournal,
        cancelled: () -> Boolean,
        progress: (Int, Long) -> Unit
    ): Pair<ZipExtractionStatus, String>? {
        val directories = HashMap<String, N>()
        fun directory(path: List<String>): N? {
            var key = ""
            var node = partial
            for (segment in path) {
                key = if (key.isEmpty()) segment else "$key/$segment"
                val known = directories[key]
                if (known != null) { node = known; continue }
                journal.append(listOf("DIR", key))
                node = target.createDirectory(node, segment) ?: return null
                directories[key] = node
            }
            return node
        }

        var files = 0
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        for (file in request.files) {
            if (cancelled()) return ZipExtractionStatus.CANCELLED to "Copy cancelled"
            val parts = file.outputPath.split("/")
            val parent = directory(parts.dropLast(1))
                ?: return ZipExtractionStatus.FAILED_ROLLED_BACK to "Could not create a folder at the destination"
            journal.append(listOf("FILE", file.outputPath))
            val node = target.createFile(parent, parts.last())
                ?: return ZipExtractionStatus.FAILED_ROLLED_BACK to "Destination refused or renamed a new file"
            files++
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            target.openNewOutput(node).use { output ->
                Files.newInputStream(file.source.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
                    while (true) {
                        if (cancelled() || Thread.currentThread().isInterrupted)
                            return ZipExtractionStatus.CANCELLED to "Copy cancelled"
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > file.sizeBytes)
                            return ZipExtractionStatus.FAILED_ROLLED_BACK to "A file grew while it was being copied"
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
                output.flush()
            }
            if (copied != file.sizeBytes)
                return ZipExtractionStatus.FAILED_ROLLED_BACK to "A file changed size while it was being copied"
            val expectedDigest = digest.digest()
            if (!expectedDigest.contentEquals(sha256(file.source, buffer)))
                return ZipExtractionStatus.FAILED_ROLLED_BACK to "A file changed while it was being copied"
            // A provider may acknowledge writes but persist fewer or different
            // bytes. Verify the actual destination, not only the write buffer.
            val storedInput = target.openExistingInput(node)
                ?: return ZipExtractionStatus.FAILED_ROLLED_BACK to
                    "Destination cannot be independently read back for verification"
            val storedDigest = MessageDigest.getInstance("SHA-256")
            var storedBytes = 0L
            storedInput.use { input ->
                while (true) {
                    if (cancelled() || Thread.currentThread().isInterrupted)
                        return ZipExtractionStatus.CANCELLED to "Copy cancelled during destination verification"
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0 || count.toLong() > copied - storedBytes)
                        return ZipExtractionStatus.FAILED_ROLLED_BACK to
                            "Destination stored more bytes than the verified source, or made no progress"
                    storedBytes += count
                    storedDigest.update(buffer, 0, count)
                }
            }
            if (storedBytes != copied || !storedDigest.digest().contentEquals(expectedDigest))
                return ZipExtractionStatus.FAILED_ROLLED_BACK to
                    "Destination bytes failed SHA-256 and size read-back verification"
            total += copied
            progress(files, total)
        }
        return null
    }

    private fun sha256(file: File, buffer: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun blocked(reason: String) = ZipExtractionResult(ZipExtractionStatus.BLOCKED, null, null, 0, 0, reason)
}
