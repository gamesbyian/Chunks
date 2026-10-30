package io.github.gamesbyian.chunks.engine

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class LocalResumeDirectoryInventoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun newPrivateStateDirectoryIsCreatedAndEnumeratedEmpty() {
        val directory = File(temp.root, "missing/transfer-state")
        assertEquals(emptyList<File>(), LocalResumeDirectoryInventory.prepare(directory))
        assertTrue(directory.isDirectory)
    }

    @Test fun preExistingRegularFileCannotBeMisreadAsEmptyStore() {
        val file = File(temp.root, "not-a-directory")
        file.writeText("must remain untouched")
        try {
            LocalResumeDirectoryInventory.prepare(file)
            fail("Must refuse a non-directory state path")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("unavailable"))
        }
        assertEquals("must remain untouched", file.readText())
    }

    @Test fun failedDirectoryListingIsDifferentFromLegitimateEmptyStore() {
        val directory = temp.newFolder("resume-listing-failure")
        try {
            LocalResumeDirectoryInventory.prepare(directory) { null }
            fail("A failed listing cannot be treated as an empty torrent catalog")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("cannot be listed"))
        }
        assertTrue(directory.isDirectory)
    }

    @Test fun exceptionDuringListingAlsoRefusesStartupWithoutMutation() {
        val directory = temp.newFolder("resume-access-loss")
        val saved = File(directory, "a".repeat(40) + ".resume")
        saved.writeBytes(byteArrayOf(1, 2, 3))
        try {
            LocalResumeDirectoryInventory.prepare(directory) { throw IOException("storage unavailable") }
            fail("Cannot continue with an unknown resume inventory")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("cannot be listed"))
        }
        assertEquals(listOf<Byte>(1, 2, 3), saved.readBytes().toList())
    }

    @Test fun validEntriesAreEnumeratedInDeterministicOrderWithoutDeletion() {
        val directory = temp.newFolder("resume-order")
        File(directory, "b.resume").writeText("b")
        File(directory, "a.resume").writeText("a")
        File(directory, "z.resume.tmp").writeText("incomplete")
        assertEquals(
            listOf("a.resume", "b.resume", "z.resume.tmp"),
            LocalResumeDirectoryInventory.prepare(directory).map { it.name }
        )
        assertTrue(File(directory, "z.resume.tmp").isFile)
    }
}
