package io.github.gamesbyian.chunks.create

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only, non-exported provider for sharing a just-created .torrent file.
 * It serves only plain file names inside one cache folder, so a granted URI
 * can never reach any other file. Receivers get access per share only.
 */
class CreatedTorrentProvider : ContentProvider() {
    companion object {
        fun authority(context: Context) = context.packageName + ".created-torrents"
        fun folder(context: Context) = File(context.cacheDir, "created-torrents")

        /** Writes [bytes] under a safe name and returns the shareable URI. */
        fun publish(context: Context, name: String, bytes: ByteArray): Uri {
            val dir = folder(context).apply { mkdirs() }
            val file = File(dir, name)
            check(file.parentFile == dir) { "Unsafe name" }
            file.writeBytes(bytes)
            return Uri.Builder().scheme("content").authority(authority(context)).appendPath(file.name).build()
        }
    }

    private fun file(uri: Uri): File {
        val context = requireNotNull(context)
        val name = uri.pathSegments.singleOrNull() ?: throw FileNotFoundException()
        val dir = folder(context)
        val file = File(dir, name)
        if (file.parentFile != dir || !file.name.endsWith(".torrent") || !file.isFile) throw FileNotFoundException()
        return file
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { when (it) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            } }.toTypedArray())
        }
    }

    override fun getType(uri: Uri) = "application/x-bittorrent"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
