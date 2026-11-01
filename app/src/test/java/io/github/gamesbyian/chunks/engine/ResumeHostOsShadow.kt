package io.github.gamesbyian.chunks.engine

import android.system.Os
import android.system.StructStat
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.IdentityHashMap
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric's ShadowLinux cannot open directories and reports nlink=0.
 * Bridge just these calls to the HOST filesystem to exercise real SQLite and
 * migration logic. This is explicitly not Android descriptor/fsync evidence;
 * the instrumented suite executes the actual Android Os calls unchanged.
 */
@Implements(Os::class)
class ResumeHostOsShadow {
    companion object {
        private val channels = IdentityHashMap<FileDescriptor, Pair<Path, FileChannel>>()

        @Implementation @JvmStatic fun open(path: String, flags: Int, mode: Int): FileDescriptor {
            val target = Path.of(path)
            val channel = FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            return FileDescriptor().also { channels[it] = target to channel }
        }

        @Implementation @JvmStatic fun fsync(fd: FileDescriptor) { channels.getValue(fd).second.force(true) }
        @Implementation @JvmStatic fun close(fd: FileDescriptor) { channels.remove(fd)!!.second.close() }
        @Implementation @JvmStatic fun pread(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int,
                                            position: Long): Int =
            channels.getValue(fd).second.read(ByteBuffer.wrap(bytes, offset, count), position)

        @Implementation @JvmStatic fun fstat(fd: FileDescriptor): StructStat = stat(channels.getValue(fd).first)
        @Implementation @JvmStatic fun lstat(path: String): StructStat = stat(Path.of(path))

        private fun stat(path: Path): StructStat {
            val attributes = Files.readAttributes(path, "unix:*", LinkOption.NOFOLLOW_LINKS)
            fun number(key: String) = attributes.getValue(key) as Number
            return StructStat(number("dev").toLong(), number("ino").toLong(), number("mode").toInt(),
                number("nlink").toLong(), number("uid").toInt(), number("gid").toInt(),
                number("rdev").toLong(), number("size").toLong(), 0, 0, 0, 4096, 0)
        }
    }
}
