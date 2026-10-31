package io.github.gamesbyian.chunks.create

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
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
            check(name.length in 9..220 && name.toByteArray(Charsets.UTF_8).size <= 230 &&
                name.endsWith(".torrent") &&
                !name.startsWith('.') && name.none { it == '/' || it == '\\' ||
                    it.isISOControl() }) { "Unsafe torrent share filename" }
            val dir = folder(context)
            val path = dir.toPath()
            val createdDirectory = try {
                Files.createDirectory(path)
                true
            } catch (_: FileAlreadyExistsException) {
                // Only an actual directory counts, never a symlink.
                false
            }
            check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                "Cache share root is not a plain private directory"
            }
            if (createdDirectory) {
                // A newly created private directory must itself be durably
                // linked before publishing objects inside it.
                FileChannel.open(context.cacheDir.toPath(), StandardOpenOption.READ)
                    .use { it.force(true) }
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
        if (uri.scheme != "content" || uri.authority != authority(context) ||
            uri.query != null || uri.fragment != null ||
            file.parentFile != dir || !file.name.endsWith(".torrent") ||
            !Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw FileNotFoundException()
        // Deliberately do NOT preflight the leaf's type by pathname.
        // The pinned nonblocking descriptor is the only authority below.
        return file
    }

    private data class PinnedShare(
        val name: String,
        val descriptor: ParcelFileDescriptor,
        val size: Long
    )

    /**
     * Pin before inspecting. A foreign FIFO substituted after any earlier
     * pathname survey would block O_RDONLY forever unless O_NONBLOCK is set.
     * Reject symbolic links, pipes, multiply linked objects and wrong types
     * on the ACTUAL open FD. A share URI never authorizes writing or cleanup.
     *
     * This is a read-only type/identity gate, not durable per-share inode
     * ownership evidence against a malicious same-UID regular-file swap.
     */
    private fun pinShare(uri: Uri): PinnedShare {
        val candidate = file(uri)
        val fd = try {
            Os.open(candidate.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
                    OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
        } catch (e: ErrnoException) {
            throw FileNotFoundException("Share is unavailable").apply { initCause(e) }
        }
        try {
            val stat = Os.fstat(fd)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1L ||
                stat.st_size < 0L) throw FileNotFoundException("Share is not a unique regular file")
            return PinnedShare(candidate.name, ParcelFileDescriptor.dup(fd), stat.st_size)
        } catch (e: ErrnoException) {
            throw FileNotFoundException("Share identity could not be verified").apply { initCause(e) }
        } finally {
            Os.close(fd)
        }
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read-only")
        return pinShare(uri).descriptor
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val pinned = pinShare(uri)
        return pinned.descriptor.use {
            val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            MatrixCursor(columns).apply {
                addRow(columns.map { when (it) {
                    OpenableColumns.DISPLAY_NAME -> pinned.name
                    OpenableColumns.SIZE -> pinned.size
                    else -> null
                } }.toTypedArray())
            }
        }
    }

    override fun getType(uri: Uri) = "application/x-bittorrent"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
        throw UnsupportedOperationException()
}
