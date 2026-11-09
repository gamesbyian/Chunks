package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.media.providers.MusicBrainzProvider
import io.github.gamesbyian.chunks.media.providers.OpenLibraryProvider
import io.github.gamesbyian.chunks.media.providers.TmdbProvider
import io.github.gamesbyian.chunks.media.providers.TvMazeProvider
import io.github.gamesbyian.chunks.model.MediaMetadataCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adapters parse *recorded real responses* (2026-11-05) and map HTTP failures to typed outcomes. */
class MediaProviderFixtureTest {
    private fun q(kind: MediaKind, title: String, year: Int? = null, season: Int? = null, episode: Int? = null, creator: String? = null) =
        (MediaQuerySanitizer.manual(kind, title, year, season, episode, creator) as MediaQueryDecision.Allowed).query

    @Test fun tvmazeSearchParsesTypedShowsWithPostersAndCrossReferences() {
        val http = FakeMediaHttp().on("/search/shows", body = fixture("tvmaze-search-babylon5.json"))
        val out = TvMazeProvider().search(q(MediaKind.TV_EPISODE, "Babylon 5"), http) as MediaProviderOutcome.Found
        val b5 = out.candidates.first()
        assertEquals(MediaMetadataCacheKey("tvmaze", "show", "475"), b5.ref)
        assertEquals(1993, b5.year); assertEquals(MediaKind.TV_SERIES, b5.kind)
        assertEquals(ArtworkRole.POSTER, b5.artwork.single().role)
        assertTrue(b5.artwork.single().url.startsWith("https://static.tvmaze.com/"))
        assertTrue(MediaMetadataCacheKey("imdb", "title", "tt0105946") in b5.crossReferences)
        assertEquals("https://api.tvmaze.com/search/shows?q=Babylon%205", http.requests.single())
    }

    @Test fun tvmazeRefineEpisodeAndSeason() {
        val http = FakeMediaHttp().on("/search/shows", body = fixture("tvmaze-search-babylon5.json"))
            .on("episodebynumber?season=2&number=7", body = fixture("tvmaze-episode-475-s02e07.json"))
            .on("episodebynumber?season=9", status = 404, body = fixture("tvmaze-episode-475-s09e01-404.json"))
            .on("/shows/475/seasons", body = fixture("tvmaze-seasons-475.json"))
        val p = TvMazeProvider()
        val show = (p.search(q(MediaKind.TV_EPISODE, "Babylon 5"), http) as MediaProviderOutcome.Found).candidates.first()
        val ep = p.refine(show, q(MediaKind.TV_EPISODE, "Babylon 5", season = 2, episode = 7), http)
        assertEquals("Soul Mates", ep.episodeTitle); assertEquals(MediaKind.TV_EPISODE, ep.kind)
        assertEquals("https://www.tvmaze.com/episodes/43454/babylon-5-2x07-soul-mates", ep.attribution.pageUrl)
        assertEquals(show, p.refine(show, q(MediaKind.TV_EPISODE, "Babylon 5", season = 9, episode = 1), http))
        val season = p.refine(show, q(MediaKind.TV_SEASON, "Babylon 5", season = 2), http)
        assertEquals(MediaKind.TV_SEASON, season.kind)
        assertTrue(season.artwork.first().url.endsWith("533219.jpg"))
    }

    @Test fun tvmazeEmptyArrayIsNotFound() {
        val http = FakeMediaHttp().on("/search/shows", body = fixture("tvmaze-search-nomatch.json"))
        assertEquals(MediaProviderOutcome.NotFound, TvMazeProvider().search(q(MediaKind.TV_SERIES, "zzqxv"), http))
    }

    @Test fun musicbrainzReleaseGroupsUseCoverArtArchiveSquare() {
        val http = FakeMediaHttp().on("musicbrainz.org/ws/2/release-group", body = fixture("mb-releasegroup-search-ok-computer.json"))
        val out = MusicBrainzProvider().search(q(MediaKind.MUSIC_ALBUM, "OK Computer", creator = "Radiohead"), http) as MediaProviderOutcome.Found
        val c = out.candidates.single()
        assertEquals("b1392450-e666-3926-a536-22c65f834433", c.ref.externalId); assertEquals("release-group", c.ref.entityType)
        assertEquals(listOf("Radiohead"), c.creators); assertEquals(1997, c.year)
        assertEquals("https://coverartarchive.org/release-group/b1392450-e666-3926-a536-22c65f834433/front-500", c.artwork.single().url)
        assertEquals(ArtworkRole.SQUARE, c.artwork.single().role)
        assertTrue(http.requests.single().contains("releasegroup%3A%22OK%20Computer%22%20AND%20artist%3A%22Radiohead%22"))
        assertTrue(http.requests.single().contains("fmt=json"))
    }

    @Test fun musicbrainzEditionsStayDistinctAndOriginalWins() {
        val http = FakeMediaHttp().on("release-group", body = fixture("mb-releasegroup-search-abbey-road.json"))
        val query = q(MediaKind.MUSIC_ALBUM, "Abbey Road", 1969, creator = "The Beatles")
        val out = MusicBrainzProvider().search(query, http) as MediaProviderOutcome.Found
        val m = MediaCandidateMatcher.match(query, out.candidates) as MediaMatchDecision.Confident
        assertEquals("9162580e-5df4-32de-80cc-f45a8d8a9b1d", m.best.candidate.ref.externalId)
        assertTrue(out.candidates.any { it.title == "Abbey Road Remix" })
    }

    @Test fun musicbrainzNoMatchAndLuceneInjectionEscaped() {
        val http = FakeMediaHttp().on("release-group", body = fixture("mb-releasegroup-search-nomatch.json"))
        assertEquals(MediaProviderOutcome.NotFound, MusicBrainzProvider().search(q(MediaKind.MUSIC_ALBUM, "A\" OR artist:\"x", creator = "y"), http))
        assertTrue(http.requests.single().contains("A%5C%22%20OR%20artist%5C%3A%5C%22x"))
    }

    @Test fun openLibraryWorksWithCoversAndAuthors() {
        val http = FakeMediaHttp().on("openlibrary.org/search.json", body = fixture("ol-search-dune.json"))
        val out = OpenLibraryProvider().search(q(MediaKind.BOOK, "Dune", creator = "Frank Herbert"), http) as MediaProviderOutcome.Found
        val dune = out.candidates.first()
        assertEquals("OL893414W", dune.ref.externalId); assertEquals(listOf("Frank Herbert"), dune.creators); assertEquals(1965, dune.year)
        assertEquals("https://covers.openlibrary.org/b/id/11481354-L.jpg?default=false", dune.artwork.single().url)
        val m = MediaCandidateMatcher.match(q(MediaKind.BOOK, "Dune", creator = "Frank Herbert"), out.candidates) as MediaMatchDecision.Confident
        assertEquals("Dune", m.best.candidate.title)
        assertFalse(http.requests.single().contains("isbn"))
    }

    @Test fun openLibraryCarrieNovelDoesNotMatchOmnibus() {
        val http = FakeMediaHttp().on("search.json", body = fixture("ol-search-carrie.json"))
        val query = q(MediaKind.BOOK, "Carrie", creator = "Stephen King")
        val out = OpenLibraryProvider().search(query, http) as MediaProviderOutcome.Found
        val m = MediaCandidateMatcher.match(query, out.candidates) as MediaMatchDecision.Confident
        assertEquals("OL81626W", m.best.candidate.ref.externalId)
    }

    @Test fun tmdbRequiresUserCredentialAndUsesBearerOrQueryKey() {
        val none = TmdbProvider { null }
        assertFalse(none.isConfigured())
        assertEquals(MediaProviderOutcome.Unauthorized, none.search(q(MediaKind.MOVIE, "Heat"), FakeMediaHttp()))
        val body = """{"page":1,"results":[{"adult":false,"id":949,"title":"Heat","original_title":"Heat","release_date":"1995-12-15",
            "poster_path":"/abc123.jpg","backdrop_path":"/def456.jpg","overview":"Obsessive master thief.","popularity":40.0},
            {"adult":true,"id":1,"title":"Heat","release_date":"1995-01-01"}]}"""
        val http = FakeMediaHttp().on("api.themoviedb.org/3/search/movie", body = body)
        val v3 = TmdbProvider { "0123456789abcdef0123456789abcdef" }
        val out = v3.search(q(MediaKind.MOVIE, "Heat", 1995), http) as MediaProviderOutcome.Found
        assertEquals(1, out.candidates.size) // adult record excluded
        assertEquals("https://image.tmdb.org/t/p/w500/abc123.jpg", out.candidates.single().artwork.first().url)
        assertTrue(http.requests.single().contains("api_key=") && http.requests.single().contains("year=1995"))
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJ4In0.c2lnbmF0dXJl"
        val http2 = FakeMediaHttp().on("search/movie", body = body)
        TmdbProvider { jwt }.search(q(MediaKind.MOVIE, "Heat"), http2)
        assertFalse(http2.requests.single().contains("api_key"))
        assertEquals("Bearer $jwt", http2.requestHeaders.single()["Authorization"])
    }

    @Test fun tmdbAllowsSafeFilenameCharactersButRejectsTraversalAndQueryInjection() {
        val body = """{"results":[
            {"id":1,"title":"Hackers","release_date":"1995-09-15",
                "poster_path":"/a_b-c.jpg","backdrop_path":"/a_B-2.png"},
            {"id":2,"title":"Hackers","release_date":"1995-09-15",
                "poster_path":"/../private.jpg","backdrop_path":"/abc.jpg?query=1"},
            {"id":3,"title":"Hackers","release_date":"1995-09-15",
                "poster_path":"//other.example/evil.jpg","backdrop_path":"https://evil.example/z.jpg"}
        ]}"""
        val http = FakeMediaHttp().on("search/movie", body = body)
        val out = TmdbProvider { "0123456789abcdef0123456789abcdef" }
            .search(q(MediaKind.MOVIE, "Hackers", 1995), http) as MediaProviderOutcome.Found
        assertEquals(3, out.candidates.size)
        assertEquals(listOf(
            "https://image.tmdb.org/t/p/w500/a_b-c.jpg",
            "https://image.tmdb.org/t/p/w780/a_B-2.png"),
            out.candidates[0].artwork.map { it.url })
        assertTrue(out.candidates[1].artwork.isEmpty())
        assertTrue(out.candidates[2].artwork.isEmpty())
        assertTrue(http.requests.single().startsWith("https://api.themoviedb.org/"))
    }

    @Test fun httpStatusesMapToTypedOutcomes() {
        val p = TvMazeProvider(); val query = q(MediaKind.TV_SERIES, "X")
        assertEquals(MediaProviderOutcome.NotFound, p.search(query, FakeMediaHttp().on("search", status = 404)))
        assertEquals(MediaProviderOutcome.Unauthorized, p.search(query, FakeMediaHttp().on("search", status = 403)))
        assertEquals(MediaProviderOutcome.RateLimited(7_000), p.search(query, FakeMediaHttp().on("search", status = 429, headers = mapOf("Retry-After" to "7"))))
        assertEquals(MediaProviderOutcome.RateLimited(60_000), p.search(query, FakeMediaHttp().on("search", status = 503)))
        assertTrue((p.search(query, FakeMediaHttp().on("search", status = 500)) as MediaProviderOutcome.Failed).retryable)
        assertTrue(p.search(query, FakeMediaHttp().on("search", body = "<html>")) is MediaProviderOutcome.BadSchema)
        assertTrue(p.search(query, FakeMediaHttp().on("search", body = "{\"a\":1}")) is MediaProviderOutcome.BadSchema)
        val off = FakeMediaHttp().apply { offline = true }
        assertEquals(MediaProviderOutcome.Failed("Offline", true), p.search(query, off))
    }

    @Test fun providerArtworkFromForeignHostsIsDropped() {
        val body = """[{"score":1,"show":{"id":1,"name":"X","image":{"original":"https://evil.example/x.jpg"}}}]"""
        val c = (TvMazeProvider().search(q(MediaKind.TV_SERIES, "X"), FakeMediaHttp().on("search", body = body)) as MediaProviderOutcome.Found).candidates.single()
        assertTrue(c.artwork.isEmpty())
    }

    @Test fun hostPolicySuffixRules() {
        val p = mediaHostPolicy(listOf("coverartarchive.org", ".archive.org"))
        assertTrue(p.allows("coverartarchive.org")); assertTrue(p.allows("ia801.us.archive.org")); assertTrue(p.allows("archive.org"))
        assertFalse(p.allows("evilarchive.org")); assertFalse(p.allows("coverartarchive.org.evil.com"))
    }
}
