package io.github.gamesbyian.chunks.model

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipExtractorTest {
    @get:Rule val temp = TemporaryFolder()

    private class SimulatedProcessDeath : Error()

    /** Delegating target used to inject provider faults and simulated process death. */
    private open class FaultyTarget(root: File) : ZipExtractionTarget<File> {
        val real = FileZipExtractionTarget(root)
        var free: Long? = Long.MAX_VALUE / 4
        var createdFiles = 0
        var failWriteOnFile = -1
        var dieAfterFiles = -1
        var refuseFileNumber = -1
        var dieAfterRename = false
        override val root: File get() = real.root
        override fun child(parent: File, name: String) = real.child(parent, name)
        override fun isDirectory(node: File) = real.isDirectory(node)
        override fun hasChildren(directory: File) = real.hasChildren(directory)
        override fun createDirectory(parent: File, name: String) = real.createDirectory(parent, name)
        override fun createFile(parent: File, name: String): File? {
            if (createdFiles == dieAfterFiles) throw SimulatedProcessDeath()
            createdFiles++
            if (createdFiles == refuseFileNumber) return null
            return real.createFile(parent, name)
        }
        override fun openNewOutput(file: File): OutputStream {
            val output = real.openNewOutput(file)
            if (createdFiles != failWriteOnFile) return output
            return object : OutputStream() {
                override fun write(b: Int) = throw IOException("No space left on device")
                override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("No space left on device")
                override fun close() = output.close()
            }
        }
        override fun rename(node: File, parent: File, newName: String): File? {
            val renamed = real.rename(node, parent, newName)
            if (dieAfterRename) throw SimulatedProcessDeath()
            return renamed
        }
        override fun delete(node: File) = real.delete(node)
        override fun availableBytes() = free
    }

    private fun zip(vararg entries: Pair<String, ByteArray?>, stored: Boolean = false): File {
        val file = File(temp.newFolder(), "source.zip")
        ZipOutputStream(FileOutputStream(file)).use { output ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                if (stored && bytes != null) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                output.putNextEntry(entry)
                if (bytes != null) output.write(bytes)
                output.closeEntry()
            }
        }
        return file
    }

    private fun request(zip: File, label: String = "Show pack.zip", allowUnknown: Boolean = false) =
        ZipExtractionRequest(zip, label, "test-target", allowUnknownFreeSpace = allowUnknown)

    private fun tree(root: File): List<String> = root.walkTopDown().filter { it != root }
        .map { it.relativeTo(root).invariantSeparatorsPath + if (it.isDirectory) "/" else "" }.sorted().toList()

    private fun newJournal() = FileZipExtractionJournal(File(temp.newFolder(), "op.log"))

    @Test fun extractsNestedMembersIntoANewFolderWithExactBytesAndKeepsSource() {
        val episode = ByteArray(70_000) { (it % 251).toByte() }
        val source = zip(
            "Season 1/" to null,
            "Season 1/Episode 01.txt" to episode,
            "Season 1/Extras/empty.txt" to ByteArray(0),
            "notes.txt" to "notes".toByteArray()
        )
        val before = source.readBytes()
        val destination = temp.newFolder("dest")
        val journal = newJournal()
        val result = ZipExtractor.extract(request(source), FileZipExtractionTarget(destination), journal)

        assertEquals(result.reason, ZipExtractionStatus.EXTRACTED, result.status)
        assertEquals("Show pack", result.folderName)
        assertEquals(3, result.writtenFiles)
        assertEquals(70_005L, result.writtenBytes)
        assertEquals(listOf("Show pack/", "Show pack/Season 1/", "Show pack/Season 1/Episode 01.txt",
            "Show pack/Season 1/Extras/", "Show pack/Season 1/Extras/empty.txt", "Show pack/notes.txt"),
            tree(destination))
        assertArrayEquals(episode, File(destination, "Show pack/Season 1/Episode 01.txt").readBytes())
        assertArrayEquals(before, source.readBytes())
        assertEquals("PUBLISHED", journal.read().last()[0])
    }

    @Test fun storedMembersAreCopiedExactly() {
        val bytes = "uncompressed payload".toByteArray()
        val destination = temp.newFolder("dest")
        val result = ZipExtractor.extract(request(zip("a.bin" to bytes, stored = true)),
            FileZipExtractionTarget(destination), newJournal())
        assertEquals(ZipExtractionStatus.EXTRACTED, result.status)
        assertArrayEquals(bytes, File(destination, "Show pack/a.bin").readBytes())
    }

    @Test fun neverMergesIntoOrOverwritesAnExistingFolder() {
        val destination = temp.newFolder("dest")
        val existing = File(destination, "Show pack").apply { mkdir() }
        File(existing, "notes.txt").writeText("user's own notes")
        File(destination, "Show pack (2)").writeText("a file, not a folder")

        val result = ZipExtractor.extract(request(zip("notes.txt" to "archive notes".toByteArray())),
            FileZipExtractionTarget(destination), newJournal())

        assertEquals(ZipExtractionStatus.EXTRACTED, result.status)
        assertEquals("Show pack (3)", result.folderName)
        assertEquals("user's own notes", File(existing, "notes.txt").readText())
        assertEquals("a file, not a folder", File(destination, "Show pack (2)").readText())
        assertEquals("archive notes", File(destination, "Show pack (3)/notes.txt").readText())
    }

    @Test fun fileTargetRenameRefusesToReplaceAnExistingSibling() {
        val destination = temp.newFolder("dest")
        val target = FileZipExtractionTarget(destination)
        val a = File(destination, "a").apply { mkdir() }
        File(destination, "b").mkdir()
        assertNull(target.rename(a, destination, "b"))
        assertTrue(a.isDirectory)
        assertNull(target.createFile(destination, "b"))
        assertNull(target.createDirectory(destination, "a"))
    }

    @Test fun hostileArchivesAreRefusedBeforeAnyDestinationWrite() {
        val cases = listOf(
            zip("../escape.txt" to "x".toByteArray()),
            zip("/abs.txt" to "x".toByteArray()),
            zip("a/../../b.txt" to "x".toByteArray()),
            zip("Name.txt" to "x".toByteArray(), "name.TXT" to "y".toByteArray()),
            zip("bomb.bin" to ByteArray(4 * 1024 * 1024)),
            zip(".chunks-partial-deadbeef/x.txt" to "x".toByteArray()),
            zip(("n".repeat(256) + ".txt") to "x".toByteArray())
        )
        for (source in cases) {
            val destination = temp.newFolder()
            val journal = newJournal()
            val result = ZipExtractor.extract(request(source), FileZipExtractionTarget(destination), journal)
            assertEquals(source.name, ZipExtractionStatus.BLOCKED, result.status)
            assertNotNull(result.reason)
            assertTrue(tree(destination).isEmpty())
            assertTrue(journal.read().isEmpty())
        }
    }

    @Test fun corruptCrcIsRefusedBeforeWriting() {
        val source = zip("file.txt" to "payload".toByteArray())
        val bytes = source.readBytes()
        val central = (0 until bytes.size - 46).first { i ->
            bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 0x01.toByte() && bytes[i + 3] == 0x02.toByte()
        }
        for (i in 16..19) bytes[central + i] = 0
        source.writeBytes(bytes)
        val destination = temp.newFolder()
        val result = ZipExtractor.extract(request(source), FileZipExtractionTarget(destination), newJournal())
        assertEquals(ZipExtractionStatus.BLOCKED, result.status)
        assertTrue(result.reason.orEmpty().contains("CRC"))
        assertTrue(tree(destination).isEmpty())
    }

    @Test fun headroomIsCheckedAndUnknownSpaceNeedsExplicitAcknowledgement() {
        val source = zip("a.bin" to ByteArray(1000) { it.toByte() })
        val tight = FaultyTarget(temp.newFolder()).apply { free = 1000L + 1024 * 1024 }
        assertEquals(ZipExtractionStatus.BLOCKED, ZipExtractor.extract(request(source), tight, newJournal()).status)
        assertTrue(tree(tight.root).isEmpty())

        val unknown = FaultyTarget(temp.newFolder()).apply { free = null }
        val refused = ZipExtractor.extract(request(source), unknown, newJournal())
        assertEquals(ZipExtractionStatus.BLOCKED, refused.status)
        assertTrue(refused.reason.orEmpty().contains("unknown"))
        assertEquals(ZipExtractionStatus.EXTRACTED,
            ZipExtractor.extract(request(source, allowUnknown = true), unknown, newJournal()).status)
    }

    @Test fun cancellationMidStreamRemovesEveryPartialOutput() {
        val random = java.util.Random(7)
        val source = zip("one.bin" to ByteArray(200_000).also { random.nextBytes(it) },
            "two.bin" to ByteArray(200_000).also { random.nextBytes(it) })
        val destination = temp.newFolder()
        var checks = 0
        val journal = newJournal()
        val result = ZipExtractor.extract(request(source), FileZipExtractionTarget(destination), journal,
            cancelled = { ++checks > 8 })
        assertEquals(ZipExtractionStatus.CANCELLED, result.status)
        assertTrue(tree(destination).isEmpty())
        assertEquals("ROLLED_BACK", journal.read().last()[0])
    }

    @Test fun writeFailureRollsBackWithoutTouchingNeighbours() {
        val destination = temp.newFolder()
        File(destination, "keep.txt").writeText("keep")
        val target = FaultyTarget(destination).apply { failWriteOnFile = 2 }
        val result = ZipExtractor.extract(request(zip(
            "a.txt" to "a".toByteArray(), "b/c.txt" to "c".toByteArray())), target, newJournal())
        assertEquals(ZipExtractionStatus.FAILED_ROLLED_BACK, result.status)
        assertEquals(listOf("keep.txt"), tree(destination))
    }

    @Test fun providerRefusingAFileNameFailsClosed() {
        val destination = temp.newFolder()
        val target = FaultyTarget(destination).apply { refuseFileNumber = 2 }
        val result = ZipExtractor.extract(request(zip(
            "a.txt" to "a".toByteArray(), "b.txt" to "b".toByteArray())), target, newJournal())
        assertEquals(ZipExtractionStatus.FAILED_ROLLED_BACK, result.status)
        assertTrue(tree(destination).isEmpty())
    }

    @Test fun processDeathMidWriteIsRolledBackByReconcileWithoutRetrying() {
        val destination = temp.newFolder()
        val journal = newJournal()
        val dying = FaultyTarget(destination).apply { dieAfterFiles = 2 }
        val source = zip("a.txt" to "a".toByteArray(), "d/b.txt" to "b".toByteArray(), "d/e/c.txt" to "c".toByteArray())
        try {
            ZipExtractor.extract(request(source), dying, journal)
            fail("expected simulated death")
        } catch (_: SimulatedProcessDeath) { }
        assertTrue(tree(destination).single { it.endsWith("/") && it.count { c -> c == '/' } == 1 }
            .startsWith(ZipExtractor.PARTIAL_PREFIX))

        val recovered = ZipExtractor.reconcile(FileZipExtractionTarget(destination), journal)
        assertEquals(ZipExtractionStatus.FAILED_ROLLED_BACK, recovered.status)
        assertTrue(tree(destination).isEmpty())
        // Idempotent: a second reconcile reports the same outcome and changes nothing.
        assertEquals(ZipExtractionStatus.FAILED_ROLLED_BACK,
            ZipExtractor.reconcile(FileZipExtractionTarget(destination), journal).status)
    }

    @Test fun processDeathAfterRenameIsRecognisedAsPublished() {
        val destination = temp.newFolder()
        val journal = newJournal()
        val dying = FaultyTarget(destination).apply { dieAfterRename = true }
        try {
            ZipExtractor.extract(request(zip("a.txt" to "a".toByteArray())), dying, journal)
            fail("expected simulated death")
        } catch (_: SimulatedProcessDeath) { }
        val recovered = ZipExtractor.reconcile(FileZipExtractionTarget(destination), journal)
        assertEquals(ZipExtractionStatus.EXTRACTED, recovered.status)
        assertEquals("Show pack", recovered.folderName)
        assertEquals("a", File(destination, "Show pack/a.txt").readText())
    }

    @Test fun reconcileKeepsUnjournaledFilesAndAsksForAttention() {
        val destination = temp.newFolder()
        val journal = newJournal()
        try {
            ZipExtractor.extract(request(zip("a.txt" to "a".toByteArray(), "b.txt" to "b".toByteArray())),
                FaultyTarget(destination).apply { dieAfterFiles = 1 }, journal)
            fail("expected simulated death")
        } catch (_: SimulatedProcessDeath) { }
        val partial = destination.listFiles()!!.single()
        File(partial, "someone-elses.txt").writeText("not ours")
        val result = ZipExtractor.reconcile(FileZipExtractionTarget(destination), journal)
        assertEquals(ZipExtractionStatus.NEEDS_ATTENTION, result.status)
        assertEquals("not ours", File(partial, "someone-elses.txt").readText())
    }

    @Test fun reconcileRejectsInconsistentJournals() {
        val destination = temp.newFolder()
        File(destination, "victim").mkdir()
        val journal = newJournal()
        journal.append(listOf("BEGIN", "abcdef12", "t", "victim", "x"))
        assertEquals(ZipExtractionStatus.NEEDS_ATTENTION,
            ZipExtractor.reconcile(FileZipExtractionTarget(destination), journal).status)
        assertTrue(File(destination, "victim").isDirectory)
        assertEquals(ZipExtractionStatus.NEEDS_ATTENTION,
            ZipExtractor.reconcile(FileZipExtractionTarget(destination), newJournal()).status)
    }

    @Test fun journalEncodesFieldsAndIgnoresTornTail() {
        val file = File(temp.newFolder(), "j.log")
        val journal = FileZipExtractionJournal(file)
        journal.append(listOf("FILE", "a b\t%/ü.txt"))
        file.appendText("DIR\tpartial-without-newline")
        assertEquals(listOf(listOf("FILE", "a b\t%/ü.txt")), journal.read())
    }

    @Test fun folderNamesAreSanitisedAndNeverHiddenOrPaths() {
        assertEquals("Show", ZipExtractor.folderBaseName("Show.ZIP"))
        assertEquals("a_b_c", ZipExtractor.folderBaseName("a/b\\c.zip"))
        assertEquals("hidden", ZipExtractor.folderBaseName("..hidden.zip"))
        assertEquals("Extracted archive", ZipExtractor.folderBaseName(" .zip"))
        assertEquals("Extracted archive", ZipExtractor.folderBaseName("..."))
        assertTrue(ZipExtractor.folderBaseName("é".repeat(300)).toByteArray().size <= 200)
    }
}
