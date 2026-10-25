package io.github.gamesbyian.chunks.model

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipArchiveInventoryTest {
    @get:Rule val folder = TemporaryFolder()

    private fun zip(vararg members: Pair<String, ByteArray>): File {
        val file = folder.newFile()
        ZipOutputStream(FileOutputStream(file)).use { out ->
            for ((name, data) in members) {
                out.putNextEntry(ZipEntry(name))
                out.write(data)
                out.closeEntry()
            }
        }
        return file
    }

    @Test fun actualLocalZipHeadersAreInspectedWithoutWritingOutputs() {
        val file = zip("Season/Episode01.txt" to "hello".toByteArray())
        val before = file.length()
        val result = ZipArchiveInventory.inspect(file)
        assertEquals(ZipPreflightStatus.BOUNDED_STREAM_CHECK_REQUIRED, result.status)
        assertEquals(listOf("Season/Episode01.txt"), result.names)
        assertEquals(1, result.fileCount)
        assertEquals(5L, result.declaredOutputBytes)
        assertEquals(before, file.length())
        assertEquals(1, folder.root.listFiles()?.size)
    }

    @Test fun actualZipWithTraversalEntryFailsClosed() {
        val file = zip("../outside.txt" to byteArrayOf(1, 2, 3))
        val result = ZipArchiveInventory.inspect(file)
        assertEquals(ZipPreflightStatus.BLOCKED, result.status)
        assertTrue(result.reasons.any { it.contains("unsafe") })
        assertFalse(File(folder.root.parentFile, "outside.txt").exists())
    }

    @Test fun realCentralDirectoryCaseAndUnicodeCollisionsFail() {
        val file = zip(
            "Media/Caf\u00e9.txt" to byteArrayOf(1),
            "media/Cafe\u0301.txt" to byteArrayOf(2)
        )
        val result = ZipArchiveInventory.inspect(file)
        assertEquals(ZipPreflightStatus.BLOCKED, result.status)
        assertTrue(result.reasons.any { it.contains("colliding") })
    }

    @Test fun highlyCompressibleRealPayloadIsFlaggedByDeclaredRatio() {
        val file = zip("inflate-me.bin" to ByteArray(500_000))
        val result = ZipArchiveInventory.inspect(file, ZipPreflightLimits(maxRatio = 10))
        assertEquals(ZipPreflightStatus.BLOCKED, result.status)
        assertTrue(result.reasons.any { it.contains("ratio") })
    }

    @Test fun malformedAndOversizedInputsAreRejectedWithoutDetails() {
        val invalid = folder.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(ZipPreflightStatus.BLOCKED, ZipArchiveInventory.inspect(invalid).status)
        val good = zip("file.txt" to byteArrayOf(1))
        val limit = ZipArchiveInventory.inspect(good, maxInputBytes = 1)
        assertEquals(ZipPreflightStatus.BLOCKED, limit.status)
        assertTrue(limit.reasons.all { !it.contains(good.absolutePath) })
    }

    @Test fun excessiveEntryCountIsRejectedEvenWithValidZipBytes() {
        val file = zip(
            "one.txt" to byteArrayOf(1),
            "two.txt" to byteArrayOf(2),
            "three.txt" to byteArrayOf(3)
        )
        val result = ZipArchiveInventory.inspect(file, ZipPreflightLimits(maxEntries = 2))
        assertEquals(ZipPreflightStatus.BLOCKED, result.status)
        assertTrue(result.reasons.any { it.contains("count") })
    }

    @Test fun limitArgumentsCannotBeZero() {
        val file = zip("normal.txt" to byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) {
            ZipArchiveInventory.inspect(file, maxInputBytes = 0)
        }
    }
    // Mutate only ZIP central-directory metadata, leaving actual member bytes intact.
    // java.util.zip.ZipOutputStream does not have a public Unix symlink API.
    private fun withUnixMemberType(file: File, mode: Int): File {
        val bytes = file.readBytes()
        val signature = listOf(0x50, 0x4b, 0x01, 0x02)
        val at = (0 until bytes.size - 46).firstOrNull { i ->
            signature.indices.all { j -> (bytes[i + j].toInt() and 255) == signature[j] }
        } ?: error("Test fixture missing central directory")
        bytes[at + 5] = 3 // Unix as the ZIP creator host OS
        bytes[at + 40] = (mode and 255).toByte()
        bytes[at + 41] = ((mode ushr 8) and 255).toByte()
        file.writeBytes(bytes)
        return file
    }

    @Test fun realZipUnixSymlinkAndSpecialMembersAreBlockedBeforePayloadReads() {
        val symlink = withUnixMemberType(zip("link.txt" to "../outside".toByteArray()), 0xa1ff)
        val inspected = ZipArchiveInventory.inspect(symlink)
        assertEquals(ZipPreflightStatus.BLOCKED, inspected.status)
        assertTrue(inspected.reasons.any { it.contains("symlink") })
        assertFalse(File(folder.root.parentFile, "outside").exists())

        val fifo = withUnixMemberType(zip("pipe.txt" to byteArrayOf(1)), 0x11a4)
        val other = ZipArchiveInventory.inspect(fifo)
        assertEquals(ZipPreflightStatus.BLOCKED, other.status)
        assertTrue(other.reasons.any { it.contains("special-file") })
    }

    @Test fun ordinaryUnixRegularFileMetadataDoesNotProduceFalseSymlinkAlert() {
        val regular = withUnixMemberType(zip("ordinary.txt" to byteArrayOf(1)), 0x81a4)
        val inspected = ZipArchiveInventory.inspect(regular)
        assertEquals(ZipPreflightStatus.BOUNDED_STREAM_CHECK_REQUIRED, inspected.status)
        assertEquals(listOf("ordinary.txt"), inspected.names)
    }

    @Test fun centralDirectoryMetadataMismatchBlocksRatherThanGuessingFileTypes() {
        val file = zip("test.txt" to byteArrayOf(7))
        val bytes = file.readBytes()
        val eocd = (bytes.size - 22 downTo 0).first {
            (bytes[it].toInt() and 255) == 0x50 &&
                (bytes[it + 1].toInt() and 255) == 0x4b &&
                (bytes[it + 2].toInt() and 255) == 5 &&
                (bytes[it + 3].toInt() and 255) == 6
        }
        bytes[eocd + 4] = 1 // claim multi-disk ZIP, unsupported by bounded preview
        file.writeBytes(bytes)
        val inspected = ZipArchiveInventory.inspect(file)
        assertEquals(ZipPreflightStatus.BLOCKED, inspected.status)
        assertTrue(inspected.reasons.any { it.contains("multi-volume") })
    }

    @Test fun individualZip64MemberSentinelsCannotBypassConservativeEocdCheck() {
        for (memberOffset in listOf(20, 24, 42)) {
            val file = zip("small.txt" to byteArrayOf(5))
            val bytes = file.readBytes()
            val at = (0 until bytes.size - 46).first { i ->
                listOf(0x50, 0x4b, 0x01, 0x02).indices.all { j ->
                    (bytes[i + j].toInt() and 255) == listOf(0x50, 0x4b, 0x01, 0x02)[j]
                }
            }
            for (j in 0 until 4) bytes[at + memberOffset + j] = 0xff.toByte()
            file.writeBytes(bytes)
            val result = ZipArchiveInventory.inspect(file)
            assertEquals(ZipPreflightStatus.BLOCKED, result.status)
            assertTrue(result.reasons.any { it.contains("ZIP64") })
        }
    }

    @Test fun splitMemberDiskReferenceIsRejectedEvenWhenOverallEocdSaysOneDisk() {
        val file = zip("small.txt" to byteArrayOf(5))
        val bytes = file.readBytes()
        val at = (0 until bytes.size - 46).first { i ->
            listOf(0x50, 0x4b, 0x01, 0x02).indices.all { j ->
                (bytes[i + j].toInt() and 255) == listOf(0x50, 0x4b, 0x01, 0x02)[j]
            }
        }
        bytes[at + 34] = 1
        file.writeBytes(bytes)
        val result = ZipArchiveInventory.inspect(file)
        assertEquals(ZipPreflightStatus.BLOCKED, result.status)
        assertTrue(result.reasons.any { it.contains("Split") })
    }

}
