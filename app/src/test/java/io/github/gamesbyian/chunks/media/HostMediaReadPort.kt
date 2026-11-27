package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.LocalMediaReadPort
import io.github.gamesbyian.chunks.LocalPinnedReadIdentity
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime

/**
 * Host-only UI fixture adapter. The real Android port is exercised separately
 * in installed API29/35 tests; this provides real bounded host file bytes to
 * the same observation/selection and Compose integration, not a mock result.
 * Host Java cannot portably fstat the channel; no physical race proof claimed.
 */
internal object HostMediaReadPort : LocalMediaReadPort {
    override fun named(path: File): LocalPinnedReadIdentity {
        val attrs = Files.readAttributes(path.toPath(),
            "unix:dev,ino,size,lastModifiedTime,ctime,isRegularFile", LinkOption.NOFOLLOW_LINKS)
        if (attrs["isRegularFile"] != true) throw IOException("Not a regular fixture")
        return LocalPinnedReadIdentity(
            (attrs["dev"] as Number).toLong(), (attrs["ino"] as Number).toLong(),
            (attrs["size"] as Number).toLong(),
            (attrs["lastModifiedTime"] as FileTime).toMillis(),
            (attrs["ctime"] as FileTime).toMillis())
    }

    override fun <T> withPinned(path: File, block: (LocalMediaReadPort.Pinned) -> T): T =
        FileChannel.open(path.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            block(object : LocalMediaReadPort.Pinned {
                override fun identity() = named(path).also {
                    if (it.size != channel.size()) throw IOException("Fixture length changed")
                }
                override fun read(bytes: ByteArray, start: Int, count: Int, offset: Long) =
                    channel.read(ByteBuffer.wrap(bytes, start, count), offset)
            })
        }
}
