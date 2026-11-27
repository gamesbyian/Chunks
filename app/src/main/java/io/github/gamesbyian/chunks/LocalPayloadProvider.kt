package io.github.gamesbyian.chunks

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileNotFoundException

/**
 * Non-exported, per-URI grant provider. A proxy descriptor makes even an
 * already-open reader subject to expiry, explicit revocation and fresh
 * native/file identity checks. No pathname or direct raw FD leaves Chunks.
 */
class LocalPayloadProvider : ContentProvider() {
    companion object {
        private val handler by lazy {
            Handler(HandlerThread("Chunks local file reader").apply { start() }.looper)
        }
    }

    override fun onCreate() = true

    private fun resolve(uri: Uri): LocalPayloadAccess.Grant {
        val ctx = requireNotNull(context)
        val grant = LocalPayloadAccess.lookup(ctx, uri) ?: throw FileNotFoundException("File access expired or unavailable")
        if (!LocalPayloadAccess.current(ctx, uri, grant))
            throw FileNotFoundException("Reviewed file is no longer available")
        return grant
    }

    override fun getType(uri: Uri): String = resolve(uri).source.mime

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val source = resolve(uri).source
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { when (it) {
                OpenableColumns.DISPLAY_NAME -> source.name
                OpenableColumns.SIZE -> source.file.sizeBytes
                else -> null
            } }.toTypedArray())
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only file access")
        val ctx = requireNotNull(context).applicationContext
        val grant = LocalPayloadAccess.acquire(ctx, uri)
            ?: throw FileNotFoundException("File access expired, unavailable or reader limit reached")
        val fd = try { LocalPayloadAccess.open(ctx, uri, grant) } catch (_: Exception) { null }
        if (fd == null) {
            LocalPayloadAccess.release(grant)
            throw FileNotFoundException("Reviewed file is unavailable")
        }
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) {
                try { Os.close(fd) } finally { LocalPayloadAccess.release(grant) }
            }
        }
        val callback = object : ProxyFileDescriptorCallback() {
            private fun checkCurrent() {
                if (released.get() || !LocalPayloadAccess.current(ctx, uri, grant, fd))
                    throw ErrnoException("File access revoked", OsConstants.EACCES)
            }
            override fun onGetSize(): Long {
                checkCurrent()
                return grant.source.file.sizeBytes
            }
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                checkCurrent()
                val length = grant.source.file.sizeBytes
                if (offset < 0 || size < 0 || size > data.size)
                    throw ErrnoException("Invalid read range", OsConstants.EINVAL)
                if (offset >= length || size == 0) return 0
                val requested = minOf(size.toLong(), length - offset).toInt()
                var total = 0
                while (total < requested) {
                    val count = Os.pread(fd, data, total, requested - total, offset + total)
                    if (count <= 0 || count > requested - total)
                        throw ErrnoException("Incomplete file read", OsConstants.EIO)
                    total += count
                }
                // Do not return the bytes if the source or grant changed
                // during the range read. This cannot recall previously read bytes.
                checkCurrent()
                return total
            }
            override fun onRelease() { release() }
        }
        return try {
            val manager = ctx.getSystemService(StorageManager::class.java)
                ?: throw FileNotFoundException("Seekable access unavailable")
            manager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, handler)
        } catch (_: Exception) {
            release()
            throw FileNotFoundException("Seekable file access unavailable")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
