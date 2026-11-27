package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.media.inspection.MediaByteSource
import io.github.gamesbyian.chunks.media.inspection.BudgetedMediaByteSource
import java.io.IOException
import java.util.zip.CRC32

/**
 * Conservative structural evidence, not a decoder. See RARLAB's RAR5
 * technote (https://www.rarlab.com/technote.htm) and UnRAR RAR4 header
 * definitions. Scan actual block boundaries; never search data for a
 * convenient end signature. No SFX, encrypted headers, old RAR variants,
 * or unrecognized block types in this initial slice.
 *
 * CRC-protected main/end facts still cannot prove common set identity,
 * compressed data integrity, file names or extraction safety.
 */
internal object RarVolumeHeaderReader {
    const val MAX_HEADER_BYTES = 256 * 1024
    const val READ_BUDGET = 4L * 1024 * 1024
    const val MAX_BLOCKS = 4096

    enum class Format { RAR4, RAR5 }
    enum class Status { HEADER_CHAIN_OBSERVED, UNRECOGNIZED, ENCRYPTED_HEADERS, UNCONFIRMED }
    data class Result(
        val status: Status,
        val format: Format? = null,
        val multiVolume: Boolean? = null,
        /** One-based; null for old continuation volumes without number. */
        val ordinal: Int? = null,
        val nextVolume: Boolean? = null
    )

    private val sig4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0)
    private val sig5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 1, 0)
    private fun refuse(): Nothing = throw IOException("Unsupported or invalid RAR header chain")
    private fun ByteArray.u8(i: Int): Int = getOrNull(i)?.toInt()?.and(255) ?: refuse()
    private fun ByteArray.u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
    private fun ByteArray.u32(i: Int): Long =
        u16(i).toLong() or (u16(i + 2).toLong() shl 16)
    private fun crc(bytes: ByteArray, offset: Int, count: Int): Long =
        CRC32().apply { update(bytes, offset, count) }.value

    private fun MediaByteSource.exact(offset: Long, count: Int): ByteArray {
        if (offset < 0 || count < 0 || offset > size || count.toLong() > size - offset) refuse()
        return read(offset, count).also { if (it.size != count) refuse() }
    }

    private fun advance(offset: Long, length: Long, end: Long): Long {
        if (offset < 0 || length < 0 || offset > end || length > end - offset) refuse()
        return offset + length
    }

    fun inspect(source: MediaByteSource): Result {
        var format: Format? = null
        return try {
            val input = BudgetedMediaByteSource(source, READ_BUDGET)
            val head = input.read(0, 8)
            when {
                head.contentEquals(sig5) -> {
                    format = Format.RAR5
                    rar5(input)
                }
                head.size >= sig4.size && head.copyOf(sig4.size).contentEquals(sig4) -> {
                    format = Format.RAR4
                    rar4(input)
                }
                else -> Result(Status.UNRECOGNIZED)
            }
        } catch (_: Exception) { Result(Status.UNCONFIRMED, format) }
    }

    private fun rar4(input: MediaByteSource): Result {
        var pos = sig4.size.toLong()
        var multi = false
        var first = false
        var sawMain = false
        var splitAfter = false
        repeat(MAX_BLOCKS) {
            val prefix = input.exact(pos, 7)
            val type = prefix.u8(2)
            val flags = prefix.u16(3)
            val size = prefix.u16(5)
            if (size < 7) refuse()
            val header = input.exact(pos, size)
            if ((crc(header, 2, size - 2) and 0xffff) != header.u16(0).toLong()) refuse()
            val dataStart = advance(pos, size.toLong(), input.size)
            if (!sawMain && type != 0x73) refuse()
            when (type) {
                0x73 -> {
                    if (sawMain || size != 13 || flags and 0x8000 != 0) refuse()
                    // Header encryption requires password/decryption before
                    // any following bytes can be treated as headers.
                    if (flags and 0x0080 != 0) return Result(Status.ENCRYPTED_HEADERS, Format.RAR4)
                    if (flags and 0x01dd.inv() != 0) refuse()
                    multi = flags and 1 != 0
                    first = flags and 0x0100 != 0
                    if (first && !multi) refuse()
                    sawMain = true
                    pos = dataStart
                }
                0x74, 0x7a -> {
                    // RAR4 file/service headers carry packed size, including
                    // a high 32-bit word for large files. Do not read payload.
                    if (size < 32 || flags and 0x8000 == 0) refuse()
                    val high = if (flags and 0x0100 != 0) {
                        if (size < 40) refuse()
                        header.u32(32)
                    } else 0L
                    if (high > Int.MAX_VALUE.toLong()) refuse()
                    val packed = (high shl 32) or header.u32(7)
                    val nameStart = if (flags and 0x0100 != 0) 40 else 32
                    val nameSize = header.u16(26)
                    if (nameSize == 0 || nameSize > size - nameStart) refuse()
                    if (type == 0x74) {
                        if (!multi && flags and 3 != 0) refuse()
                        if (first && flags and 1 != 0) refuse()
                        splitAfter = flags and 2 != 0
                    }
                    pos = advance(dataStart, packed, input.size)
                }
                0x7b -> {
                    if (flags and 0x000f.inv() != 0) refuse()
                    val next = flags and 1 != 0
                    if ((!multi && next) || (splitAfter && !next)) refuse()
                    var cursor = 7
                    if (flags and 2 != 0) cursor += 4 // optional data CRC, not checked here
                    val number = if (flags and 8 != 0) {
                        val n = header.u16(cursor) + 1
                        cursor += 2
                        n
                    } else null
                    if (flags and 4 != 0) cursor += 7
                    if (cursor != size || dataStart != input.size) refuse()
                    if (number != null && ((!multi && number != 1) || (first && number != 1))) refuse()
                    return Result(Status.HEADER_CHAIN_OBSERVED, Format.RAR4, multi,
                        number ?: if (!multi || first) 1 else null, next)
                }
                else -> refuse()
            }
        }
        refuse()
    }

    /** Unsigned RAR vint constrained to representable signed Long.
     * Nonminimal encodings are permitted by the format. */
    private class Cursor(private val bytes: ByteArray, var pos: Int = 0, val end: Int = bytes.size) {
        fun vint(maxBytes: Int = 9): Long {
            var value = 0L
            repeat(maxBytes) { shift ->
                if (pos >= end) refuse()
                val byte = bytes.u8(pos++)
                value = value or ((byte and 127).toLong() shl (7 * shift))
                if (byte and 128 == 0) return value
            }
            refuse()
        }
    }

    private fun rar5(input: MediaByteSource): Result {
        var pos = sig5.size.toLong()
        var multi = false
        var ordinal = 1
        var sawMain = false
        var splitAfter = false
        repeat(MAX_BLOCKS) {
            // Read no more than the three permitted size-vint bytes beyond
            // the CRC. A tiny header at EOF may have fewer than seven bytes.
            val prefix = input.read(pos, 7)
            if (prefix.size < 5) refuse()
            val sizeCursor = Cursor(prefix, 4)
            val bodySize = sizeCursor.vint(3)
            if (bodySize !in 3L..MAX_HEADER_BYTES.toLong()) refuse()
            val total = sizeCursor.pos + bodySize.toInt()
            val header = input.exact(pos, total)
            if (crc(header, 4, total - 4) != header.u32(0)) refuse()
            val common = Cursor(header, sizeCursor.pos)
            val type = common.vint()
            val flags = common.vint()
            if (flags and 0x7fL.inv() != 0L) refuse()
            val extra = if (flags and 1L != 0L) common.vint() else 0L
            val data = if (flags and 2L != 0L) common.vint() else 0L
            if (extra > total - common.pos) refuse()
            val fields = Cursor(header, common.pos, total - extra.toInt())
            val dataStart = advance(pos, total.toLong(), input.size)
            val next = advance(dataStart, data, input.size)
            if (!sawMain && type !in setOf(1L, 4L)) refuse()
            when (type) {
                4L -> {
                    if (sawMain) refuse()
                    return Result(Status.ENCRYPTED_HEADERS, Format.RAR5)
                }
                1L -> {
                    if (sawMain || flags and 1L.inv() != 0L) refuse()
                    val archive = fields.vint()
                    if (archive and 0x1fL.inv() != 0L) refuse()
                    multi = archive and 1L != 0L
                    if (archive and 2L != 0L) {
                        if (!multi) refuse()
                        val number = fields.vint()
                        if (number !in 1L..9999L) refuse()
                        ordinal = number.toInt() + 1
                    }
                    if (fields.pos != fields.end) refuse()
                    sawMain = true
                }
                2L, 3L -> {
                    if (type == 2L) {
                        if (!multi && flags and 0x18L != 0L) refuse()
                        if (ordinal == 1 && flags and 8L != 0L) refuse()
                        splitAfter = flags and 16L != 0L
                    }
                    // Validate the mandatory file/service fields fit the
                    // non-extra header, without interpreting names or extras.
                    val fileFlags = fields.vint()
                    if (fileFlags and 0x0fL.inv() != 0L) refuse()
                    fields.vint() // unpacked size
                    fields.vint() // attributes
                    if (fileFlags and 2L != 0L) fields.pos += 4
                    if (fileFlags and 4L != 0L) fields.pos += 4
                    fields.vint() // compression info
                    fields.vint() // host OS
                    val name = fields.vint()
                    if (name !in 1L..(fields.end - fields.pos).toLong() ||
                        name != (fields.end - fields.pos).toLong()) refuse()
                }
                5L -> {
                    if (flags != 0L) refuse()
                    val end = fields.vint()
                    if (end !in 0L..1L || fields.pos != fields.end || next != input.size) refuse()
                    val more = end == 1L
                    if ((!multi && more) || (splitAfter && !more)) refuse()
                    return Result(Status.HEADER_CHAIN_OBSERVED, Format.RAR5, multi, ordinal, more)
                }
                else -> refuse()
            }
            pos = next
        }
        refuse()
    }
}
