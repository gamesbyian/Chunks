package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalOwnedAddFolderInstrumentedTest {
    @Test fun appOwnedDownloadRootAcceptsExistingNonemptyChosenSubfolder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = requireNotNull(LocalEngineHolder.appDownloads(context))
        val label = "chunks-folder-test-" + System.nanoTime().toString(16)
        val proposed = requireNotNull(LocalOwnedAddFolder.proposed(root, label))
        try {
            assertFalse(proposed.exists()) // typing the name does not write anything
            val chosen = LocalOwnedAddFolder.prepare(root, label)
            assertEquals(proposed.canonicalFile, chosen?.canonicalFile)
            assertTrue(proposed.isDirectory)
            assertEquals(root.canonicalFile, proposed.canonicalFile.parentFile)
            java.io.File(proposed, "test-fixture.dat").writeText("keep")
            assertEquals(proposed.canonicalFile,
                LocalOwnedAddFolder.prepare(root, label)?.canonicalFile)
            assertEquals("keep", java.io.File(proposed, "test-fixture.dat").readText())
        } finally {
            // This test owns precisely this fresh isolated disposable directory.
            java.io.File(proposed, "test-fixture.dat").delete()
            proposed.delete()
        }
    }

    @Test fun aFolderNameCannotLeaveTheLocalAppOwnedDownloadRoot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = requireNotNull(LocalEngineHolder.appDownloads(context))
        assertNull(LocalOwnedAddFolder.proposed(root, "../remote"))
        assertNull(LocalOwnedAddFolder.prepare(root, "../remote"))
        assertEquals(root, LocalOwnedAddFolder.prepare(root, ""))
    }
}
