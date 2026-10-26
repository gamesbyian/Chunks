package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAllocatedFolderProbeInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun hardlinkedFilesCountAllocationOnceAndExposeOutsideReferences() {
        val root = File(context.filesDir, "alloc-probe-hardlinks").apply { deleteRecursively(); mkdirs() }
        val outside = File(context.filesDir, "alloc-probe-outside").apply { deleteRecursively(); mkdirs() }
        try {
            val file = File(root, "first.bin").apply { writeBytes(ByteArray(4096) { 5 }) }
            try {
                Files.createLink(File(root, "second.bin").toPath(), file.toPath())
                Files.createLink(File(outside, "external.bin").toPath(), file.toPath())
            } catch (denied: java.nio.file.AccessDeniedException) {
                // Android app SELinux policy can deny hardlink creation even
                // within app-private storage. The pure JVM test exercises
                // the actual shared accounting implementation in that case.
                Assume.assumeNoException(denied)
            }
            val inventory = LocalAllocatedFolderProbe.measure(root)
            assertTrue(inventory.complete)
            assertEquals(2, inventory.regularPaths)
            assertEquals(1, inventory.uniqueInodes)
            assertEquals(4096L, inventory.logicalBytes)
            assertTrue(inventory.allocatedBytes != null && inventory.allocatedBytes!! >= 4096L)
            assertTrue("A third physical hardlink is outside this root", inventory.externalHardlinkPossible)
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test fun sparseFileReportsLogicalAndAllocatedSeparately() {
        val root = File(context.filesDir, "alloc-probe-sparse").apply { deleteRecursively(); mkdirs() }
        try {
            RandomAccessFile(File(root, "sparse.bin"), "rw").use { it.setLength(32L * 1024 * 1024) }
            val inventory = LocalAllocatedFolderProbe.measure(root)
            assertTrue(inventory.complete)
            assertEquals(32L * 1024 * 1024, inventory.logicalBytes)
            assertNotNull(inventory.allocatedBytes)
            assertTrue(inventory.allocatedBytes!! <= inventory.logicalBytes!!)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun symlinkAndEntryCapMakeInventoryExplicitlyIncomplete() {
        val root = File(context.filesDir, "alloc-probe-incomplete").apply { deleteRecursively(); mkdirs() }
        try {
            repeat(5) { File(root, "file-$it.bin").writeBytes(ByteArray(10)) }
            val limited = LocalAllocatedFolderProbe.measure(root, maxEntries = 3)
            assertFalse(limited.complete)
            assertEquals(2, limited.regularPaths)
            val escape = File(root, "escape")
            Files.createSymbolicLink(escape.toPath(), context.filesDir.toPath())
            val scanned = LocalAllocatedFolderProbe.measure(root)
            assertFalse(scanned.complete)
            assertEquals(5, scanned.regularPaths)
        } finally {
            File(root, "escape").delete()
            root.deleteRecursively()
        }
    }

    @Test fun missingRootDoesNotPretendZeroAllocation() {
        val report = LocalAllocatedFolderProbe.measure(File(context.filesDir, "alloc-probe-absent"))
        assertFalse(report.complete)
        assertNull(report.allocatedBytes)
        assertNull(report.logicalBytes)
    }
}
