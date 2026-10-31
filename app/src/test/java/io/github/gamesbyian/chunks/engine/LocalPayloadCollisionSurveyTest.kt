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

    @Test fun existingRegularPayloadFileIsReportedButNotMutatedBySurvey() {
        val root = temp.newFolder("existing-payload")
        val target = File(root, "Series/S01E01.mkv")
        assertTrue(target.parentFile.mkdirs())
        target.writeText("foreign bytes")
        val result = LocalPayloadCollisionSurvey.inspect(root, listOf("Series/S01E01.mkv"))
        assertEquals(LocalPayloadCollisionSurvey.Result.ExistingPaths(
            listOf("Series/S01E01.mkv"), 1), result)
        assertEquals("foreign bytes", target.readText())
    }

    @Test fun directoryAtExpectedPayloadFileIsRefusedRatherThanOverwritten() {
        val root = temp.newFolder("wrong-payload-type")
        File(root, "directory-as-file").mkdirs()
        assertTrue(LocalPayloadCollisionSurvey.inspect(root,
            listOf("directory-as-file")) is LocalPayloadCollisionSurvey.Result.Unknown)
        val target = File(root, "episode.mkv").apply { writeText("existing regular bytes") }
        assertEquals(LocalPayloadCollisionSurvey.Result.ExistingPaths(
            listOf("episode.mkv"), 1),
            LocalPayloadCollisionSurvey.inspect(root,
                listOf("episode.mkv", "not-present")))
        assertEquals("existing regular bytes", target.readText())
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

    @Test fun nativeMetadataCountIsBoundedBeforePathMaterialization() {
        // This must reject enormous hostile native counts without allocating
        // a list or constructing a huge synthetic torrent fixture.
        for (count in listOf(Int.MIN_VALUE, -1, 0, 100_001, Int.MAX_VALUE)) {
            assertTrue("Unexpected acceptance of native count $count",
                !LocalPayloadCollisionSurvey.supportsNativeFileCount(count))
        }
        for (count in listOf(1, 2, 100_000)) {
            assertTrue("Valid native count $count refused",
                LocalPayloadCollisionSurvey.supportsNativeFileCount(count))
        }
    }

    @Test fun oversizedFileListDoesNotBecomeMisleadingClear() {
        assertTrue(LocalPayloadCollisionSurvey.inspect(
            temp.root, listOf("a", "b"), maxPaths = 1)
            is LocalPayloadCollisionSurvey.Result.Unknown)
    }

    @Test fun chosenRootAncestorAliasIsResolvedBeforeCollisionSurvey() {
        val selected = temp.newFolder("selected-parent")
        val external = temp.newFolder("external-target")
        val actual = File(external, "download")
        assertTrue(actual.mkdir())
        val foreign = File(actual, "episode.mkv")
        foreign.writeText("foreign bytes")
        Files.createSymbolicLink(File(selected, "indirect").toPath(), external.toPath())
        // OS-managed parent aliases also occur in Android's app-private tree.
        // Inspect the real destination, never claim a lexical path is clear.
        assertEquals(LocalPayloadCollisionSurvey.Result.ExistingPaths(
            listOf("episode.mkv"), 1),
            LocalPayloadCollisionSurvey.inspect(
                File(selected, "indirect/download"), listOf("episode.mkv")))
        assertEquals("foreign bytes", foreign.readText())
    }

    @Test fun rootItselfCannotBeAnIndirectDestination() {
        val selected = temp.newFolder("symlink-selected")
        val actual = temp.newFolder("symlink-real")
        val alias = File(selected, "direct-link")
        Files.createSymbolicLink(alias.toPath(), actual.toPath())
        assertTrue(LocalPayloadCollisionSurvey.inspect(
            alias, listOf("episode.mkv")) is LocalPayloadCollisionSurvey.Result.Unknown)
    }

    @Test fun lexicalParentTraversalCannotSurveyDifferentTreeThanNativeWriter() {
        val selected = temp.newFolder("traversal-selected")
        val external = temp.newFolder("traversal-external")
        Files.createSymbolicLink(File(selected, "indirect").toPath(), external.toPath())
        // Resolving indirect/.. lexically erases the link; resolving it
        // physically could reach the external directory's parent instead.
        assertTrue(LocalPayloadCollisionSurvey.inspect(
            File(selected, "indirect/.."), listOf("episode.mkv"))
            is LocalPayloadCollisionSurvey.Result.Unknown)
    }

    @Test fun duplicateDeclaredFileIndicesCannotShareOneFutureDestination() {
        assertTrue(LocalPayloadCollisionSurvey.inspect(
            temp.root, listOf("duplicate.mkv", "duplicate.mkv"))
            is LocalPayloadCollisionSurvey.Result.Unknown)
    }

    @Test fun torrentFileAndItsOwnChildCannotBothBePayloads() {
        for (paths in listOf(
            listOf("Series", "Series/S01E01.mkv"),
            listOf("Series/S01E01.mkv", "Series")
        )) {
            assertTrue(LocalPayloadCollisionSurvey.inspect(temp.root, paths)
                is LocalPayloadCollisionSurvey.Result.Unknown)
        }
    }

    @Test fun caseInsensitiveVolumeCannotAliasTwoDifferentTorrentIndices() {
        val root = temp.newFolder("folding-alias")
        val paths = listOf("Show/Episode.mkv", "show/episode.MKV")
        assertTrue(LocalPayloadCollisionSurvey.inspect(root, paths)
            is LocalPayloadCollisionSurvey.Result.Unknown)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun caseFoldedFileCannotAlsoBeItsOwnDirectoryParent() {
        val root = temp.newFolder("folding-ancestor")
        for (paths in listOf(
            listOf("SeRiEs", "series/episode.mkv"),
            listOf("series/episode.mkv", "SeRiEs")
        )) {
            assertTrue(LocalPayloadCollisionSurvey.inspect(root, paths)
                is LocalPayloadCollisionSurvey.Result.Unknown)
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun compatibilityAndCanonicalUnicodeAliasesAreNotDistinctFiles() {
        val root = temp.newFolder("unicode-alias")
        for (paths in listOf(
            listOf("Cafe\u0301/episode.mkv", "Caf\u00e9/episode.mkv"),
            listOf("Wei\u00df/episode.mkv", "WEISS/episode.mkv"),
            listOf("\u212b/episode.mkv", "\u00c5/episode.mkv")
        )) {
            assertTrue(paths.toString(), LocalPayloadCollisionSurvey.inspect(root, paths)
                is LocalPayloadCollisionSurvey.Result.Unknown)
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun overlongSinglePathFailsClosedWithoutFilesystemMutation() {
        assertTrue(LocalPayloadCollisionSurvey.inspect(
            temp.root, listOf("a".repeat(4097)))
            is LocalPayloadCollisionSurvey.Result.Unknown)
    }
}
