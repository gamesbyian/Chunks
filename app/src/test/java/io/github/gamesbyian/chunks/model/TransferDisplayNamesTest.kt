package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.*
import org.junit.Test

class TransferDisplayNamesTest {
    private val original = "Celebrity.Jeopardy.S04E02.720p.HEVC.x265-MeGusta.mkv"

    @Test fun extractsEpisodeWithoutChangingTheOriginal() {
        val suggestions = TransferDisplayNames.suggestions(original)
        assertEquals("Celebrity Jeopardy S04E02", suggestions.first().name)
        assertEquals("Celebrity Jeopardy · Season 4, Episode 2", suggestions[1].name)
        assertEquals(2, suggestions.size)
        assertEquals("Celebrity.Jeopardy.S04E02.720p.HEVC.x265-MeGusta.mkv", original)
    }

    @Test fun neverFabricatesGuestsOrEpisodeTitles() {
        val suggestions = TransferDisplayNames.suggestions(original)
        assertFalse(suggestions.any { it.name.contains("Chalamet", ignoreCase = true) })
        val withGuest = "Celebrity.Jeopardy.S04E02.Timothee.Chalamet.1080p.mkv"
        assertTrue(TransferDisplayNames.suggestions(withGuest).any {
            it.name == "Celebrity Jeopardy: Timothee Chalamet (S04E02)"
        })
    }

    @Test fun cleansMovieYearAndLeavesOrdinaryNamesUntouched() {
        assertEquals("A Movie (2024)", TransferDisplayNames.suggestions(
            "A.Movie.2024.1080p.BluRay.x264.mkv").first().name)
        assertTrue(TransferDisplayNames.suggestions("Already Readable Title.mkv").isEmpty())
        assertTrue(TransferDisplayNames.suggestions("file.xyz").isEmpty())
        assertTrue(TransferDisplayNames.suggestions("").isEmpty())
    }

    @Test fun validatesNamesWithoutUsingThemAsPaths() {
        assertEquals("Celeb Jeopardy s04e02",
            TransferDisplayNames.validateAlias("  Celeb Jeopardy s04e02  "))
        assertEquals("😊", TransferDisplayNames.validateAlias(" 😊 "))
        for (bad in listOf("", " \t ", "x".repeat(161), "trick\u202Egnp", "a\nb")) {
            assertThrows(IllegalArgumentException::class.java) {
                TransferDisplayNames.validateAlias(bad)
            }
        }
    }

    @Test fun catalogSearchesAndSortsAliasButKeepsRawMetadata() {
        val originals = listOf(
            RemoteTransfer("A", original, 100, 50, true, "/videos"),
            RemoteTransfer("B", "Other.Series.S01E01.mkv", 100, 90, true, "/videos")
        )
        val names = mapOf("A" to "A friendly episode", "B" to "Z renamed episode")
        val resolver: (RemoteTransfer) -> String = { names[it.hash] ?: it.name }
        assertEquals("A", TransferCatalog.visible(originals, "friendly",
            TransferFilter.ALL, TransferOrder.NAME, resolver).single().hash)
        assertEquals("A", TransferCatalog.visible(originals, "HEVC",
            TransferFilter.ALL, TransferOrder.NAME, resolver).single().hash)
        assertEquals(listOf("A", "B"), TransferCatalog.visible(originals, "",
            TransferFilter.ALL, TransferOrder.NAME, resolver).map { it.hash })
        assertEquals(original, originals[0].name)
        assertEquals("/videos", originals[0].directory)
    }
}
