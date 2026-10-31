package io.github.gamesbyian.chunks.storage

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * A disposable ZIP verification file with NO directory entry, no filename to
 * replace, and no pathname deletion. The pinned descriptor owns the anonymous
 * inode for the entire ZIP reader lifetime. Closing it releases staging bytes.
 *
 * Linux O_TMPFILE is attempted on the app-private cache filesystem (API29+).
 * On API30+, a memfd is an alternative if O_TMPFILE is unavailable. An
 * unsupported environment fails closed; never fall back to a named temporary
 * file or reopen a created pathname for truncating output.
 *
 * /proc/self/fd/<fd> is a capability path to the still-open anonymous inode.
 * Consumers must use [use] and must NOT retain [file] after [close]. The
 * resulting File works only when that Android kernel permits reopening the
 * process-owned FD through /proc. Independent API29/35 proof is mandatory.
 */
internal class AnonymousZipReviewStage private constructor(
    private val pinned: ParcelFileDescriptor,
    val file: File,
    val copiedBytes: Long
) : Closeable {
    override fun close() = pinned.close()

    companion object {
        /** Linux asm-generic O_TMPFILE = (__O_TMPFILE | O_DIRECTORY). */
        private const val O_TMPFILE_LINUX = 0x410000
        private const val MFD_CLOEXEC_LINUX = 0x0001

        /** No named objects are ever created by this method. */
        fun stage(
            resolver: ContentResolver,
            sourceUri: Uri,
            privateCache: File,
            maxBytes: Long,
            cancelled: () -> Boolean = { false }
        ): AnonymousZipReviewStage {
            require(maxBytes > 0)
            if (sourceUri.scheme != "content")
                throw IOException("Only Android document sources can be inspected")
            if (!privateCache.isDirectory ||
                java.nio.file.Files.isSymbolicLink(privateCache.toPath()))
                throw IOException("Private review cache is unavailable")

            val descriptor = openAnonymous(privateCache)
            var transferred = false
            try {
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                val stream: InputStream = resolver.openInputStream(sourceUri)
                    ?: throw IOException("Android could not read the selected document")
                stream.use { input ->
                    while (true) {
                        if (cancelled() || Thread.currentThread().isInterrupted)
                            throw IOException("ZIP review was cancelled or interrupted")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) throw IOException("ZIP input stream made no progress")
                        if (count.toLong() > maxBytes - total)
                            throw IOException("ZIP review exceeds permitted staging bytes")
                        var offset = 0
                        while (offset < count) {
                            val n = Os.write(descriptor.fileDescriptor, buffer, offset, count - offset)
                            if (n <= 0) throw IOException("Anonymous staging write stopped making progress")
                            offset += n
                        }
                        total += count
                    }
                }
                Os.fsync(descriptor.fileDescriptor)
                val stat = Os.fstat(descriptor.fileDescriptor)
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 0L)
                    throw IOException("Review staging is not an anonymous, unlinked regular file")
                // Reopening /proc/self/fd is independent of the staged file's
                // former logical name, and never targets an external pathname.
                val view = File("/proc/self/fd/${descriptor.fd}")
                java.util.zip.ZipFile(view).use { _ ->
                    // Probe the actual ZIP open, not merely fstat success.
                }
                val staged = AnonymousZipReviewStage(descriptor, view, total)
                transferred = true
                return staged
            } catch (e: ErrnoException) {
                throw IOException("Anonymous ZIP staging is unsupported or failed", e)
            } finally {
                if (!transferred) descriptor.close()
            }
        }

        private fun openAnonymous(privateCache: File): ParcelFileDescriptor {
            val fd = try {
                Os.open(privateCache.absolutePath,
                    O_TMPFILE_LINUX or OsConstants.O_RDWR or OsConstants.O_CLOEXEC, 0x180)
            } catch (tmpError: ErrnoException) {
                if (Build.VERSION.SDK_INT < 30) {
                    throw IOException("This Android filesystem cannot safely stage a ZIP anonymously", tmpError)
                }
                try {
                    Os.memfd_create("chunks-zip-review", MFD_CLOEXEC_LINUX)
                } catch (memError: ErrnoException) {
                    throw IOException("No anonymous ZIP staging capability is available", memError)
                }
            }
            try {
                return ParcelFileDescriptor.dup(fd)
            } finally {
                Os.close(fd)
            }
        }
    }
}
