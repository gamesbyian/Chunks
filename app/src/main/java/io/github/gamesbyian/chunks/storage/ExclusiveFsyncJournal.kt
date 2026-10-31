package io.github.gamesbyian.chunks.storage

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Experimental app-PRIVATE write-ahead log for a single publication operation.
 * The channel is opened exactly once with CREATE_NEW and NOFOLLOW_LINKS, and
 * retained for all appends. It never reopens its pathname for writing, truncates
 * an existing object, cleans up an unknown path, or interprets intent as title.
 *
 * Parent directory must be accessible only within the application's private
 * security boundary. Path-based open cannot pin an adversarially renameable
 * parent; do not use this for shared Downloads or SAF, and do not use the log
 * itself as proof of ownership of any destination object.
 */
internal class ExclusiveFsyncJournal private constructor(
    val path: Path,
    private val channel: FileChannel
) : Closeable {
    companion object {
        private val ID = Regex("[a-f0-9]{32}")
        private const val MAX_RECOVERY_BYTES = 1024 * 1024L

        fun create(privateDirectory: Path, operationId: String): ExclusiveFsyncJournal {
            require(ID.matches(operationId))
            require(Files.isDirectory(privateDirectory, LinkOption.NOFOLLOW_LINKS))
            val path = privateDirectory.resolve("${operationId}.log")
            val modes = setOf<OpenOption>(StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            val channel = FileChannel.open(path, modes)
            try {
                // The new log directory entry must survive a crash before we
                // permit the corresponding external operation to proceed.
                FileChannel.open(privateDirectory, StandardOpenOption.READ).use { it.force(true) }
                return ExclusiveFsyncJournal(path, channel)
            } catch (failure: Exception) {
                channel.close()
                // Creating a name might already have succeeded. Never delete
                // it by name: an attacker or provider may have substituted it.
                throw IOException("Could not durably establish new intent journal", failure)
            }
        }

        /**
         * Read-only, bounded forensic recovery. The Android branch must open
         * its final file NONBLOCKING before inspecting its type: a same-UID
         * attacker can replace an earlier isRegularFile() result with a FIFO
         * just before FileChannel.open(), hanging process recovery forever.
         *
         * Only the originally pinned FD is read, never a reopened pathname.
         * Hardlinks, symlinks, FIFOs, oversized files, and changes to the
         * pinned file's identity, link count or length fail closed. These
         * checks cannot detect same-length in-place content changes.
         * Host-only JVM fixtures keep NIO compatibility without Android APIs.
         */
        private fun runningOnAndroid(): Boolean = try {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        } catch (_: NoClassDefFoundError) { false }

        private fun completedLines(bytes: ByteArray): List<String> {
            val text = bytes.toString(Charsets.UTF_8)
            val end = text.lastIndexOf('\n')
            if (end < 0) return emptyList()
            return text.substring(0, end).split('\n')
        }

        fun readCompleteLines(path: Path): List<String>? {
            if (runningOnAndroid()) {
                val fd = try {
                    Os.open(path.toString(), OsConstants.O_RDONLY or
                        OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or
                        OsConstants.O_CLOEXEC, 0)
                } catch (_: Exception) { return null }
                try {
                    val original = Os.fstat(fd)
                    if (!OsConstants.S_ISREG(original.st_mode) ||
                        original.st_nlink != 1L ||
                        original.st_size < 0L ||
                        original.st_size > MAX_RECOVERY_BYTES) return null
                    val bytes = ByteArray(original.st_size.toInt())
                    var received = 0
                    while (received < bytes.size) {
                        val count = Os.pread(fd, bytes, received,
                            bytes.size - received, received.toLong())
                        if (count <= 0) return null
                        received += count
                    }
                    val after = Os.fstat(fd)
                    if (!OsConstants.S_ISREG(after.st_mode) ||
                        after.st_nlink != 1L || after.st_size != original.st_size ||
                        after.st_dev != original.st_dev ||
                        after.st_ino != original.st_ino) return null
                    return completedLines(bytes)
                } catch (_: Exception) { return null }
                finally { runCatching { Os.close(fd) } }
            }
            // JVM host-only path for non-Android unit fixtures. Android never
            // takes this point-in-time pathname-survey/open sequence.
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
            val opts = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            return try {
                FileChannel.open(path, opts).use { channel ->
                    if (channel.size() > MAX_RECOVERY_BYTES) return null
                    val bytes = ByteBuffer.allocate(channel.size().toInt())
                    while (bytes.hasRemaining()) {
                        if (channel.read(bytes) <= 0) return null
                    }
                    completedLines(bytes.array())
                }
            } catch (_: Exception) { null }
        }
    }

    @Synchronized fun append(record: String) {
        require(record.isNotEmpty() && record.length <= 4096 &&
            record.none { it == '\n' || it == '\r' || it == '\u0000' })
        val line = ByteBuffer.wrap((record + "\n").toByteArray(Charsets.UTF_8))
        while (line.hasRemaining()) channel.write(line)
        channel.force(true)
    }

    override fun close() = channel.close()
}
