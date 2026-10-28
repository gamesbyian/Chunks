package io.github.gamesbyian.chunks

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalPlaceWriteProbeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun writableFolderPassesAndKeepsNoProbeFiles() {
        val folder = temp.newFolder("Downloads")
        File(folder, "existing.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(LocalPlaceWriteProbe.verify(folder))
        assertEquals(listOf("existing.bin"), folder.list()!!.toList())
        assertTrue(File(folder, "existing.bin").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test fun missingRelativeSymlinkedAndReadOnlyFoldersAreRefused() {
        assertNotNull(LocalPlaceWriteProbe.verify(File(temp.root, "absent")))
        assertNotNull(LocalPlaceWriteProbe.verify(File("relative")))
        val real = temp.newFolder("real")
        val link = File(temp.root, "link").toPath()
        Files.createSymbolicLink(link, real.toPath())
        assertNotNull(LocalPlaceWriteProbe.verify(link.toFile()))
        val readOnly = temp.newFolder("read-only")
        readOnly.setWritable(false)
        try {
            // Root ignores permission bits; only assert where the OS enforces them.
            if (!File(readOnly, "x").let { runCatching { it.createNewFile() }.getOrDefault(false) })
                assertNotNull(LocalPlaceWriteProbe.verify(readOnly))
        } finally {
            readOnly.setWritable(true)
            File(readOnly, "x").delete()
        }
    }

    @Test fun ensureChildCreatesOneLevelIdempotentlyAndRefusesRedirection() {
        val parent = temp.newFolder("Download")
        val first = LocalPlaceWriteProbe.ensureChild(parent, "Chunks Downloads")
        assertNotNull(first)
        assertEquals(first, LocalPlaceWriteProbe.ensureChild(parent, "Chunks Downloads"))
        assertTrue(first!!.isDirectory)

        assertNull(LocalPlaceWriteProbe.ensureChild(parent, "a/b"))
        assertNull(LocalPlaceWriteProbe.ensureChild(parent, ".."))
        assertNull(LocalPlaceWriteProbe.ensureChild(parent, ""))
        assertNull(LocalPlaceWriteProbe.ensureChild(File(temp.root, "absent"), "Chunks Downloads"))

        File(parent, "file").writeText("not a folder")
        assertNull(LocalPlaceWriteProbe.ensureChild(parent, "file"))

        val elsewhere = temp.newFolder("elsewhere")
        Files.createSymbolicLink(File(parent, "planted").toPath(), elsewhere.toPath())
        assertNull(LocalPlaceWriteProbe.ensureChild(parent, "planted"))
        assertTrue(elsewhere.list()!!.isEmpty())
    }
}
