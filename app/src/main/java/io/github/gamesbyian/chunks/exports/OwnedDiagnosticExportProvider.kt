package io.github.gamesbyian.chunks.exports

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
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Shares independently created diagnostic/snapshot bytes from app-private cache.
 * It NEVER opens an Android picker-returned URI for writing, renames a foreign
 * destination, or deletes a pathname based on a saved journal. A receiving app
 * controls its own final Save operation after the user's explicit share choice.
 *
 * The cache is private to this app's UID. No promise of resilience against
 * malicious code running with the same UID which can rename its parent root.
 */
class OwnedDiagnosticExportProvider : ContentProvider() {
    companion object {
        private const val MAX_BYTES = 64 * 1024 * 1024
        private val VALID_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._() -]{0,159}\\.(json|chunksnap|torrent|txt)")

        fun authority(context: Context) = context.packageName + ".owned-exports"
        fun folder(context: Context) = File(context.cacheDir, "owned-diagnostic-exports")

        private fun mime(name: String) = when {
            name.endsWith(".chunksnap") -> "application/octet-stream"
            name.endsWith(".json") -> "application/json"
            name.endsWith(".torrent") -> "application/x-bittorrent"
            name.endsWith(".txt") -> "text/plain"
            else -> null
        }

        /** Creates a new, isolated read-only share; never adopts an existing name. */
        fun publish(context: Context, name: String, bytes: ByteArray): Uri {
            require(VALID_NAME.matches(name) && bytes.isNotEmpty() && bytes.size <= MAX_BYTES) {
                "Invalid diagnostic export"
            }
            val root = folder(context).toPath()
            val createdRoot = try {
                Files.createDirectory(root)
                true
            } catch (_: FileAlreadyExistsException) { false }
            check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                "Private export location is unavailable"
            }
            if (createdRoot) {
                FileChannel.open(context.cacheDir.toPath(), StandardOpenOption.READ)
                    .use { it.force(true) }
            }
            // The random suffix prevents misleading reuse across simultaneous
            // exports, while CREATE_NEW is the actual no-replace protection.
            // The provider also validates the *generated* basename on every
            // read. Reserve 33 characters for the hyphen and 128-bit UUID;
            // otherwise a valid 160-character input can create an unreadable URI.
            val stem = name.substringBeforeLast('.').take(127)
            val extension = name.substringAfterLast('.')
            for (attempt in 0 until 16) {
                val unique = "${stem}-" + UUID.randomUUID().toString().replace("-", "") +
                    "." + extension
                check(VALID_NAME.matches(unique)) { "Export name is not round-trip safe" }
                val file = root.resolve(unique)
                val channel = try {
                    FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                } catch (_: FileAlreadyExistsException) { continue }
                channel.use { out ->
                    val body = ByteBuffer.wrap(bytes)
                    while (body.hasRemaining()) {
                        if (out.write(body) <= 0) throw IOException("Export write made no progress")
                    }
                    out.force(true)
                    out.position(0)
                    val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteBuffer.allocate(64 * 1024)
                    var count = 0
                    while (true) {
                        buffer.clear()
                        val n = out.read(buffer)
                        if (n < 0) break
                        if (n == 0 || n > bytes.size - count)
                            throw IOException("Export readback was incomplete or exceeded the expected bytes")
                        count += n
                        digest.update(buffer.array(), 0, n)
                    }
                    if (count != bytes.size || !digest.digest().contentEquals(expected))
                        throw IOException("Export bytes failed independent descriptor readback")
                }
                FileChannel.open(root, StandardOpenOption.READ).use { it.force(true) }
                return Uri.Builder().scheme("content").authority(authority(context))
                    .appendPath(unique).build()
            }
            throw IOException("Could not exclusively create export output")
        }
    }

    private fun ownedFile(uri: Uri): File {
        val ctx = requireNotNull(context)
        val name = uri.pathSegments.singleOrNull() ?: throw FileNotFoundException()
        if (uri.authority != authority(ctx) || uri.scheme != "content" ||
            mime(name) == null || !VALID_NAME.matches(name))
            throw FileNotFoundException()
        val root = folder(ctx)
        val file = File(root, name)
        if (file.parentFile != root || !Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw FileNotFoundException()
        return file
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Export documents are read-only")
        val file = ownedFile(uri)
        val fd = try {
            Os.open(file.absolutePath,
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        } catch (e: ErrnoException) {
            throw FileNotFoundException("Export is unavailable").apply { initCause(e) }
        }
        try {
            val st = Os.fstat(fd)
            if (!OsConstants.S_ISREG(st.st_mode) || st.st_nlink != 1L)
                throw FileNotFoundException("Export is not an isolated regular file")
            return ParcelFileDescriptor.dup(fd)
        } finally {
            Os.close(fd)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = ownedFile(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val fields: Map<String, Any?> = mapOf(
            OpenableColumns.DISPLAY_NAME to file.name,
            OpenableColumns.SIZE to file.length()
        )
        return MatrixCursor(cols).apply { addRow(cols.map { fields[it] }.toTypedArray()) }
    }

    override fun getType(uri: Uri): String =
        mime(ownedFile(uri).name) ?: throw FileNotFoundException()

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
