package io.github.gamesbyian.chunks

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalOwnedAddFolderTest {
    @get:Rule val temp = TemporaryFolder()
    private fun root() = temp.newFolder("chunks-downloads")

    @Test fun defaultDoesNotCreateAnythingOrRequireTypedFolder() {
        val root = root()
        assertEquals(root, LocalOwnedAddFolder.proposed(root, " "))
        assertEquals(root, LocalOwnedAddFolder.prepare(root, ""))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun createsOnlyExplicitChildWhenConfirmedAndAcceptsEmptyExistingChild() {
        val root = root()
        val path = LocalOwnedAddFolder.proposed(root, "Season 01")!!
        assertFalse(path.exists())
        assertEquals(path, LocalOwnedAddFolder.prepare(root, "Season 01"))
        assertTrue(path.isDirectory)
        assertEquals(path, LocalOwnedAddFolder.prepare(root, "Season 01"))
    }

    @Test fun refusesNonemptyOrFileCollisionsBeforeEngineAdd() {
        val root = root()
        val occupied = java.io.File(root, "Occupied")
        assertTrue(occupied.mkdir())
        assertTrue(java.io.File(occupied, "existing.dat").writeText("leave alone").let { true })
        assertNull(LocalOwnedAddFolder.prepare(root, "Occupied"))
        assertEquals("leave alone", java.io.File(occupied, "existing.dat").readText())
        assertTrue(java.io.File(root, "file").writeText("x").let { true })
        assertNull(LocalOwnedAddFolder.prepare(root, "file"))
    }

    @Test fun traversalBidiControlsAndOversizedNamesAreRejected() {
        val root = root()
        for (bad in listOf("../parent", "..", ".hidden", "ends.", "a/b",
            "a\\b", "a\nname", "a\u202Ename", "x".repeat(121))) {
            assertNull(bad, LocalOwnedAddFolder.proposed(root, bad))
            assertNull(bad, LocalOwnedAddFolder.prepare(root, bad))
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun symlinkedChildCannotRedirectWriteOutsideOwnedRoot() {
        val root = root()
        val outside = temp.newFolder("outside")
        val link = java.io.File(root, "redirect").toPath()
        try {
            Files.createSymbolicLink(link, outside.toPath())
        } catch (_: UnsupportedOperationException) { return }
        assertNull(LocalOwnedAddFolder.prepare(root, "redirect"))
        assertTrue(outside.listFiles().orEmpty().isEmpty())
    }
}
