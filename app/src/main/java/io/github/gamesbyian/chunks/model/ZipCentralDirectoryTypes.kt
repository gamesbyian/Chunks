package io.github.gamesbyian.chunks.model

import java.io.File
import java.io.RandomAccessFile

/**
 * Metadata-only ZIP member-type check. java.util.zip.ZipEntry does not expose
 * central-directory external attributes, which can mark apparently ordinary
 * files as Unix symlinks or special files. This scanner never reads payloads.
 *
 * ZIP64, split archives, encrypted members and malformed directory metadata
 * are deliberately unsupported for this conservative local preview.
 */
internal object ZipCentralDirectoryTypes {
    fun rejectReason(file: File, maxEntries: Int): String? {
        require(maxEntries > 0)
        return RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val tailLength = minOf(length, 22L + 65535L).toInt()
            if (tailLength < 22) return@use "ZIP end record is missing"
            val tail = ByteArray(tailLength)
            val start = length - tailLength
            input.seek(start)
            input.readFully(tail)

            // An EOCD is valid only if its advertised comment ends at EOF.
            var endOffset = -1
            for (i in tail.size - 22 downTo 0) {
                if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tail.size) {
                    endOffset = i
                    break
                }
            }
            if (endOffset < 0) return@use "ZIP end record is invalid"

            val disk = u16(tail, endOffset + 4)
            val directoryDisk = u16(tail, endOffset + 6)
            val onDiskCount = u16(tail, endOffset + 8)
            val count = u16(tail, endOffset + 10)
            val directorySize = u32(tail, endOffset + 12)
            val directoryOffset = u32(tail, endOffset + 16)
            if (disk != 0 || directoryDisk != 0 || onDiskCount != count)
                return@use "Split or multi-volume ZIP archives are not supported"
            if (count == 0xffff || directorySize == 0xffffffffL ||
                directoryOffset == 0xffffffffL)
                return@use "ZIP64 metadata needs a separate verified inspector"
            if (count > maxEntries) return@use "ZIP entry count exceeds preview limit"

            val directoryEnd = directoryOffset + directorySize
            if (directoryEnd > start + endOffset || directoryEnd > length ||
                directoryEnd < directoryOffset)
                return@use "ZIP central directory extent is invalid"
            var cursor = directoryOffset
            val fixed = ByteArray(46)
            repeat(count) {
                if (cursor + fixed.size > directoryEnd)
                    return@use "ZIP central directory entry is incomplete"
                input.seek(cursor)
                input.readFully(fixed)
                if (u32(fixed, 0) != 0x02014b50L)
                    return@use "ZIP central directory entry signature is invalid"
                // ZIP64 can appear on individual members even when the EOCD
                // contains normal 16/32-bit totals. Reject sentinel fields
                // until a separately bounded ZIP64 extra-field parser exists.
                if (u32(fixed, 20) == 0xffffffffL ||
                    u32(fixed, 24) == 0xffffffffL ||
                    u32(fixed, 42) == 0xffffffffL ||
                    u16(fixed, 34) == 0xffff)
                    return@use "ZIP64 member metadata needs a separate verified inspector"
                if (u16(fixed, 34) != 0)
                    return@use "Split ZIP member disk references are unsupported"
                val flags = u16(fixed, 8)
                if ((flags and 1) != 0)
                    return@use "Encrypted ZIP entries are not supported by local preflight"
                val type = (u32(fixed, 38) ushr 16).toInt() and 0xf000
                if (type == 0xa000)
                    return@use "ZIP symlink member is unsafe for extraction"
                if (type != 0 && type != 0x8000 && type != 0x4000)
                    return@use "ZIP special-file member type is unsupported"
                val variable = u16(fixed, 28) + u16(fixed, 30) + u16(fixed, 32)
                cursor += fixed.size + variable
                if (cursor > directoryEnd)
                    return@use "ZIP central directory member extent is invalid"
            }
            if (cursor != directoryEnd)
                return@use "ZIP central directory contains unrecognized trailing metadata"
            null
        }
    }

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Long =
        (u16(bytes, at).toLong() and 65535L) or (u16(bytes, at + 2).toLong() shl 16)
}
