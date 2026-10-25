package io.github.gamesbyian.chunks.model

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipPayloadVerifierTest {
    @get:Rule val temp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val file = temp.newFile()
        ZipOutputStream(FileOutputStream(file)).use { output ->
            for ((name, bytes) in entries) {
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return file
    }

    @Test fun actuallyInflatesAndChecksCrcWithoutWritingAnyOutput() {
        val file = zip(
            "media/episode.txt" to "An entirely local sample".toByteArray(),
            "notes.txt" to "notes".toByteArray()
        )
        val before = file.readBytes()
        val result = ZipPayloadVerifier.verify(file)
        assertEquals(ZipPayloadStatus.VERIFIED_READ_ONLY, result.status)
        assertEquals(2, result.verifiedFiles)
        assertEquals(29L, result.actualOutputBytes)
        assertNull(result.reason)
        assertArrayEquals(before, file.readBytes())
        assertEquals(1, temp.root.listFiles()?.size)
    }

    @Test fun actualStreamingByteBudgetRejectsExcessBeforeAnyExtraction() {
        val file = zip("file.bin" to ByteArray(200) { 1 })
        val result = ZipPayloadVerifier.verify(file, maxExpandedBytes = 64)
        assertEquals(ZipPayloadStatus.BLOCKED, result.status)
        assertNull(result.actualOutputBytes)
        assertTrue(result.reason.orEmpty().contains("budget"))
        assertEquals(1, temp.root.listFiles()?.size)
    }

    @Test fun tamperedCentralDirectoryCrcFailsClosedEvenIfEntryCanInflate() {
        val file = zip("file.txt" to "payload".toByteArray())
        val bytes = file.readBytes()
        val central = (0 until bytes.size - 46).first { i ->
            (bytes[i].toInt() and 255) == 0x50 &&
                (bytes[i + 1].toInt() and 255) == 0x4b &&
                (bytes[i + 2].toInt() and 255) == 0x01 &&
                (bytes[i + 3].toInt() and 255) == 0x02
        }
        // Corrupt the central CRC only, retaining a structurally readable ZIP.
        for (i in 16..19) bytes[central + i] = 0
        file.writeBytes(bytes)
        val result = ZipPayloadVerifier.verify(file)
        assertEquals(ZipPayloadStatus.BLOCKED, result.status)
        assertTrue(result.reason.orEmpty().contains("CRC"))
    }

    @Test fun dangerousPathsAndExpansionRatiosAreRejectedBeforeStreaming() {
        val traversal = zip("../escape.txt" to "bad".toByteArray())
        val path = ZipPayloadVerifier.verify(traversal)
        assertEquals(ZipPayloadStatus.BLOCKED, path.status)
        assertTrue(path.reason.orEmpty().contains("unsafe"))
        assertFalse(File(temp.root.parentFile, "escape.txt").exists())

        val compressed = zip("compressible.bin" to ByteArray(100_000))
        val ratio = ZipPayloadVerifier.verify(compressed,
            limits = ZipPreflightLimits(maxRatio = 10))
        assertEquals(ZipPayloadStatus.BLOCKED, ratio.status)
        assertTrue(ratio.reason.orEmpty().contains("ratio"))
    }

    @Test fun incompleteArchivesAndInvalidLimitsAreNotTreatedAsSuccess() {
        val file = temp.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(ZipPayloadStatus.BLOCKED, ZipPayloadVerifier.verify(file).status)
        assertThrows(IllegalArgumentException::class.java) {
            ZipPayloadVerifier.verify(file, maxExpandedBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ZipPayloadVerifier.verify(file, maxExpandedBytes = 1024L * 1024 * 1024 + 1)
        }
    }
}
