package io.github.gamesbyian.chunks.engine

import io.github.gamesbyian.chunks.engine.LocalFileDiskCheck.Status
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LocalFileDiskCheckTest {
    private fun file(index: Int, path: String, size: Long, done: Long, wanted: Boolean = true) =
        LocalTransferFile(index, path, size, done, wanted)

    @Test fun classifiesEveryDiskStateWithoutClaimingVerification() {
        val root = Files.createTempDirectory("chunks-diskcheck").toFile()
        try {
            File(root, "Show").mkdirs()
            File(root, "Show/full.mkv").writeBytes(ByteArray(100))
            File(root, "Show/partial.mkv").writeBytes(ByteArray(40))
            File(root, "Show/wrong.mkv").writeBytes(ByteArray(90))
            File(root, "Show/dir.mkv").mkdirs()
            val files = listOf(
                file(0, "Show/full.mkv", 100, 100),
                file(1, "Show/partial.mkv", 100, 30),
                file(2, "Show/wrong.mkv", 100, 100),
                file(3, "Show/gone.mkv", 100, 64),
                file(4, "Show/later.mkv", 100, 0),
                file(5, "Show/skipped.mkv", 100, 0, wanted = false),
                file(6, "Show/dir.mkv", 100, 0),
                file(7, "../outside.mkv", 100, 0))
            val before = root.walkTopDown().map { it.path to it.length() }.toList()
            val report = LocalFileDiskCheck.check(root, files)
            assertEquals(listOf(Status.PRESENT, Status.PARTIAL, Status.SIZE_MISMATCH, Status.MISSING,
                Status.NOT_STARTED, Status.SKIPPED, Status.NOT_A_FILE, Status.UNSAFE_PATH),
                report.results.map { it.status })
            assertEquals(4, report.problems.size)
            assertTrue(report.summary(), report.summary().startsWith("4 of 8 files need attention."))
            assertTrue(report.summary().endsWith("This checks presence and size only, not contents."))
            assertTrue(report.summary().contains("contents not re-checked"))
            // Strictly read-only: nothing created, removed or resized.
            assertEquals(before, root.walkTopDown().map { it.path to it.length() }.toList())
            assertFalse(File(root.parentFile, "outside.mkv").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun symlinkEscapingTheSaveFolderIsRefused() {
        val root = Files.createTempDirectory("chunks-diskcheck-root").toFile()
        val elsewhere = Files.createTempDirectory("chunks-diskcheck-out").toFile()
        try {
            File(elsewhere, "secret.bin").writeBytes(ByteArray(10))
            Files.createSymbolicLink(File(root, "link").toPath(), elsewhere.toPath())
            val result = LocalFileDiskCheck.check(root, listOf(file(0, "link/secret.bin", 10, 10))).results.single()
            assertEquals(Status.UNSAFE_PATH, result.status)
            assertNull(result.observedBytes)
        } finally { root.deleteRecursively(); elsewhere.deleteRecursively() }
    }

    @Test fun healthyTransferReportsNoProblemsAndEmptyListIsHonest() {
        val root = Files.createTempDirectory("chunks-diskcheck-ok").toFile()
        try {
            File(root, "a.bin").writeBytes(ByteArray(5))
            val report = LocalFileDiskCheck.check(root, listOf(file(0, "a.bin", 5, 5)))
            assertTrue(report.problems.isEmpty())
            assertTrue(report.summary().startsWith("No problems found on disk."))
            assertEquals("No files to check.", LocalFileDiskCheck.check(root, emptyList()).summary())
        } finally { root.deleteRecursively() }
    }
}
