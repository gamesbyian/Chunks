package io.github.gamesbyian.chunks.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalCheckedRestartGuardTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun absentNeverVerifiedFilePermitsObservedRestart() {
        val root = temp.newFolder("resume")
        File(root, "episode1.bin").writeText("verified by engine")
        assertEquals(LocalCheckedRestartGuard.Result.ClearAtObservation,
            LocalCheckedRestartGuard.inspect(root,
                listOf("episode1.bin", "episode2.bin"), listOf(18L, 0L)))
    }

    @Test fun regularFileWithZeroNativeProgressMayBeRecheckedAfterStop() {
        val root = temp.newFolder("foreign")
        val verified = File(root, "owned.bin").apply { writeText("old verified data") }
        val foreign = File(root, "ignored.bin").apply { writeText("foreign untrusted") }
        val result = LocalCheckedRestartGuard.inspect(root,
            listOf("owned.bin", "ignored.bin"), listOf(17L, 0L))
        assertEquals(LocalCheckedRestartGuard.Result.ClearAtObservation, result)
        assertEquals("foreign untrusted", foreign.readText())
        assertEquals("old verified data", verified.readText())
    }

    @Test fun knownPartialFileIsNotDeclaredOwnedByThisTest() {
        val root = temp.newFolder("partial")
        File(root, "partial.bin").writeBytes(ByteArray(400))
        // Content rechecking is the responsibility of native libtorrent,
        // never a filename/size comparison performed by this path survey.
        assertEquals(LocalCheckedRestartGuard.Result.ClearAtObservation,
            LocalCheckedRestartGuard.inspect(root, listOf("partial.bin"), listOf(200L)))
    }

    @Test fun symlinkParentBlocksEvenIfTheChildHasPositiveProgress() {
        val root = temp.newFolder("root")
        val external = temp.newFolder("external")
        Files.createSymbolicLink(File(root, "linked").toPath(), external.toPath())
        assertTrue(LocalCheckedRestartGuard.inspect(root,
            listOf("linked/owned.bin"), listOf(1024L))
            is LocalCheckedRestartGuard.Result.Refused)
        assertTrue(external.list().isEmpty())
    }

    @Test fun existingZeroLengthRegularFileIsAnAuthorizedPayloadCandidate() {
        val root = temp.newFolder("zero")
        val path = File(root, "empty.bin").apply { createNewFile() }
        assertEquals(LocalCheckedRestartGuard.Result.ClearAtObservation,
            LocalCheckedRestartGuard.inspect(root,
                listOf("empty.bin"), listOf(0L)))
        assertEquals(0L, path.length())
    }

    @Test fun incompleteNativeFileProgressAndTraversalRefuse() {
        val root = temp.newFolder("invalid")
        val pairs = listOf(
            listOf("a", "b") to listOf(1L),
            listOf("a") to emptyList(),
            emptyList<String>() to emptyList<Long>(),
            listOf("a") to listOf(-1L),
            listOf("../escape") to listOf(0L)
        )
        pairs.forEach { (paths, progress) ->
            assertTrue("$paths / $progress",
                LocalCheckedRestartGuard.inspect(root, paths, progress)
                    is LocalCheckedRestartGuard.Result.Refused)
        }
        assertTrue(LocalCheckedRestartGuard.inspect(root,
            listOf("a", "b"), listOf(0L, 0L), maxPaths = 1)
            is LocalCheckedRestartGuard.Result.Refused)
    }

    @Test fun interruptedPermissionsAndRootSwapCannotBecomeClear() {
        val root = temp.newFolder("valid")
        val missing = File(root, "removed")
        assertTrue(LocalCheckedRestartGuard.inspect(missing,
            listOf("file.bin"), listOf(0L))
            is LocalCheckedRestartGuard.Result.Refused)
        val other = temp.newFolder("different")
        val alias = File(root, "alias")
        Files.createSymbolicLink(alias.toPath(), other.toPath())
        assertTrue(LocalCheckedRestartGuard.inspect(alias,
            listOf("file.bin"), listOf(0L))
            is LocalCheckedRestartGuard.Result.Refused)
    }
}
