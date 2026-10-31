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
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/**
 * Read-only, non-exported provider for sharing a just-created .torrent file.
 * It serves only plain file names inside one cache folder, so a granted URI
 * can never reach any other file. Receivers get access per share only.
 */
class CreatedTorrentProvider : ContentProvider() {
    companion object {
        fun authority(context: Context) = context.packageName + ".created-torrents"
        fun folder(context: Context) = File(context.cacheDir, "created-torrents")

        /**
         * Each share creates a NEW owned cache object. Never truncate a prior
         * export or another file with the same requested name. Retain the
         * original file descriptor through the write and force content before
         * returning a URI; ambiguous failures leave a private orphan to review,
         * not a pathname-based cleanup that could destroy substituted bytes.
         */
        fun publish(context: Context, name: String, bytes: ByteArray): Uri {
            check(name.length in 9..220 && name.endsWith(".torrent") &&
                !name.startsWith('.') && name.none { it == '/' || it == '\\' ||
                    it.isISOControl() }) { "Unsafe torrent share filename" }
            val dir = folder(context)
            val path = dir.toPath()
            try {
                Files.createDirectory(path)
            } catch (_: FileAlreadyExistsException) {
                // Only an actual directory counts, never a symlink.
            }
            check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                "Cache share root is not a plain private directory"
            }
            val stem = name.removeSuffix(".torrent")
            for (attempt in 1..1000) {
                val candidate = if (attempt == 1) name else "${stem} (${attempt}).torrent"
                val outputPath = path.resolve(candidate)
                val output = try {
                    FileChannel.open(outputPath, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                } catch (_: FileAlreadyExistsException) {
                    continue
                }
                output.use { channel ->
                    val remaining = ByteBuffer.wrap(bytes)
                    while (remaining.hasRemaining()) channel.write(remaining)
                    channel.force(true)
                }
                // Do not announce a URI until the new directory entry is
                // durably requested. No fallback to an overwrite-capable open.
                FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
                return Uri.Builder().scheme("content").authority(authority(context))
                    .appendPath(candidate).build()
            }
            throw java.io.IOException("Could not reserve an exclusive share filename")
        }
    }

    private fun file(uri: Uri): File {
        val context = requireNotNull(context)
        val name = uri.pathSegments.singleOrNull() ?: throw FileNotFoundException()
        val dir = folder(context)
        val file = File(dir, name)
        if (file.parentFile != dir || !file.name.endsWith(".torrent") ||
            !Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw FileNotFoundException()
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
