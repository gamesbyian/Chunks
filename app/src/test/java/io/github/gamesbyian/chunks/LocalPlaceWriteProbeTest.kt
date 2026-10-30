package io.github.gamesbyian.chunks

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
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

    @Test fun occupiedTemporaryProbeIsNeverOpenedForWriteOrDeleted() {
        val root = temp.newFolder("probe-collision")
        val probe = File(root, ".chunks-write-probe-0000000000000000.tmp")
        val unrelated = byteArrayOf(4, 8, 15, 16, 23, 42)
        probe.writeBytes(unrelated)
        val predictable = object : java.util.Random() {
            override fun nextBytes(bytes: ByteArray) { bytes.fill(0) }
        }
        assertNotNull(LocalPlaceWriteProbe.verify(root, predictable))
        assertTrue(probe.isFile)
        assertTrue(unrelated.contentEquals(probe.readBytes()))
        assertFalse(File(root, ".chunks-write-probe-0000000000000000.ok").exists())
    }

    @Test fun occupiedRenameTargetRemainsIntactAndNoTemporaryProbeIsLeft() {
        val root = temp.newFolder("rename-target-collision")
        val other = File(root, ".chunks-write-probe-0000000000000000.ok")
        val bytes = byteArrayOf(2, 7, 1, 8, 2, 8)
        other.writeBytes(bytes)
        val predictable = object : java.util.Random() {
            override fun nextBytes(buffer: ByteArray) { buffer.fill(0) }
        }
        assertNotNull(LocalPlaceWriteProbe.verify(root, predictable))
        assertTrue(bytes.contentEquals(other.readBytes()))
        assertFalse(File(root, ".chunks-write-probe-0000000000000000.tmp").exists())
    }

    @Test fun destinationCreatedAfterFinalSurveyCannotBeDeletedAsOurProbe() {
        val root = temp.newFolder("interleaved-destination")
        val planted = File(root, ".chunks-write-probe-0000000000000000.ok")
        val original = byteArrayOf(5, 8, 13, 21)
        val predictable = object : java.util.Random() {
            override fun nextBytes(bytes: ByteArray) { bytes.fill(0) }
        }
        val outcome = LocalPlaceWriteProbe.verify(root, predictable,
            beforeRename = { planted.writeBytes(original) })
        assertNotNull("A competing destination must fail the probe", outcome)
        assertTrue("The competing name must remain occupied", planted.isFile)
        assertTrue("A rename collision must leave foreign bytes unchanged",
            original.contentEquals(planted.readBytes()))
        assertFalse(File(root, ".chunks-write-probe-0000000000000000.tmp").exists())
    }

    @Test fun destinationReplacedBySymlinkDuringRenameCannotAlterOutsideBytes() {
        val root = temp.newFolder("interleaved-link")
        val foreign = File(temp.newFolder("interleaved-foreign"), "payload.bin")
        val original = byteArrayOf(8, 6, 7, 5, 3, 0, 9)
        foreign.writeBytes(original)
        val target = File(root, ".chunks-write-probe-0000000000000000.ok")
        val predictable = object : java.util.Random() {
            override fun nextBytes(bytes: ByteArray) { bytes.fill(0) }
        }
        val outcome = LocalPlaceWriteProbe.verify(root, predictable,
            beforeRename = { Files.createSymbolicLink(target.toPath(), foreign.toPath()) })
        assertNotNull("A raced symlink destination must be refused", outcome)
        assertTrue(Files.isSymbolicLink(target.toPath()))
        assertTrue(original.contentEquals(foreign.readBytes()))
        assertFalse(File(root, ".chunks-write-probe-0000000000000000.tmp").exists())
    }

    @Test fun unknownPostCleanupExistenceNeverReportsVerifiedWriteProbe() {
        val root = temp.newFolder("unavailable-probe-check")
        val tmp = File(root, "probe.tmp").toPath()
        val ok = File(root, "probe.ok").toPath()
        assertTrue(LocalPlaceWriteProbe.bothNamesDefinitelyAbsent(tmp, ok))
        var calls = 0
        assertFalse(LocalPlaceWriteProbe.bothNamesDefinitelyAbsent(tmp, ok) {
            calls++
            // A provider may be unable to prove absence for the first name.
            false
        })
        assertEquals(1, calls)
        assertFalse(LocalPlaceWriteProbe.bothNamesDefinitelyAbsent(tmp, ok) {
            // The first absence is proven, but the rename-target check is
            // unavailable. It must still be an unverified result.
            it == tmp
        })
        File(root, "probe.tmp").writeText("unrelated")
        assertFalse(LocalPlaceWriteProbe.bothNamesDefinitelyAbsent(tmp, ok))
    }
}
