package io.github.gamesbyian.chunks.external

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
 * Genuine other-package fixture: the provider is registered in the androidTest
 * APK (a distinct installed app/UID), never in Chunks' target APK.
 * Only opaque, read-only content URLs and explicit Android grants are offered.
 * No provider data or private tracker URL is sent to remote torrent clients.
 */
class ExternalTorrentTestProvider : ContentProvider() {
    companion object {
        private const val suffix = ".external-torrent-proof"
        fun uri(testApp: Context, slot: String): Uri =
            Uri.Builder().scheme("content").authority(testApp.packageName + suffix)
                .appendPath("opaque").appendPath(slot).build()
        fun file(testApp: Context, slot: String) =
            File(testApp.filesDir, "external-$slot.torrent")
    }

    override fun onCreate(): Boolean = true

    private fun source(uri: Uri): File {
        val parts = uri.pathSegments
        if (parts.size != 2 || parts[0] != "opaque" ||
            parts[1] !in setOf("first", "second"))
            throw FileNotFoundException("Unsupported test document")
        return file(requireNotNull(context), parts[1])
            .takeIf { it.isFile } ?: throw FileNotFoundException("Missing test document")
    }

    override fun getType(uri: Uri): String {
        source(uri)
        return "application/octet-stream"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = source(uri)
        val fields = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(fields).apply {
            addRow(fields.map { field -> when (field) {
                OpenableColumns.DISPLAY_NAME -> "external.torrent"
                OpenableColumns.SIZE -> file.length()
                else -> null
            } }.toTypedArray())
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Test source is read-only")
        return ParcelFileDescriptor.open(source(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("No source mutation")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("No source mutation")
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("No source mutation")
}
