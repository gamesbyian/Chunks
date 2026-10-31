package io.github.gamesbyian.chunks.storage

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

        /** Read-only, bounded forensic recovery. This never authorizes cleanup. */
        fun readCompleteLines(path: Path): List<String>? {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
            val opts = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            FileChannel.open(path, opts).use { channel ->
                if (channel.size() > MAX_RECOVERY_BYTES) return null
                val bytes = ByteBuffer.allocate(channel.size().toInt())
                while (bytes.hasRemaining()) {
                    if (channel.read(bytes) <= 0) return null
                }
                val raw = bytes.array().toString(Charsets.UTF_8)
                val end = raw.lastIndexOf('\n')
                if (end < 0) return emptyList()
                return raw.substring(0, end).split('\n')
            }
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
