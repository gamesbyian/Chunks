package io.github.gamesbyian.chunks.engine

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Read only a bounded, existing app-private resume file. File attributes can
 * change between the size probe and open/read, so the stream is independently
 * bounded too. A missing, unreadable, truncated or growing file is not a valid
 * resume witness; never delete or rewrite it during recovery.
 *
 * A symlink is not a trusted app-private resume snapshot. The production
 * opener also refuses a link substituted between attribute check and open.
 */
internal object LocalResumeFileReader {
    // One stable default-opener identity lets host/fixture tests continue to
    // inject a short/failing InputStream. Android's production call uses the
    // pinned descriptor path instead of NIO's survey-then-open sequence.
    private val defaultOpener: (File) -> InputStream = {
        Files.newInputStream(it.toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    private fun isAndroid(): Boolean = try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    } catch (_: NoClassDefFoundError) { false }

    /**
     * Real Android file admission is READ ONLY. Open O_NONBLOCK *before*
     * classifying the inode: a FIFO substituted after a pathname preflight
     * can otherwise indefinitely block session restoration. Never follow
     * final links or reopen the path, and require the SAME regular, uniquely
     * linked FD and exact byte length after the bounded read. A successful
     * parse does not establish native payload ownership under #568.
     */
    private fun readPinnedAndroid(file: File, maxBytes: Long): ByteArray? {
        if (maxBytes <= 0L) return null
        val fd = try {
            Os.open(file.absolutePath, OsConstants.O_RDONLY or
                OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or
                OsConstants.O_CLOEXEC, 0)
        } catch (_: Exception) { return null }
        try {
            val before = Os.fstat(fd)
            if (!OsConstants.S_ISREG(before.st_mode) ||
                before.st_nlink != 1L || before.st_size !in 1L..maxBytes ||
                before.st_size > Int.MAX_VALUE.toLong()) return null
            val bytes = ByteArray(before.st_size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = Os.pread(fd, bytes, offset, bytes.size - offset,
                    offset.toLong())
                if (count <= 0) return null
                offset += count
            }
            val after = Os.fstat(fd)
            if (!OsConstants.S_ISREG(after.st_mode) ||
                after.st_nlink != 1L ||
                after.st_size != before.st_size ||
                after.st_dev != before.st_dev ||
                after.st_ino != before.st_ino) return null
            return bytes
        } catch (_: Exception) { return null }
        finally { runCatching { Os.close(fd) } }
    }

    fun read(
        file: File,
        maxBytes: Long,
        open: (File) -> InputStream = defaultOpener
    ): ByteArray? {
        if (open === defaultOpener && isAndroid()) return readPinnedAndroid(file, maxBytes)
        return readPortable(file, maxBytes, open)
    }

    private fun readPortable(file: File, maxBytes: Long,
                             open: (File) -> InputStream): ByteArray? = try {
        val path = file.toPath()
        val expectedLength = file.length()
        if (maxBytes <= 0L || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
            expectedLength !in 1L..maxBytes) null
        else open(file).use { stream ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0 || bytes.size().toLong() + count > maxBytes) return null
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray().takeIf {
                it.size.toLong() == expectedLength && file.length() == expectedLength
            }
        }
    } catch (_: Exception) {
        null
    }
}
