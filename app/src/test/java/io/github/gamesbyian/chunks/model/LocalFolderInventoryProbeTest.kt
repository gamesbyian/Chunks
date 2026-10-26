package io.github.gamesbyian.chunks.model

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFolderInventoryProbeTest {
    @Test fun countsNestedFilesWithoutTouchingTheirBytes() {
        val root = Files.createTempDirectory("chunks-inventory").toFile()
        try {
            val nested = root.resolve("series/season").apply { mkdirs() }
            val first = root.resolve("first.bin").apply { writeBytes(ByteArray(13) { 7 }) }
            nested.resolve("second.bin").writeBytes(ByteArray(17) { 3 })
            val result = LocalFolderInventoryProbe.measure(root)
            assertTrue(result.complete)
            assertEquals(2, result.regularFiles)
            assertEquals(30L, result.logicalBytes)
            assertEquals(13L, first.length())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun refusesToFollowLinksOutsideTheAuthorizedFolder() {
        val root = Files.createTempDirectory("chunks-owned")
        val foreign = Files.createTempDirectory("chunks-foreign")
        try {
            Files.write(foreign.resolve("secret.bin"), ByteArray(4096))
            Files.write(root.resolve("owned.bin"), ByteArray(7))
            Files.createSymbolicLink(root.resolve("escape"), foreign)
            val result = LocalFolderInventoryProbe.measure(root.toFile())
            assertFalse(result.complete)
            assertEquals(1, result.regularFiles)
            assertEquals(7L, result.logicalBytes)
        } finally {
            root.toFile().deleteRecursively()
            foreign.toFile().deleteRecursively()
        }
    }

    @Test fun exceededEntryLimitIsReportedAsPartialRatherThanComplete() {
        val root = Files.createTempDirectory("chunks-bounded").toFile()
        try {
            repeat(5) { index -> root.resolve("part-$index").writeBytes(ByteArray(10)) }
            val result = LocalFolderInventoryProbe.measure(root, maxEntries = 3)
            assertFalse(result.complete)
            assertEquals(2, result.regularFiles)
            assertEquals(20L, result.logicalBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun missingFolderAndSymbolicRootReturnUnknownNotZero() {
        val root = Files.createTempDirectory("chunks-link")
        try {
            val missing = LocalFolderInventoryProbe.measure(root.resolve("absent").toFile())
            assertNull(missing.logicalBytes)
            assertFalse(missing.complete)
            Files.createSymbolicLink(root.resolve("linked-root"), root)
            val linked = LocalFolderInventoryProbe.measure(root.resolve("linked-root").toFile())
            assertNull(linked.logicalBytes)
            assertFalse(linked.complete)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
