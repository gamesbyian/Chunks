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
}
