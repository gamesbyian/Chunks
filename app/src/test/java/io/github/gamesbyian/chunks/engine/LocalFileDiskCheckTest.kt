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

    @Test fun missingOrNonFolderSaveRootFailsClosedInsteadOfClean() {
        val base = Files.createTempDirectory("chunks-root").toFile()
        val notStarted = listOf(file(0, "a.bin", 10, 0), file(1, "b.bin", 10, 0, wanted = false))
        val regular = File(base, "plain.txt").apply { writeText("x") }
        for (root in listOf(File(base, "gone"), regular)) {
            val report = LocalFileDiskCheck.check(root, notStarted)
            assertTrue(report.results.all { it.status == Status.FOLDER_UNAVAILABLE })
            assertEquals(2, report.problems.size)
            assertFalse(report.summary().contains("No problems"))
        }
        // Nothing was created while checking.
        assertFalse(File(base, "gone").exists())
        assertTrue(regular.isFile)
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

    @Test fun symlinkInsideRootIsNeverReportedAsARealPayload() {
        val root = Files.createTempDirectory("chunks-diskcheck-internal-link").toFile()
        try {
            val original = File(root, "real.bin")
            original.writeBytes(ByteArray(17) { 3 })
            Files.createSymbolicLink(File(root, "alias.bin").toPath(), original.toPath())
            val result = LocalFileDiskCheck.check(root,
                listOf(file(0, "alias.bin", 17, 17))).results.single()
            assertEquals(Status.UNSAFE_PATH, result.status)
            assertNull(result.observedBytes)
            assertArrayEquals(ByteArray(17) { 3 }, original.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun brokenSymlinkIsUnsafeInsteadOfAnApparentlyUnstartedFile() {
        val root = Files.createTempDirectory("chunks-diskcheck-broken-link").toFile()
        try {
            Files.createSymbolicLink(File(root, "orphan.bin").toPath(),
                File(root, "absent.bin").toPath())
            val result = LocalFileDiskCheck.check(root,
                listOf(file(0, "orphan.bin", 100, 0))).results.single()
            assertEquals(Status.UNSAFE_PATH, result.status)
            assertNull(result.observedBytes)
        } finally { root.deleteRecursively() }
    }

    @Test fun directlySymlinkedSaveFolderDoesNotMakeOtherFilesAppearPresent() {
        val real = Files.createTempDirectory("chunks-diskcheck-real-root").toFile()
        val alias = File(real.parentFile, real.name + "-alias")
        try {
            File(real, "foreign.bin").writeBytes(ByteArray(13) { 7 })
            Files.createSymbolicLink(alias.toPath(), real.toPath())
            val report = LocalFileDiskCheck.check(alias,
                listOf(file(0, "foreign.bin", 13, 13)))
            assertEquals(Status.FOLDER_UNAVAILABLE, report.results.single().status)
            assertNull(report.results.single().observedBytes)
            assertArrayEquals(ByteArray(13) { 7 },
                File(real, "foreign.bin").readBytes())
        } finally {
            alias.delete()
            real.deleteRecursively()
        }
    }

    @Test fun ambiguousOrEscapingNamesNeverCountAsUnstartedOrHealthy() {
        val root = Files.createTempDirectory("chunks-diskcheck-malformed").toFile()
        try {
            for (unsafe in listOf("../elsewhere", "/absolute", "x/../y",
                "x//y", "x/./y", "x\\y", "x\nname")) {
                val result = LocalFileDiskCheck.check(root,
                    listOf(file(0, unsafe, 4, 0))).results.single()
                assertEquals(unsafe, Status.UNSAFE_PATH, result.status)
            }
            assertEquals(Status.UNREADABLE, LocalFileDiskCheck.classify(
                file(0, "unknown.bin", 4, 0),
                LocalFileDiskCheck.Observation(false, false, 0, error = true)))
        } finally { root.deleteRecursively() }
    }
}
