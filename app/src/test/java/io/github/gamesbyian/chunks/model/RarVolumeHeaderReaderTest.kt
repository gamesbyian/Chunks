package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.media.inspection.ByteArrayMediaByteSource
import io.github.gamesbyian.chunks.media.inspection.MediaByteSource
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

/** Synthetic structural fixtures, not independently produced/extracted archives. */
class RarVolumeHeaderReaderTest {
    private val signature4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 7, 0)
    private val signature5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 7, 1, 0)
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun le(value: Long, width: Int) = ByteArray(width) { (value shr (8 * it)).toByte() }
    private fun crc(data: ByteArray) = CRC32().apply { update(data) }.value
    private fun vint(value: Long): ByteArray {
        require(value >= 0)
        var n = value
        val result = ArrayList<Byte>()
        do {
            val part = (n and 127L).toInt()
            n = n ushr 7
            result += (part or if (n != 0L) 128 else 0).toByte()
        } while (n != 0L)
        return result.toByteArray()
    }
    private fun header4(type: Int, flags: Int, body: ByteArray = byteArrayOf()): ByteArray {
        val data = bytes(type) + le(flags.toLong(), 2) + le((7 + body.size).toLong(), 2) + body
        return le(crc(data), 2) + data
    }
    private fun header5(type: Int, flags: Int, fields: ByteArray): ByteArray {
        val body = vint(type.toLong()) + vint(flags.toLong()) + fields
        val data = vint(body.size.toLong()) + body
        return le(crc(data), 4) + data
    }
    private fun main4(flags: Int) = header4(0x73, flags, ByteArray(6))
    private fun end4(next: Boolean, ordinal: Int) =
        header4(0x7b, 8 or if (next) 1 else 0, le((ordinal - 1).toLong(), 2))
    private fun main5(multi: Boolean, ordinal: Int) = header5(1, 0,
        vint((if (multi) 1L else 0L) or if (ordinal > 1) 2L else 0L) +
            if (ordinal > 1) vint((ordinal - 1).toLong()) else byteArrayOf())
    private fun end5(next: Boolean) = header5(5, 0, vint(if (next) 1 else 0))
    private fun file4(data: ByteArray, split: Int = 0): ByteArray {
        val body = le(data.size.toLong(), 4) + le(data.size.toLong(), 4) +
            bytes(3) + le(crc(data), 4) + ByteArray(4) + bytes(29, 0x30) +
            le(1, 2) + ByteArray(4) + bytes(65)
        return header4(0x74, 0x8000 or split, body) + data
    }
    private fun file5(data: ByteArray, split: Int = 0): ByteArray =
        header5(2, 2 or split, vint(data.size.toLong()) +
            vint(0) + vint(data.size.toLong()) + vint(0) + vint(0) + vint(1) + vint(1) + bytes(65)) + data

    private fun inspect(bytes: ByteArray) = RarVolumeHeaderReader.inspect(ByteArrayMediaByteSource(bytes))

    @Test fun rar4CrcBoundariesVolumeOrderAndTerminalFlags() {
        val first = signature4 + main4(0x0101) + file4(bytes(1, 2), 2) + end4(true, 1)
        val last = signature4 + main4(1) + file4(bytes(3, 4), 1) + end4(false, 2)
        assertEquals(RarVolumeHeaderReader.Result(
            RarVolumeHeaderReader.Status.HEADER_CHAIN_OBSERVED,
            RarVolumeHeaderReader.Format.RAR4, true, 1, true, splitAfter = true), inspect(first))
        assertEquals(RarVolumeHeaderReader.Result(
            RarVolumeHeaderReader.Status.HEADER_CHAIN_OBSERVED,
            RarVolumeHeaderReader.Format.RAR4, true, 2, false, splitBefore = true), inspect(last))
        val unknownOrdinal = signature4 + main4(1) + file4(bytes(5), 1) + header4(0x7b, 0)
        assertNull(inspect(unknownOrdinal).ordinal)
    }

    @Test fun rar5VolumeNumberIsZeroBasedOnDiskAndOnlyFirstOmitsIt() {
        val first = signature5 + main5(true, 1) + file5(bytes(1, 2), 16) + end5(true)
        val last = signature5 + main5(true, 2) + file5(bytes(3, 4), 8) + end5(false)
        assertEquals(RarVolumeHeaderReader.Result(
            RarVolumeHeaderReader.Status.HEADER_CHAIN_OBSERVED,
            RarVolumeHeaderReader.Format.RAR5, true, 1, true, splitAfter = true), inspect(first))
        assertEquals(RarVolumeHeaderReader.Result(
            RarVolumeHeaderReader.Status.HEADER_CHAIN_OBSERVED,
            RarVolumeHeaderReader.Format.RAR5, true, 2, false, splitBefore = true), inspect(last))
        assertEquals(RarVolumeHeaderReader.Result(
            RarVolumeHeaderReader.Status.HEADER_CHAIN_OBSERVED,
            RarVolumeHeaderReader.Format.RAR5, false, 1, false),
            inspect(signature5 + main5(false, 1) + file5(bytes(42)) + end5(false)))
    }

    @Test fun embeddedEndMarkerInsideDataIsNeverAcceptedAsTerminalHeader() {
        val apparentEnd = end5(false)
        val noEnd = signature5 + main5(false, 1) + file5(apparentEnd)
        assertEquals(RarVolumeHeaderReader.Status.UNCONFIRMED, inspect(noEnd).status)
        val good = noEnd + end5(false)
        assertEquals(RarVolumeHeaderReader.Status.HEADER_CHAIN_OBSERVED, inspect(good).status)
        // Data CRC is intentionally not verified by this structural reader.
        val altered = good.copyOf().also { it[it.size - apparentEnd.size - 1] = 99 }
        assertEquals(RarVolumeHeaderReader.Status.HEADER_CHAIN_OBSERVED, inspect(altered).status)
    }

    @Test fun corruptHeaderTruncationUnexpectedTrailingDataAndContradictionsRefuse() {
        val valid4 = signature4 + main4(0) + file4(bytes(1)) + end4(false, 1)
        val valid5 = signature5 + main5(false, 1) + file5(bytes(1)) + end5(false)
        for (valid in listOf(valid4, valid5)) {
            for (bad in listOf(valid.copyOf(valid.size - 1), valid + bytes(7),
                valid.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() })) {
                assertEquals(RarVolumeHeaderReader.Status.UNCONFIRMED, inspect(bad).status)
                assertNull(inspect(bad).nextVolume)
            }
        }
        for (bad in listOf(signature4 + main4(0) + end4(true, 1),
            signature4 + main4(0x0101) + end4(false, 2),
            signature5 + main5(false, 2) + end5(false),
            signature5 + main5(true, 1) + file5(bytes(1), 16) + end5(false),
            signature5 + main5(false, 1) + header5(99, 0, bytes(0)) + end5(false))) {
            assertEquals(RarVolumeHeaderReader.Status.UNCONFIRMED, inspect(bad).status)
        }
    }

    @Test fun encryptedHeadersAndUnrecognizedBytesNeverProvideReadyFacts() {
        assertEquals(RarVolumeHeaderReader.Status.ENCRYPTED_HEADERS,
            inspect(signature4 + main4(0x80)).status)
        assertEquals(RarVolumeHeaderReader.Status.ENCRYPTED_HEADERS,
            inspect(signature5 + header5(4, 0, bytes(0, 0) + ByteArray(17))).status)
        assertEquals(RarVolumeHeaderReader.Status.UNCONFIRMED,
            inspect(signature5 + header5(4, 0, bytes(0))).status)
        assertEquals(RarVolumeHeaderReader.Status.UNRECOGNIZED, inspect(bytes(1, 2, 3)).status)
    }

    @Test fun boundedReadsSkipPackedDataAndRejectOversizedHeaderAndShortProviderReplies() {
        val packed = ByteArray(5 * 1024 * 1024)
        val archive = signature5 + main5(false, 1) + file5(packed) + end5(false)
        var requested = 0L
        val source = object : MediaByteSource {
            private val real = ByteArrayMediaByteSource(archive)
            override val size = real.size
            override fun read(offset: Long, length: Int): ByteArray {
                requested += length
                return real.read(offset, length)
            }
        }
        assertEquals(RarVolumeHeaderReader.Status.HEADER_CHAIN_OBSERVED,
            RarVolumeHeaderReader.inspect(source).status)
        assertTrue("Header scan must not read packed bytes", requested < 1024)
        val huge = signature5 + header5(1, 1, vint(0) + vint(0) + ByteArray(300_000))
        assertEquals(RarVolumeHeaderReader.Status.UNCONFIRMED, inspect(huge).status)
        val short = object : MediaByteSource {
            private val real = ByteArrayMediaByteSource(archive)
            override val size = real.size
            override fun read(offset: Long, length: Int) =
                real.read(offset, if (offset == 0L) length else (length - 1).coerceAtLeast(0))
        }
        assertEquals(RarVolumeHeaderReader.Status.UNCONFIRMED,
            RarVolumeHeaderReader.inspect(short).status)
    }

    @Test fun excessiveBlockCountAndUnterminatedSizeVintRefuse() {
        val repeated = file5(byteArrayOf())
        val many = java.io.ByteArrayOutputStream().apply {
            write(signature5); write(main5(false, 1))
            repeat(RarVolumeHeaderReader.MAX_BLOCKS) { write(repeated) }
            write(end5(false))
        }.toByteArray()
        assertEquals(RarVolumeHeaderReader.Status.UNCONFIRMED, inspect(many).status)
        assertEquals(RarVolumeHeaderReader.Status.UNCONFIRMED,
            inspect(signature5 + ByteArray(4) + bytes(128, 128, 128, 0)).status)
    }
}
