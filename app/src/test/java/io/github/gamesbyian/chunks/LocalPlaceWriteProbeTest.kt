package io.github.gamesbyian.chunks

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
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

    // Pure JVM fixture: path identity is captured before the controlled hook.
    // This is not Android fd/fstat evidence; instrumentation exercises that path.
    private fun verify(
        root: File,
        random: java.util.Random = java.security.SecureRandom(),
        afterCreate: () -> Unit = {},
        beforeRename: () -> Unit = {},
        beforePublish: () -> Unit = {}
    ) = LocalPlaceWriteProbe.verify(root, random, openExclusive = { path, hook ->
        val channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        val key = Files.readAttributes(path, BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS).fileKey()
        hook()
        object : LocalPlaceWriteProbe.ExclusiveProbeFile {
            override fun writeAt(position: Long, bytes: ByteArray) {
                channel.position(position)
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) check(channel.write(buffer) > 0)
            }
            override fun sync() = channel.force(true)
            override fun owns(path: Path): Boolean {
                val stat = Files.readAttributes(path, BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS)
                return key != null && stat.isRegularFile && stat.fileKey() == key
            }
            override fun close() = channel.close()
        }
    }, afterCreate = afterCreate, beforeRename = beforeRename,
        beforePublish = beforePublish)

    @Test fun writableFolderPassesAndKeepsNoProbeFiles() {
        val folder = temp.newFolder("Downloads")
        File(folder, "existing.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(verify(folder))
        assertEquals(listOf("existing.bin"), folder.list()!!.toList())
        assertTrue(File(folder, "existing.bin").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test fun missingRelativeSymlinkedAndReadOnlyFoldersAreRefused() {
        assertNotNull(verify(File(temp.root, "absent")))
        assertNotNull(verify(File("relative")))
        val real = temp.newFolder("real")
        val link = File(temp.root, "link").toPath()
        Files.createSymbolicLink(link, real.toPath())
        assertNotNull(verify(link.toFile()))
        val readOnly = temp.newFolder("read-only")
        readOnly.setWritable(false)
        try {
            // Root ignores permission bits; only assert where the OS enforces them.
            if (!File(readOnly, "x").let { runCatching { it.createNewFile() }.getOrDefault(false) })
                assertNotNull(verify(readOnly))
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
        assertNotNull(verify(root, predictable))
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
        assertNotNull(verify(root, predictable))
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
        val outcome = verify(root, predictable,
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
        val outcome = verify(root, predictable,
            beforeRename = { Files.createSymbolicLink(target.toPath(), foreign.toPath()) })
        assertNotNull("A raced symlink destination must be refused", outcome)
        assertTrue(Files.isSymbolicLink(target.toPath()))
        assertTrue(original.contentEquals(foreign.readBytes()))
        assertFalse(File(root, ".chunks-write-probe-0000000000000000.tmp").exists())
    }

    @Test fun destinationCreatedAfterLastSurveyIsNeverReplacedOrDeleted() {
        val root = temp.newFolder("post-survey-collision")
        val destination = File(root, ".chunks-write-probe-0000000000000000.ok")
        val foreign = byteArrayOf(14, 15, 92, 65, 35, 89)
        val predictable = object : java.util.Random() {
            override fun nextBytes(buffer: ByteArray) { buffer.fill(0) }
        }
        var injected = false
        val result = verify(root, predictable, beforePublish = {
            // No more existence survey occurs after this callback. The only
            // safe outcome is CREATE_NEW refusing the occupied destination.
            destination.writeBytes(foreign)
            injected = true
        })
        assertTrue("The injected post-survey collision must execute", injected)
        assertNotNull("An occupied .ok destination must be refused", result)
        assertTrue("The foreign destination must remain byte-identical",
            foreign.contentEquals(destination.readBytes()))
        assertFalse("Owned source staging can be cleaned without touching .ok",
            File(root, ".chunks-write-probe-0000000000000000.tmp").exists())
    }

    @Test fun destinationSymlinkArrivingAfterLastSurveyIsNotFollowedOrRemoved() {
        val root = temp.newFolder("post-survey-link")
        val outside = temp.newFile("outside-post-survey").apply {
            writeText("external content remains intact")
        }
        val destination = File(root, ".chunks-write-probe-0000000000000000.ok").toPath()
        val predictable = object : java.util.Random() {
            override fun nextBytes(buffer: ByteArray) { buffer.fill(0) }
        }
        val result = verify(root, predictable, beforePublish = {
            Files.createSymbolicLink(destination, outside.toPath())
        })
        assertNotNull(result)
        assertTrue("Pre-existing symlink must not be replaced",
            Files.isSymbolicLink(destination))
        assertEquals("external content remains intact", outside.readText())
        assertFalse(File(root, ".chunks-write-probe-0000000000000000.tmp").exists())
    }

    @Test fun replacedSourceBeforeRenameIsNeverMovedOrDeleted() {
        val root = temp.newFolder("interleaved-source-replacement")
        val originalName = File(root, ".chunks-write-probe-0000000000000000.tmp").toPath()
        val displaced = File(root, "owned-probe-moved-aside.tmp").toPath()
        val destination = File(root, ".chunks-write-probe-0000000000000000.ok").toPath()
        val foreign = byteArrayOf(9, 8, 7, 6, 5)
        val predictable = object : java.util.Random() {
            override fun nextBytes(bytes: ByteArray) { bytes.fill(0) }
        }
        val outcome = verify(root, predictable, beforeRename = {
            Files.move(originalName, displaced)
            Files.write(originalName, foreign)
        })
        assertNotNull("A replaced source must never be treated as the owned file", outcome)
        assertTrue("The replaced source must retain foreign bytes",
            foreign.contentEquals(Files.readAllBytes(originalName)))
        assertTrue("The displaced original must remain intact for manual recovery",
            Files.size(displaced) > foreign.size)
        assertFalse("No renamed destination is allowed", Files.exists(destination))
    }

    @Test fun symlinkedSourceAfterWriteCannotBeMovedOrCleanedUp() {
        val root = temp.newFolder("interleaved-source-symlink")
        val foreign = File(temp.newFolder("external-source"), "keep.bin")
        val original = byteArrayOf(4, 2, 4, 2)
        foreign.writeBytes(original)
        val source = File(root, ".chunks-write-probe-0000000000000000.tmp").toPath()
        val displaced = File(root, "original-probe-moved.tmp").toPath()
        val destination = File(root, ".chunks-write-probe-0000000000000000.ok").toPath()
        val predictable = object : java.util.Random() {
            override fun nextBytes(bytes: ByteArray) { bytes.fill(0) }
        }
        val outcome = verify(root, predictable, beforeRename = {
            Files.move(source, displaced)
            Files.createSymbolicLink(source, foreign.toPath())
        })
        assertNotNull("A symlink replacement must be refused", outcome)
        assertTrue(Files.isSymbolicLink(source))
        assertTrue(original.contentEquals(foreign.readBytes()))
        assertTrue(Files.exists(displaced))
        assertFalse(Files.exists(destination))
    }

    @Test fun replacementBeforeDescriptorIdentityCaptureIsNeverOwned() {
        val root = temp.newFolder("early-source-replacement")
        val source = File(root, ".chunks-write-probe-0000000000000000.tmp").toPath()
        val displaced = File(root, "opened-inode.tmp").toPath()
        val foreign = byteArrayOf(9, 8, 7, 6)
        val predictable = object : java.util.Random() {
            override fun nextBytes(bytes: ByteArray) { bytes.fill(0) }
        }
        val outcome = verify(root, predictable, afterCreate = {
            Files.move(source, displaced)
            Files.write(source, foreign)
        })
        assertNotNull(outcome)
        assertTrue(foreign.contentEquals(Files.readAllBytes(source)))
        assertTrue(Files.exists(displaced))
        assertFalse(File(root, ".chunks-write-probe-0000000000000000.ok").exists())
    }

    @Test fun byteIdenticalReplacementDoesNotConferOwnership() {
        val root = temp.newFolder("identical-foreign-source")
        val source = File(root, ".chunks-write-probe-0000000000000000.tmp").toPath()
        val displaced = File(root, "original-inode").toPath()
        var expected: ByteArray? = null
        val predictable = object : java.util.Random() {
            override fun nextBytes(bytes: ByteArray) { bytes.fill(0) }
        }
        val outcome = verify(root, predictable, beforeRename = {
            expected = Files.readAllBytes(source)
            Files.move(source, displaced)
            Files.copy(displaced, source)
        })
        assertNotNull(outcome)
        assertTrue(expected!!.contentEquals(Files.readAllBytes(source)))
        assertTrue(expected!!.contentEquals(Files.readAllBytes(displaced)))
        assertFalse(File(root, ".chunks-write-probe-0000000000000000.ok").exists())
    }

    @Test fun unavailableDescriptorIdentityNeverAllowsCleanupByPath() {
        val root = temp.newFolder("unavailable-fstat")
        val marker = byteArrayOf(1, 9, 2, 8)
        val outcome = LocalPlaceWriteProbe.verify(root, openExclusive = { path, _ ->
            Files.write(path, marker, StandardOpenOption.CREATE_NEW)
            throw java.io.IOException("descriptor identity unavailable")
        })
        assertNotNull(outcome)
        assertEquals(1, root.list()!!.size)
        assertTrue(marker.contentEquals(root.listFiles()!!.single().readBytes()))
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
