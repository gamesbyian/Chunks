package io.github.gamesbyian.chunks.organize

import org.junit.Assert.*
import org.junit.Test

class ClassifierTest {
    @Test fun seasonEpisodeRecognizesTvAndFranchise() {
        val result = Classifier.suggest("Star.Trek.TNG.S03E04.1080p.mkv")
        assertEquals("TV", result.category)
        assertEquals("Star Trek", result.collection)
        assertTrue(result.confidence >= 90)
    }

    @Test fun comicAndMovieRemainDifferentCategoriesWithinFranchise() {
        val comic = Classifier.suggest("Amazing.Spider-Man.028.cbz")
        val movie = Classifier.suggest("Spider-Man.2.2004.mkv")
        assertEquals("Comics", comic.category)
        assertEquals("Video", movie.category)
        assertEquals("Spider-Man", comic.collection)
        assertEquals("Spider-Man", movie.collection)
    }

    @Test fun musicAndBooksClassifyWithoutFabricatedCollections() {
        assertEquals("Music", Classifier.suggest("Artist.Album.01.flac").category)
        val book = Classifier.suggest("Interesting.Book.epub")
        assertEquals("Books", book.category)
        assertNull(book.collection)
    }

    @Test fun unknownFileDoesNotGuessDestination() {
        val unknown = Classifier.suggest("some.random.weirdstuff")
        assertEquals("Unsorted", unknown.category)
        assertEquals(0, unknown.confidence)
        assertNull(unknown.collection)
    }
}
