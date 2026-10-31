package io.github.gamesbyian.chunks.storage

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/**
 * An app-private, exclusive and fsynced forensic journal for a single owned
 * platform MediaStore Downloads publication. This is a NON-ACTIVATED storage
 * primitive until the positive Save UX and physical acceptance are approved.
 *
 * A record cannot grant authority to open a provider URI for writing or to
 * delete any document. Recovery is intentionally read-only; unknown/torn
 * records and any collision return NEEDS_ATTENTION without cleanup/retry.
 */
internal class OwnedMediaStorePublicationJournal private constructor(
    val operationId: String,
    private val journal: ExclusiveFsyncJournal
) : Closeable {
    enum class Phase { INTENT, ROW_RECEIVED, BYTES_SYNCED, PUBLISH_REQUESTED, VERIFIED_PUBLISHED }
    enum class Verdict { NEEDS_ATTENTION, VERIFIED_PENDING, VERIFIED_PUBLISHED }

    data class Evidence(
        val operationId: String,
        val phase: Phase,
        val requestedName: String,
        val mime: String,
        val expectedSize: Long,
        val expectedSha256: String,
        val rowUri: String?
    )

    data class Assessment(val verdict: Verdict, val phase: Phase?, val operationId: String)

    companion object {
        private const val VERSION = "CHUNKS_MS1"
        private const val DIRECTORY = "owned-mediastore-publications"
        private val ID = Regex("[0-9a-f]{32}")
        private val SHA = Regex("[0-9a-f]{64}")
        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9._() -]{0,190}\\.(json|chunksnap|torrent|txt|bin)")
        private val MIME = setOf("application/json", "application/octet-stream",
            "application/x-bittorrent", "text/plain")

        /** Caller-provided context must use internal app files, never external Downloads. */
        fun journalRoot(context: Context): Path = context.filesDir.toPath().resolve(DIRECTORY)

        fun begin(context: Context, name: String, mime: String, size: Long, sha256: String):
            OwnedMediaStorePublicationJournal {
            require(NAME.matches(name) && mime in MIME && size in 1..OwnedMediaStoreDocumentPublication.MAX_BYTES.toLong() &&
                SHA.matches(sha256)) { "Unsafe publication receipt" }
            val dir = journalRoot(context)
            val created = try {
                Files.createDirectory(dir)
                true
            } catch (_: FileAlreadyExistsException) { false }
            check(Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                "Publication journal root must be an owned private directory"
            }
            if (created) {
                FileChannel.open(context.filesDir.toPath(), StandardOpenOption.READ)
                    .use { it.force(true) }
            }
            for (attempt in 1..16) {
                val id = UUID.randomUUID().toString().replace("-", "")
                val out = try { ExclusiveFsyncJournal.create(dir, id) }
                    catch (collision: FileAlreadyExistsException) { continue }
                try {
                    out.append("$VERSION\tINTENT\t$id\t$name\t$mime\t$size\t$sha256")
                    return OwnedMediaStorePublicationJournal(id, out)
                } catch (error: Exception) {
                    out.close()
                    // Preserve possibly durable intent as forensic evidence.
                    throw error
                }
            }
            throw IOException("Could not reserve unique durable publication journal")
        }

        /**
         * Reject unknown and contradictory lines. A final torn line is ignored
         * by ExclusiveFsyncJournal; older complete records are never upgraded
         * into success without an independent physical provider readback.
         */
        fun parse(operationId: String, lines: List<String>?): Evidence? {
            if (!ID.matches(operationId) || lines.isNullOrEmpty()) return null
            val first = lines.first().split('\t')
            if (first.size != 7 || first[0] != VERSION || first[1] != "INTENT" ||
                first[2] != operationId || !NAME.matches(first[3]) ||
                first[4] !in MIME || !SHA.matches(first[6])) return null
            val size = first[5].toLongOrNull()
                ?.takeIf { it in 1..OwnedMediaStoreDocumentPublication.MAX_BYTES.toLong() }
                ?: return null
            var phase = Phase.INTENT
            var rowUri: String? = null
            for (line in lines.drop(1)) {
                val parts = line.split('\t')
                if (parts.size < 2 || parts[0] != VERSION) return null
                when {
                    phase == Phase.INTENT && parts.size == 3 && parts[1] == "ROW" -> {
                        val uri = try { Uri.parse(parts[2]) } catch (_: Exception) { return null }
                        val root = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        val id = try { ContentUris.parseId(uri) } catch (_: Exception) { return null }
                        if (id <= 0 || uri != ContentUris.withAppendedId(root, id))
                            return null
                        rowUri = uri.toString()
                        phase = Phase.ROW_RECEIVED
                    }
                    phase == Phase.ROW_RECEIVED && parts.size == 2 && parts[1] == "SYNCED" ->
                        phase = Phase.BYTES_SYNCED
                    phase == Phase.BYTES_SYNCED && parts.size == 2 && parts[1] == "PUBLISH" ->
                        phase = Phase.PUBLISH_REQUESTED
                    phase == Phase.PUBLISH_REQUESTED && parts.size == 2 && parts[1] == "VERIFIED" ->
                        phase = Phase.VERIFIED_PUBLISHED
                    else -> return null
                }
            }
            return Evidence(operationId, phase, first[3], first[4], size, first[6], rowUri)
        }

        /**
         * Safe after process death. No insert/update/write/delete, no provider
         * enumeration by filename, no attempt to complete a pending operation.
         * It may report published only for exact row identity and fresh bytes,
         * and only when the durable journal had requested publication.
         */
        fun assess(context: Context, operationId: String): Assessment {
            val fail = Assessment(Verdict.NEEDS_ATTENTION, null, operationId)
            if (!ID.matches(operationId)) return fail
            val path = journalRoot(context).resolve("$operationId.log")
            val lines = try { ExclusiveFsyncJournal.readCompleteLines(path) }
                catch (_: Exception) { return fail }
            val e = parse(operationId, lines) ?: return fail
            val unsure = Assessment(Verdict.NEEDS_ATTENTION, e.phase, operationId)
            val rawUri = e.rowUri ?: return unsure
            val uri = Uri.parse(rawUri)
            val resolver = context.contentResolver
            return try {
                val cols = arrayOf(
                    MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                    MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.IS_PENDING)
                resolver.query(uri, cols, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) false else {
                        val id = ContentUris.parseId(uri)
                        val same = c.getLong(0) == id &&
                            c.getString(1) == context.packageName &&
                            c.getString(2) == e.requestedName &&
                            c.getString(3)?.trimEnd('/') ==
                                (Environment.DIRECTORY_DOWNLOADS + "/" +
                                OwnedMediaStoreDocumentPublication.EXPORT_FOLDER).trimEnd('/') &&
                            c.getString(4) == e.mime &&
                            c.getInt(5) in 0..1
                        val pending = c.getInt(5)
                        if (c.moveToNext() || !same) false
                        else if (!readback(resolver.openFileDescriptor(uri, "r"), e.expectedSize,
                                e.expectedSha256)) false
                        else return Assessment(
                            when {
                                pending == 0 && e.phase >= Phase.PUBLISH_REQUESTED ->
                                    Verdict.VERIFIED_PUBLISHED
                                pending == 1 && e.phase == Phase.BYTES_SYNCED ->
                                    Verdict.VERIFIED_PENDING
                                else -> Verdict.NEEDS_ATTENTION
                            }, e.phase, operationId)
                    }
                }
                // No clean-up even if the row disappeared, fields changed, or
                // an adversarial provider returned another object.
                unsure
            } catch (_: Exception) { unsure }
        }

        private fun readback(
            descriptor: android.os.ParcelFileDescriptor?,
            expectedSize: Long,
            sha256: String
        ): Boolean {
            if (descriptor == null) return false
            return descriptor.use { d ->
                val fd = d.fileDescriptor
                val stat = Os.fstat(fd)
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != expectedSize)
                    return@use false
                val digest = MessageDigest.getInstance("SHA-256")
                val block = ByteArray(32 * 1024)
                var read = 0L
                while (read < expectedSize) {
                    val count = Os.pread(fd, block, 0,
                        minOf(block.size.toLong(), expectedSize - read).toInt(), read)
                    if (count <= 0) return@use false
                    digest.update(block, 0, count)
                    read += count
                }
                if (Os.pread(fd, block, 0, 1, read) != 0) return@use false
                val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                actual == sha256
            }
        }
    }

    /** Exact returned row only. Its contents remain untrusted until readback. */
    fun rowReceived(uri: Uri) {
        val root = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val id = try { ContentUris.parseId(uri) } catch (_: Exception) {
            throw IOException("Refusing journal of nonnumeric URI")
        }
        if (id <= 0 || uri != ContentUris.withAppendedId(root, id))
            throw IOException("Refusing journal of non-platform Downloads URI")
        journal.append("$VERSION\tROW\t$uri")
    }
    fun bytesSynced() = journal.append("$VERSION\tSYNCED")
    fun publicationRequested() = journal.append("$VERSION\tPUBLISH")
    fun publicationVerified() = journal.append("$VERSION\tVERIFIED")
    override fun close() = journal.close()
}
