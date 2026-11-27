package io.github.gamesbyian.chunks

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAddStorageObservationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun missingRootBorrowsOnlyVolumeNotParentContentsOrCap() {
        val parent = temp.newFolder("parent")
        File(parent, "foreign.bin").writeBytes(ByteArray(901))
        val root = File(parent, "Chunks Downloads")
        val target = File(root, "New folder")
        var capReads = 0
        val sampled = mutableListOf<File>()
        val observation = LocalAddStorageObservation.read(root, target, 600,
            capReader = { capReads++; 100L },
            freeReader = { sampled += it; 500L })
        assertEquals(0, capReads)
        assertEquals(listOf(parent), sampled)
        assertNull(observation.existingLogicalBytes)
        assertNull(observation.folderCapBytes)
        assertNull(observation.projectedCapExceeded)
        assertEquals(500L, observation.availableVolumeBytes)
        assertEquals(true, observation.additionalBytesExceedFree)
        assertFalse(root.exists())
        assertFalse(target.exists())
        assertEquals(901L, File(parent, "foreign.bin").length())
    }

    @Test fun existingTargetSamplesItsVolumeButBudgetRemainsRootScoped() {
        val root = temp.newFolder("root")
        val child = File(root, "Existing").apply { mkdir() }
        File(root, "existing.bin").writeBytes(ByteArray(400))
        File(child, "child.bin").writeBytes(ByteArray(200))
        val capReads = mutableListOf<File>()
        val volumeReads = mutableListOf<File>()
        val observation = LocalAddStorageObservation.read(root, child, 600,
            capReader = { capReads += it; 1_000L },
            freeReader = { volumeReads += it; 300L })
        assertEquals(listOf(root), capReads)
        assertEquals(listOf(child), volumeReads)
        assertEquals(600L, observation.existingLogicalBytes)
        assertEquals(1_200L, observation.projectedLogicalMaximum)
        assertEquals(true, observation.projectedCapExceeded)
        assertEquals(true, observation.additionalBytesExceedFree)
    }

    @Test fun missingChildUsesRootVolumeWithoutCreatingChild() {
        val root = temp.newFolder("root")
        val child = File(root, "New")
        val observation = LocalAddStorageObservation.read(root, child, 10,
            capReader = { assertEquals(root, it); 100L },
            freeReader = { assertEquals(root, it); 1_000L })
        assertEquals(0L, observation.existingLogicalBytes)
        assertEquals(10L, observation.projectedLogicalMaximum)
        assertEquals(false, observation.additionalBytesExceedFree)
        assertFalse(child.exists())
    }

    @Test fun symlinkNonDirectoryAndOutsideTargetCannotBorrowVolumeCapacity() {
        val root = temp.newFolder("root")
        val outside = temp.newFolder("outside")
        val link = File(root, "link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val regular = File(root, "file").apply { writeText("retained") }
        for (target in listOf(link, regular, outside, File(root, "../outside"))) {
            var freeReads = 0
            val observation = LocalAddStorageObservation.read(root, target, 10,
                capReader = { 100L },
                freeReader = { freeReads++; 999L })
            assertEquals(0, freeReads)
            assertNull(observation.availableVolumeBytes)
            assertNull(observation.additionalBytesExceedFree)
        }
        assertEquals("retained", regular.readText())
        assertTrue(outside.listFiles().orEmpty().isEmpty())
    }

    @Test fun failedCapacityReadDoesNotEraseIndependentlyMeasuredBudget() {
        val root = temp.newFolder("root")
        File(root, "file").writeBytes(ByteArray(4))
        val observation = LocalAddStorageObservation.read(root, root, 10,
            capReader = { 12L },
            freeReader = { error("unavailable") })
        assertEquals(14L, observation.projectedLogicalMaximum)
        assertEquals(true, observation.projectedCapExceeded)
        assertNull(observation.availableVolumeBytes)
    }
}
