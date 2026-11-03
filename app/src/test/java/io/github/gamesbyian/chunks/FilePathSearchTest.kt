package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Test

class FilePathSearchTest {
    private val paths = listOf(
        "Season 1/S01E01.mkv",
        "Season 1/S01E02.mkv",
        "extras/behind-the-scenes.mp4",
        "Season 2/S02E01.mkv",
        "notes.txt"
    )

    @Test fun searchRetainsOriginalProviderIndicesAndOrder() {
        assertEquals(listOf(0, 1, 3), matchingFileIndices(paths, ".MKV"))
        assertEquals(listOf(0, 1), matchingFileIndices(paths, " season 1 "))
        assertEquals(listOf(2), matchingFileIndices(paths, "BEHIND-THE-SCENES"))
    }

    @Test fun emptyAndUnknownQueriesNeverAlterTheSourceInventory() {
        val before = paths.toList()
        assertEquals(listOf(0, 1, 2, 3, 4), matchingFileIndices(paths, ""))
        assertEquals(listOf(0, 1, 2, 3, 4), matchingFileIndices(paths, "  "))
        assertEquals(emptyList<Int>(), matchingFileIndices(paths, "missing-file"))
        assertEquals(before, paths)
    }

    @Test fun identicalNamesAndPathsKeepEachExactIndexIndependent() {
        assertEquals(listOf(0, 2), matchingFileIndices(
            listOf("a/file.mkv", "b/file.txt", "a/file.mkv"), "a/file"))
    }
}
