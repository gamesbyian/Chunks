package io.github.gamesbyian.chunks.rpc

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `InputStream.readNBytes(int)` exists on Android only from API 33; minSdk is 29
 * without core-library desugaring, where it throws NoSuchMethodError. Android Lint
 * flagged four remote RPC reads (capability probe, ruTorrent delete-data acceptance,
 * directory listing, File Manager); [readAtMost] replaces them with the same contract.
 */
class BoundedReadTest {
    private fun stream(size: Int) = ByteArrayInputStream(ByteArray(size) { (it % 251).toByte() })

    /** Hands out one byte per read(), like a slow network stream. */
    private class Trickle(private val bytes: ByteArray) : InputStream() {
        private var index = 0
        override fun read(): Int = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (index >= bytes.size) return -1
            b[off] = bytes[index++]
            return 1
        }
    }

    @Test fun readsTheWholeShorterStreamAndStopsAtEnd() {
        assertEquals(10, stream(10).readAtMost(150_001).size)
        assertEquals(0, stream(0).readAtMost(16_385).size)
    }

    @Test fun neverReadsBeyondTheLimitSoCallersCanDetectOversize() {
        val bytes = stream(200_000).readAtMost(150_001)
        assertEquals(150_001, bytes.size)
        assertTrue("A caller's size > max check still fires", bytes.size > 150_000)
        assertEquals(16_384, stream(16_384).readAtMost(16_385).size)
    }

    @Test fun assemblesPartialReadsInOrderLikeReadNBytes() {
        val original = ByteArray(20_000) { (it * 7).toByte() }
        assertArrayEquals(original.copyOf(12_345), Trickle(original).readAtMost(12_345))
        assertArrayEquals(original, Trickle(original).readAtMost(30_000))
        assertEquals(0, Trickle(original).readAtMost(0).size)
    }

    @Test fun productionSourcesNeverCallApi33OnlyStreamReads() {
        // Lint is not a CI gate; keep the minSdk 29 crash from returning.
        // java.nio.file.Files.readAllBytes(path) is API 26 and allowed.
        val forbidden = Regex("""(?<!Files)\.(readNBytes|readAllBytes|transferTo)\(""")
        val root = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readLines().withIndex()
                .filter { (_, line) -> forbidden.containsMatchIn(line) && !line.trimStart().startsWith("//") &&
                    !line.trimStart().startsWith("*") }
                .map { (n, _) -> "${file.name}:${n + 1}" } }
            .toList()
        assertTrue("API 33-only InputStream calls under minSdk 29: $offenders", offenders.isEmpty())
    }
}
