package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalArchivePiecePlan
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.media.inspection.MediaByteSource
import java.io.IOException
import java.security.MessageDigest

/** Small immutable captured bytes, not a guarantee about later disk contents.
 * No decoder, output writer, private staging path or automatic authority. */
internal class LocalArchiveByteSnapshot private constructor(
    private val files: List<Pair<LocalTransferFile, ByteArray>>
) {
    val sizeBytes: Long get() = files.sumOf { it.second.size.toLong() }
    val volumeCount: Int get() = files.size

    fun sources(): List<MediaByteSource> = files.map { (_, bytes) ->
        object : MediaByteSource {
            override val size = bytes.size.toLong()
            override fun read(offset: Long, length: Int): ByteArray {
                if (offset < 0 || offset > size || length < 0 || length.toLong() > size - offset)
                    throw IOException("Invalid captured archive range")
                return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
            }
        }
    }

    companion object {
        /** All captured bytes must come from independently matched whole pieces. */
        fun capture(plan: LocalArchivePiecePlan,
                    open: (LocalTransferFile) -> MediaByteSource?,
                    cancelled: () -> Boolean): LocalArchiveByteSnapshot? {
            return try {
                val captured = plan.selected.associate { it.index to ByteArray(it.sizeBytes.toInt()) }
                val filled = plan.selected.associate { it.index to 0L }.toMutableMap()
                val readers = mutableMapOf<Int, MediaByteSource>()
                for (piece in plan.pieces) {
                    if (cancelled()) return null
                    val digest = MessageDigest.getInstance("SHA-1")
                    for (slice in piece.slices) {
                        if (cancelled()) return null
                        val source = readers[slice.file.index] ?: (open(slice.file) ?: return null).also {
                            if (it.size != slice.file.sizeBytes) return null
                            readers[slice.file.index] = it
                        }
                        val bytes = source.read(slice.offset, slice.length)
                        if (bytes.size != slice.length) return null
                        digest.update(bytes)
                        captured[slice.file.index]?.let { output ->
                            if (filled[slice.file.index] != slice.offset ||
                                slice.offset + slice.length > output.size) return null
                            bytes.copyInto(output, slice.offset.toInt())
                            filled[slice.file.index] = slice.offset + slice.length
                        }
                    }
                    val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    if (hex != piece.sha1) return null
                }
                readers.values.forEach { it.read(0, 0) }
                if (cancelled() || plan.selected.any { filled[it.index] != it.sizeBytes }) null
                else LocalArchiveByteSnapshot(plan.selected.map { it to captured.getValue(it.index) })
            } catch (_: Exception) { null }
        }
    }
}
