package io.github.gamesbyian.chunks.model

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.Random
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryCopierTest {
    @get:Rule val temp = TemporaryFolder()

    private class SimulatedProcessDeath : Error()

    private class Target(root: File) : ZipExtractionTarget<File> {
        val real = FileZipExtractionTarget(root)
        var free: Long? = Long.MAX_VALUE / 4
        var created = 0
        var dieAfterFiles = -1
        var failWriteOnFile = -1
        /** Runs while the given file number is being written. */
        var duringWrite: Pair<Int, () -> Unit>? = null
        override val root: File get() = real.root
        override fun child(parent: File, name: String) = real.child(parent, name)
        override fun isDirectory(node: File) = real.isDirectory(node)
        override fun hasChildren(directory: File) = real.hasChildren(directory)
        override fun createDirectory(parent: File, name: String) = real.createDirectory(parent, name)
        override fun createFile(parent: File, name: String): File? {
            if (created == dieAfterFiles) throw SimulatedProcessDeath()
            created++
            return real.createFile(parent, name)
        }
        override fun openNewOutput(file: File): OutputStream {
            val output = real.openNewOutput(file)
            val number = created
            if (number == failWriteOnFile) return object : OutputStream() {
                override fun write(b: Int) = throw IOException("No space left on device")
                override fun close() = output.close()
            }
            val hook = duringWrite?.takeIf { it.first == number }?.second ?: return output
            return object : OutputStream() {
                var fired = false
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (!fired) { fired = true; hook() }
                    output.write(b, off, len)
                }
                override fun flush() = output.flush()
                override fun close() = output.close()
            }
        }
        override fun rename(node: File, parent: File, newName: String) = real.rename(node, parent, newName)
        override fun delete(node: File) = real.delete(node)
        override fun availableBytes() = free
    }

    private class Payload(val root: File, val files: Map<String, ByteArray>) {
        fun request(label: String = "Show S01", allowUnknown: Boolean = false) = LibraryCopyRequest(root,
            files.map { (path, bytes) -> LibraryCopyFile(File(root, path), path, bytes.size.toLong()) },
            label, "test-target", allowUnknown)
    }

    private fun payload(): Payload {
        val root = temp.newFolder("downloads", "Show S01")
        val random = Random(7)
        val files = linkedMapOf(
            "Episode 1.mkv" to ByteArray(300_000).also { random.nextBytes(it) },
            "Extras/Notes.txt" to "notes".toByteArray(),
            "Extras/Deep/empty.bin" to ByteArray(0)
        )
        for ((path, bytes) in files) File(root, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
        return Payload(root, files)
    }

    private fun journal() = FileZipExtractionJournal(File(temp.newFolder(), "op.log"))

    private fun tree(root: File) = root.walkTopDown().filter { it != root }
        .map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()

    private fun assertSourceIntact(payload: Payload) {
        for ((path, bytes) in payload.files) assertArrayEquals(path, bytes, File(payload.root, path).readBytes())
    }

    @Test fun copiesEveryFileIntoANewFolderAndKeepsTheOriginals() {
        val payload = payload()
        val destination = temp.newFolder("library")
        File(destination, "Unrelated.txt").writeText("keep")
        val result = LibraryCopier.copy(payload.request(), FileZipExtractionTarget(destination), journal())
        assertEquals(result.reason, ZipExtractionStatus.EXTRACTED, result.status)
        assertEquals("Show S01", result.folderName)
        assertEquals(3, result.writtenFiles)
        assertEquals(payload.files.values.sumOf { it.size.toLong() }, result.writtenBytes)
        for ((path, bytes) in payload.files) assertArrayEquals(path, bytes, File(destination, "Show S01/$path").readBytes())
        assertEquals("keep", File(destination, "Unrelated.txt").readText())
        assertSourceIntact(payload)
    }

    @Test fun anExistingFolderOfTheSameNameIsNeverMergedOrReplaced() {
        val payload = payload()
        val destination = temp.newFolder("library")
        File(destination, "Show S01").mkdirs()
        File(destination, "Show S01/Episode 1.mkv").writeText("user's own file")
        val result = LibraryCopier.copy(payload.request(), FileZipExtractionTarget(destination), journal())
        assertEquals(ZipExtractionStatus.EXTRACTED, result.status)
        assertEquals("Show S01 (2)", result.folderName)
        assertEquals("user's own file", File(destination, "Show S01/Episode 1.mkv").readText())
    }

    @Test fun refusesUnsafeOrUnfinishedInputsBeforeWritingAnything() {
        val payload = payload()
        val outside = temp.newFile("secret.txt").apply { writeText("outside") }
        val link = File(payload.root, "link.txt").toPath()
        Files.createSymbolicLink(link, outside.toPath())
        val bad = listOf(
            LibraryCopyFile(File(payload.root, "Episode 1.mkv"), "../escape.mkv", 300_000),
            LibraryCopyFile(File(payload.root, "Episode 1.mkv"), "a//b", 300_000),
            LibraryCopyFile(outside, "secret.txt", 7),
            LibraryCopyFile(link.toFile(), "link.txt", 7),
            LibraryCopyFile(File(payload.root, "Episode 1.mkv"), "Episode 1.mkv", 299_999),
            LibraryCopyFile(File(payload.root, "missing.bin"), "missing.bin", 0)
        )
        val destination = temp.newFolder("library")
        for (file in bad) {
            val result = LibraryCopier.copy(LibraryCopyRequest(payload.root, listOf(file), "x", "t"),
                FileZipExtractionTarget(destination), journal())
            assertEquals(file.toString(), ZipExtractionStatus.BLOCKED, result.status)
        }
        val duplicate = LibraryCopyRequest(payload.root, listOf(
            LibraryCopyFile(File(payload.root, "Extras/Notes.txt"), "Notes.txt", 5),
            LibraryCopyFile(File(payload.root, "Extras/Notes.txt"), "NOTES.txt", 5)), "x", "t")
        assertEquals(ZipExtractionStatus.BLOCKED,
            LibraryCopier.copy(duplicate, FileZipExtractionTarget(destination), journal()).status)
        val fileFolder = LibraryCopyRequest(payload.root, listOf(
            LibraryCopyFile(File(payload.root, "Extras/Notes.txt"), "a", 5),
            LibraryCopyFile(File(payload.root, "Extras/Notes.txt"), "a/b", 5)), "x", "t")
        assertEquals(ZipExtractionStatus.BLOCKED,
            LibraryCopier.copy(fileFolder, FileZipExtractionTarget(destination), journal()).status)
        assertTrue(tree(destination).isEmpty())
    }

    @Test fun freeSpaceIsCheckedAndUnknownSpaceNeedsAcknowledgement() {
        val payload = payload()
        val destination = temp.newFolder("library")
        val target = Target(destination).apply { free = 1_000 }
        assertEquals(ZipExtractionStatus.BLOCKED, LibraryCopier.copy(payload.request(), target, journal()).status)
        target.free = null
        assertEquals(ZipExtractionStatus.BLOCKED, LibraryCopier.copy(payload.request(), target, journal()).status)
        assertTrue(tree(destination).isEmpty())
        assertEquals(ZipExtractionStatus.EXTRACTED,
            LibraryCopier.copy(payload.request(allowUnknown = true), target, journal()).status)
    }

    @Test fun aSourceChangingDuringTheCopyFailsAndLeavesNothing() {
        val payload = payload()
        val destination = temp.newFolder("library")
        val target = Target(destination).apply {
            duringWrite = 1 to {
                val episode = File(payload.root, "Episode 1.mkv")
                val bytes = episode.readBytes()
                // Bytes the copier has already read: the copy would silently differ.
                bytes[0] = (bytes[0] + 1).toByte()
                episode.writeBytes(bytes)
            }
        }
        val result = LibraryCopier.copy(payload.request(), target, journal())
        assertEquals(ZipExtractionStatus.FAILED_ROLLED_BACK, result.status)
        assertEquals("A file changed while it was being copied", result.reason)
        assertTrue(tree(destination).isEmpty())
    }

    @Test fun writeFailureAndCancellationRollBack() {
        val payload = payload()
        val destination = temp.newFolder("library")
        val failing = Target(destination).apply { failWriteOnFile = 2 }
        assertEquals(ZipExtractionStatus.FAILED_ROLLED_BACK, LibraryCopier.copy(payload.request(), failing, journal()).status)
        assertTrue(tree(destination).isEmpty())
        var checks = 0
        val cancelled = LibraryCopier.copy(payload.request(), FileZipExtractionTarget(destination), journal(), cancelled = { ++checks > 3 })
        assertEquals(ZipExtractionStatus.CANCELLED, cancelled.status)
        assertTrue(tree(destination).isEmpty())
        assertSourceIntact(payload)
    }

    @Test fun processDeathMidCopyIsRolledBackByReconcileWithoutRetrying() {
        val payload = payload()
        val destination = temp.newFolder("library")
        val log = journal()
        val dying = Target(destination).apply { dieAfterFiles = 2 }
        try {
            LibraryCopier.copy(payload.request(), dying, log, operationId = "a".repeat(32))
            fail("expected simulated death")
        } catch (_: SimulatedProcessDeath) { }
        assertEquals(listOf(".chunks-partial-" + "a".repeat(32)), tree(destination).filter { '/' !in it })
        val recovered = ZipExtractor.reconcile(FileZipExtractionTarget(destination), log)
        assertEquals(ZipExtractionStatus.FAILED_ROLLED_BACK, recovered.status)
        assertTrue(tree(destination).isEmpty())
        assertSourceIntact(payload)
    }
}
