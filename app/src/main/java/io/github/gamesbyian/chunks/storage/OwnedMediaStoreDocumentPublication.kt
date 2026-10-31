package io.github.gamesbyian.chunks.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Isolated, NON-SHIPPING experiment for a POSITIVE, user-visible document
 * publication on Android's platform MediaStore Downloads collection.
 *
 * This only supports one fresh row on the primary Downloads volume. It is
 * NOT a generic SAF writer, ZIP tree extractor, native payload store, or a
 * permission to modify an arbitrary URI returned by a DocumentsProvider.
 *
 * The platform MediaStore is the trusted creation authority: insert creates
 * a new row, then the writer checks that exact row's identity, owner and
 * pending state before opening a descriptor. No path is reopened for writing,
 * no rename/replace is attempted, and failures preserve ambiguous pending
 * rows (letting the platform apply its own pending-expiration policy).
 */
internal object OwnedMediaStoreDocumentPublication {
    const val MAX_BYTES = 64 * 1024 * 1024
    const val EXPORT_FOLDER = "Chunks Exports"
    private val safeName = Regex("[A-Za-z0-9][A-Za-z0-9._() -]{0,155}\\.(json|chunksnap|torrent|txt|bin)")
    private const val CHUNK = 32 * 1024

    data class Receipt(
        /** Exact durable operation journal, for recovery without guessing filenames. */
        val operationId: String,
        val uri: Uri,
        val publishedName: String,
        val sizeBytes: Long,
        val sha256: String
    )

    private data class Row(
        val id: Long,
        val name: String,
        val owner: String?,
        val relativePath: String,
        val mime: String,
        val pending: Int,
        val size: Long
    )

    private fun shaHex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun row(resolver: ContentResolver, uri: Uri): Row {
        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.SIZE
        )
        return (resolver.query(uri, columns, null, null, null)
            ?: throw IOException("MediaStore refused the exact-row identity read")).use { cursor ->
            if (!cursor.moveToFirst())
                throw IOException("New MediaStore row was not visible to its owner")
            fun str(index: Int) = cursor.getString(index)
            val result = Row(cursor.getLong(0), str(1) ?: "",
                str(2), str(3) ?: "", str(4) ?: "",
                cursor.getInt(5), cursor.getLong(6))
            if (cursor.moveToNext()) throw IOException("MediaStore returned multiple rows for one URI")
            result
        }
    }

    private fun requireRow(
        resolver: ContentResolver,
        uri: Uri,
        expectedId: Long,
        expectedName: String,
        expectedOwner: String,
        expectedPath: String,
        expectedMime: String,
        pending: Int,
        expectedSize: Long? = null
    ) {
        val actual = row(resolver, uri)
        // Report only *which* independent assertion failed, never private
        // filenames, exact URI, filesystem path or personally identifying
        // values. A stale platform SIZE index must be distinguished from
        // wrong owner/row ID before deciding how to handle an API variation.
        val mismatches = buildList {
            if (actual.id != expectedId) add("row-id")
            if (actual.name != expectedName) add("name")
            if (actual.owner != expectedOwner) add("owner")
            if (actual.relativePath.trimEnd('/') != expectedPath.trimEnd('/')) add("relative-path")
            if (actual.mime != expectedMime) add("mime-type")
            if (actual.pending != pending) add("pending-state")
            if (expectedSize != null && actual.size != expectedSize) add("provider-size")
        }
        if (mismatches.isNotEmpty())
            throw IOException("MediaStore rejected " + mismatches.joinToString(",") +
                "; no unknown document was modified or deleted")
    }

    private fun readIndependent(
        resolver: ContentResolver,
        uri: Uri,
        expectedBytes: ByteArray
    ) {
        val parcel = resolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Published row cannot be independently reopened")
        parcel.use { descriptor ->
            val stat = Os.fstat(descriptor.fileDescriptor)
            // MediaColumns.SIZE is provider-indexed metadata that can lag
            // actual fsynced bytes while IS_PENDING=1. The fresh, reopened
            // descriptor's physical length and content are the prepublication
            // receipt. Do NOT weaken owner/row-id/scope/name/pending checks.
            if (!OsConstants.S_ISREG(stat.st_mode) ||
                stat.st_size != expectedBytes.size.toLong())
                throw IOException("MediaStore reopened object type or physical length disagrees")
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(CHUNK)
            var done = 0
            while (done < expectedBytes.size) {
                val count = Os.pread(descriptor.fileDescriptor, buffer, 0,
                    minOf(buffer.size, expectedBytes.size - done), done.toLong())
                if (count <= 0) throw IOException("MediaStore readback ended unexpectedly")
                digest.update(buffer, 0, count)
                done += count
            }
            val extra = Os.pread(descriptor.fileDescriptor, buffer, 0, 1, done.toLong())
            if (extra != 0 ||
                !digest.digest().contentEquals(
                    MessageDigest.getInstance("SHA-256").digest(expectedBytes)))
                throw IOException("Independent MediaStore readback did not match original bytes")
        }
    }

    /**
     * This proof is opt-in and NOT connected to Compose. A future UI must
     * explain the exact user-visible Downloads/Chunks Exports location.
     * No rollback delete is attempted on any failure or cancellation.
     */
    fun publish(
        context: Context,
        requestedName: String,
        mime: String,
        bytes: ByteArray,
        cancelled: () -> Boolean = { false }
    ): Receipt {
        require(Build.VERSION.SDK_INT >= 29) { "Scoped MediaStore requires Android 10 or later" }
        require(safeName.matches(requestedName) &&
            bytes.isNotEmpty() && bytes.size <= MAX_BYTES &&
            mime in setOf("application/json", "application/octet-stream",
                "application/x-bittorrent", "text/plain")) {
            "Unsafe or oversized standalone export"
        }
        if (cancelled()) throw IOException("Export was cancelled before creation")
        val root = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val extension = requestedName.substringAfterLast('.')
        val stem = requestedName.substringBeforeLast('.').take(140)
        // Even simultaneous same-title publishes request distinct names.
        val unique = "${stem}-" + UUID.randomUUID().toString().replace("-", "") +
            "." + extension
        val relative = Environment.DIRECTORY_DOWNLOADS + "/" + EXPORT_FOLDER + "/"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, unique)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val digest = shaHex(bytes)
        val operation = OwnedMediaStorePublicationJournal.begin(context, unique, mime,
            bytes.size.toLong(), digest)
        return operation.use { journal ->
            val resolver = context.contentResolver
            // Never call insert without a durably fsynced, exclusive intent.
            // The journal is forensic evidence only, not provider write authority.
            // Do not ever delete an uncertain row on an exception.
            // Do not treat an arbitrary returned content URI as an exclusive row.
            val returned = resolver.insert(root, values)
                ?: throw IOException("MediaStore refused fresh Downloads creation")
            val id = try { ContentUris.parseId(returned) }
                catch (error: Exception) { throw IOException("MediaStore did not return a stable numeric row", error) }
            if (id <= 0 || returned != ContentUris.withAppendedId(root, id))
                throw IOException("MediaStore returned an unexpected document target")
            journal.rowReceived(returned)
            requireRow(resolver, returned, id, unique, context.packageName, relative, mime, 1, 0)
            val descriptor = resolver.openFileDescriptor(returned, "rw")
                ?: throw IOException("New pending row has no writable descriptor")
            descriptor.use { out ->
                val stat = Os.fstat(out.fileDescriptor)
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size != 0L)
                    throw IOException("New MediaStore row is not an empty regular file")
                var offset = 0
                while (offset < bytes.size) {
                    if (cancelled()) throw IOException("Export was cancelled; pending row preserved")
                    val count = Os.pwrite(out.fileDescriptor, bytes, offset,
                        minOf(CHUNK, bytes.size - offset), offset.toLong())
                    if (count <= 0) throw IOException("MediaStore write stopped making progress")
                    offset += count
                }
                Os.fsync(out.fileDescriptor)
            }
            // Pre-publish MediaStore's indexed SIZE can remain zero until the
            // platform's synchronous publication scan. Accept this *one*
            // metadata lag ONLY after checking all other exact-row fields.
            // Immediately require physical length and source-independent digest
            // through a freshly opened FD, before any unpend mutation.
            requireRow(resolver, returned, id, unique, context.packageName, relative, mime, 1)
            readIndependent(resolver, returned, bytes)
            journal.bytesSynced()
            if (cancelled()) throw IOException("Export cancelled; verified row left pending")
            journal.publicationRequested()
            val published = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            if (resolver.update(returned, published, null, null) != 1)
                throw IOException("MediaStore publication was not acknowledged exactly once")
            requireRow(resolver, returned, id, unique, context.packageName, relative, mime, 0,
                bytes.size.toLong())
            readIndependent(resolver, returned, bytes)
            journal.publicationVerified()
            return@use Receipt(journal.operationId, returned, unique, bytes.size.toLong(), digest)
        }
    }
}
