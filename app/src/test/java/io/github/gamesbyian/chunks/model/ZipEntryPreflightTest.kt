package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class ZipEntryPreflightTest {
    private fun file(name: String, size: Long? = 100, compressed: Long? = 50) =
        ZipMemberDescriptor(name, false, size, compressed)
    private fun directory(name: String) = ZipMemberDescriptor(name, true, 0, 0)

    @Test fun ordinaryZipRequiresActualStreamingVerification() {
        val result = ZipEntryPreflight.inspect(listOf(
            directory("Season/"),
            file("Season/Episode01.mkv", 900, 800),
            file("Season/Episode02.mkv", 700, 650)
        ))
        assertEquals(ZipPreflightStatus.BOUNDED_STREAM_CHECK_REQUIRED, result.status)
        assertEquals(1600L, result.declaredOutputBytes)
        assertEquals(2, result.fileCount)
        assertTrue(result.reasons.isEmpty())
    }

    @Test fun traversalDriveBackslashAndAbsolutePathsNeverPass() {
        for (path in listOf(
            "../outside.txt", "a/../../escape.txt", "/root.txt",
            "C:/Windows/file.txt", "a\\nested.txt", "./file.txt",
            "a//file.txt", "a/./file.txt", "a/../file.txt", "a/ ",
            "name. ", "a\u202Efile.txt", "a\nfile.txt"
        )) {
            val result = ZipEntryPreflight.inspect(listOf(file(path)))
            assertEquals("Unsafe path: $path", ZipPreflightStatus.BLOCKED, result.status)
        }
    }

    @Test fun nfcAndCaseFoldCollisionsAreBlocked() {
        val duplicate = ZipEntryPreflight.inspect(listOf(
            file("Media/Caf\u00e9.txt"), file("media/Cafe\u0301.txt")
        ))
        assertEquals(ZipPreflightStatus.BLOCKED, duplicate.status)
        assertTrue(duplicate.reasons.any { it.contains("colliding") })
    }

    @Test fun entryFileCannotAlsoBeParentDirectory() {
        val result = ZipEntryPreflight.inspect(listOf(file("Folder"), file("Folder/file.mp4")))
        assertEquals(ZipPreflightStatus.BLOCKED, result.status)
        assertTrue(result.reasons.any { it.contains("parent directory") })
    }

    @Test fun declaredZipBombAndZeroCompressedDataAreBlocked() {
        assertEquals(ZipPreflightStatus.BLOCKED,
            ZipEntryPreflight.inspect(listOf(file("bad.zip", 1_000_000, 1))).status)
        assertEquals(ZipPreflightStatus.BLOCKED,
            ZipEntryPreflight.inspect(listOf(file("zero.zip", 1, 0))).status)
    }

    @Test fun byteTotalsAndCountLimitUseSafeArithmetic() {
        val limits = ZipPreflightLimits(maxEntries = 1, maxDeclaredBytes = 1000)
        assertEquals(ZipPreflightStatus.BLOCKED, ZipEntryPreflight.inspect(
            listOf(file("first"), file("second")), limits).status)
        assertEquals(ZipPreflightStatus.BLOCKED, ZipEntryPreflight.inspect(
            listOf(file("a", Long.MAX_VALUE, null), file("b", 100, null))).status)
    }

    @Test fun unknownSizesDoNotBecomeFalseZeroEstimates() {
        val result = ZipEntryPreflight.inspect(listOf(file("unknown.mkv", null, null)))
        assertEquals(ZipPreflightStatus.BOUNDED_STREAM_CHECK_REQUIRED, result.status)
        assertNull(result.declaredOutputBytes)
    }

    @Test fun depthLimitAndBadNumbersAreRejected() {
        val result = ZipEntryPreflight.inspect(listOf(file("a/b/c/file")),
            ZipPreflightLimits(maxDepth = 3))
        assertEquals(ZipPreflightStatus.BLOCKED, result.status)
        assertEquals(ZipPreflightStatus.BLOCKED,
            ZipEntryPreflight.inspect(listOf(file("bad", -1, 2))).status)
        assertThrows(IllegalArgumentException::class.java) {
            ZipPreflightLimits(maxEntries = 0)
        }
    }
}
