package io.github.gamesbyian.chunks.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalPayloadCollisionSurveyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun absentPayloadsAreClearOnlyAtObservationTime() {
        val result = LocalPayloadCollisionSurvey.inspect(
            temp.root, listOf("Movies/movie.mkv", "Series/S01E01.mkv")
        )
        assertEquals(LocalPayloadCollisionSurvey.Result.ClearAtObservation(2), result)
    }

    @Test fun anUnrelatedExistingFileRequiresReviewEvenIfTheNameMatches() {
        val root = temp.newFolder("existing-payload")
        val target = File(root, "Series/S01E01.mkv")
        assertTrue(target.parentFile.mkdirs())
        target.writeText("foreign bytes")
        val result = LocalPayloadCollisionSurvey.inspect(root, listOf("Series/S01E01.mkv"))
        assertEquals(LocalPayloadCollisionSurvey.Result.ExistingPaths(
            listOf("Series/S01E01.mkv"), 1), result)
        assertEquals("foreign bytes", target.readText())
    }

    @Test fun directoriesAndMultipleFilesAreCountedWithoutMutation() {
        val root = temp.newFolder("multi-collision")
        File(root, "directory-as-file").mkdirs()
        File(root, "episode.mkv").writeText("foreign")
        val result = LocalPayloadCollisionSurvey.inspect(root,
            listOf("directory-as-file", "episode.mkv", "not-present"))
        assertEquals(LocalPayloadCollisionSurvey.Result.ExistingPaths(
            listOf("directory-as-file", "episode.mkv"), 2), result)
    }

    @Test fun malformedOrEscapingNamesAreUnknownNotSafe() {
        for (relative in listOf("../escape.mkv", "/tmp/outside", "a\\b.mkv",
                "a/../b.mkv", "a//b.mkv", "a/./b.mkv", "a/")) {
            assertTrue(relative, LocalPayloadCollisionSurvey.inspect(
                temp.root, listOf(relative)) is LocalPayloadCollisionSurvey.Result.Unknown)
        }
    }

    @Test fun existingSymlinkParentCannotAuthorizeWritesOutsideTheRoot() {
        val root = temp.newFolder("symlink-root")
        val external = temp.newFolder("external-unrelated")
        Files.createSymbolicLink(File(root, "link").toPath(), external.toPath())
        assertTrue(LocalPayloadCollisionSurvey.inspect(root, listOf("link/foreign.mkv"))
            is LocalPayloadCollisionSurvey.Result.Unknown)
        assertTrue(external.list().isEmpty())
    }

    @Test fun inaccessibleOrUntrustedRootFailsClosed() {
        val file = temp.newFile("root-is-file")
        assertTrue(LocalPayloadCollisionSurvey.inspect(file, listOf("a.mkv"))
            is LocalPayloadCollisionSurvey.Result.Unknown)
        assertTrue(LocalPayloadCollisionSurvey.inspect(
            File(temp.root, "nonexistent"), listOf("a.mkv"))
            is LocalPayloadCollisionSurvey.Result.Unknown)
    }

    @Test fun oversizedFileListDoesNotBecomeMisleadingClear() {
        assertTrue(LocalPayloadCollisionSurvey.inspect(
            temp.root, listOf("a", "b"), maxPaths = 1)
            is LocalPayloadCollisionSurvey.Result.Unknown)
    }
}
