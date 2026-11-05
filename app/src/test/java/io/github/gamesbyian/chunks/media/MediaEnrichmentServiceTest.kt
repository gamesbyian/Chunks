package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.media.providers.MusicBrainzProvider
import io.github.gamesbyian.chunks.media.providers.OpenLibraryProvider
import io.github.gamesbyian.chunks.media.providers.TmdbProvider
import io.github.gamesbyian.chunks.media.providers.TvMazeProvider
import io.github.gamesbyian.chunks.model.MediaLookupOrigin
import io.github.gamesbyian.chunks.model.MediaMetadataCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MediaEnrichmentServiceTest {
    private val consent = MediaPrivacySettings(onlineLookupConsent = true)
    private val online = MediaNetworkState(online = true, metered = false)
    private val clock = FakeClock()

    private fun service(http: MediaHttp, vararg providers: MediaMetadataProvider, cache: MediaQueryCache = InMemoryMediaQueryCache()) =
        MediaEnrichmentService(providers.toList(), http, cache, { clock.now }, clock::sleep)

    private fun rec(name: String) = MediaRecognizer.recognize(name)

    @Test fun noRequestsWithoutConsent() {
        val http = FakeMediaHttp()
        val r = service(http, TvMazeProvider()).lookup(rec("Babylon.5.S02E07.mkv"), MediaPrivacySettings(), online)
        assertEquals(MediaLookupStatus.BLOCKED_CONSENT, r.status); assertTrue(http.requests.isEmpty())
    }

    @Test fun backgroundLookupNeedsAutomaticOptInAndUnmeteredNetwork() {
        val http = FakeMediaHttp()
        val s = service(http, TvMazeProvider())
        val bg = MediaLookupOrigin.CONSENTED_BACKGROUND_ENRICHMENT
        assertEquals(MediaLookupStatus.BLOCKED_CONSENT, s.lookup(rec("Babylon.5.S02E07.mkv"), consent, online, bg).status)
        assertEquals(MediaLookupStatus.BLOCKED_CONSENT, s.lookup(rec("Babylon.5.S02E07.mkv"), consent.copy(automaticLookup = true),
            MediaNetworkState(true, metered = true), bg).status)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun babylon5EpisodeDisambiguatedByEpisodeExistence() {
        val http = FakeMediaHttp().on("/search/shows", body = fixture("tvmaze-search-babylon5.json"))
            .on("/shows/475/episodebynumber?season=2&number=7", body = fixture("tvmaze-episode-475-s02e07.json"))
            .on("/shows/57926/episodebynumber", status = 404)
        val r = service(http, TvMazeProvider()).lookup(rec("Babylon.5.S02E07.Soul.Mates.1080p.mkv"), consent, online)
        assertEquals(MediaLookupStatus.MATCHED, r.status)
        assertEquals("475", r.match!!.ref.externalId); assertEquals("Soul Mates", r.match!!.episodeTitle)
        // Polite pacing between consecutive TVmaze calls.
        assertTrue(clock.sleeps.all { it <= 600 })
        // Nothing but the sanitized title, season and episode left the device.
        assertTrue(http.requests.none { it.contains("1080p") || it.contains("Soul") || it.contains("mkv") })
    }

    @Test fun theOfficeWithoutYearNeedsReviewAndWithYearIsAutomatic() {
        val http = FakeMediaHttp().on("/search/shows", body = fixture("tvmaze-search-the-office.json"))
        val s = service(http, TvMazeProvider())
        val ambiguous = s.lookup(rec("The.Office.S03.720p.WEB-DL"), consent, online)
        assertEquals(MediaLookupStatus.NEEDS_REVIEW, ambiguous.status); assertNull(ambiguous.match)
        assertTrue(ambiguous.alternates.map { it.ref.externalId }.containsAll(listOf("526", "1292")))
        val uk = s.lookup(rec("The.Office.2001.S01.DVDRip"), consent, online)
        assertEquals(MediaLookupStatus.MATCHED, uk.status); assertEquals("1292", uk.match!!.ref.externalId)
    }

    @Test fun missThenNextProviderAndArtworkCompletedByLaterProvider() {
        // A: TVmaze record without image; B: TMDB with poster. Metadata from A, artwork from B.
        val tvmaze = """[{"score":1,"show":{"id":9,"name":"Quiet Show","premiered":"2010-01-01","weight":80,"image":null}}]"""
        val tmdb = """{"results":[{"id":77,"name":"Quiet Show","first_air_date":"2010-03-01","poster_path":"/p.jpg"}]}"""
        val http = FakeMediaHttp().on("api.tvmaze.com/search", body = tvmaze).on("api.themoviedb.org/3/search/tv", body = tmdb)
        val r = service(http, TvMazeProvider(), TmdbProvider { "0123456789abcdef0123456789abcdef" })
            .lookup(rec("Quiet.Show.S01.1080p"), consent, online)
        assertEquals(MediaLookupStatus.MATCHED, r.status)
        assertEquals(MediaMetadataCacheKey("tvmaze", "show", "9"), r.match!!.ref)
        assertEquals("tmdb", r.match!!.artwork.single().provider)
    }

    @Test fun rateLimitCoolsDownProviderAndHonorsRetryAfter() {
        val http = FakeMediaHttp().on("musicbrainz.org", status = 503, headers = mapOf("Retry-After" to "20"))
        val s = service(http, MusicBrainzProvider())
        val r1 = s.lookup(rec("Radiohead - OK Computer (1997) [FLAC]"), consent, online)
        assertEquals(MediaLookupStatus.PROVIDER_COOLDOWN, r1.status); assertEquals(clock.now + 20_000, r1.retryNotBeforeMs)
        val r2 = s.lookup(rec("Radiohead - OK Computer (1997) [FLAC]"), consent, online)
        assertEquals(MediaLookupStatus.PROVIDER_COOLDOWN, r2.status); assertEquals(1, http.requests.size)
        clock.now += 21_000
        http.routes.clear(); http.on("musicbrainz.org", body = fixture("mb-releasegroup-search-ok-computer.json"))
        val q = (MediaQuerySanitizer.manual(MediaKind.MUSIC_ALBUM, "OK Computer", creator = "Radiohead") as MediaQueryDecision.Allowed).query
        val r3 = s.lookup(q, consent, online)
        assertEquals(MediaLookupStatus.MATCHED, r3.status); assertEquals(2, http.requests.size)
    }

    @Test fun positiveAndNegativeResultsAreCachedAndPersisted() {
        val dir = Files.createTempDirectory("media-cache").toFile()
        val file = dir.resolve("queries.json")
        val http = FakeMediaHttp().on("/search/shows?q=Babylon", body = fixture("tvmaze-search-babylon5.json"))
            .on("/search/shows", body = "[]")
        val s = service(http, TvMazeProvider(), cache = FileMediaQueryCache(file))
        val q = (MediaQuerySanitizer.manual(MediaKind.TV_SERIES, "Babylon 5", year = 1993) as MediaQueryDecision.Allowed).query
        assertEquals(MediaLookupStatus.MATCHED, s.lookup(q, consent, online).status)
        val miss = rec("Nothing.Here.S01E01.mkv")
        assertEquals(MediaLookupStatus.NOT_FOUND, s.lookup(miss, consent, online).status)
        assertEquals(2, http.requests.size)
        // New process: persisted cache answers both without network.
        val again = service(http, TvMazeProvider(), cache = FileMediaQueryCache(file))
        val cached = again.lookup(q, consent, online)
        assertEquals(MediaLookupStatus.MATCHED, cached.status); assertTrue(cached.attempts.single().fromCache)
        assertEquals(MediaLookupStatus.NOT_FOUND, again.lookup(miss, consent, online).status)
        assertEquals(2, http.requests.size)
        // Negative cache expires and rechecks.
        clock.now += MediaEnrichmentService.NOT_FOUND_TTL_MS + 1
        again.lookup(miss, consent, online)
        assertEquals(3, http.requests.size)
    }

    @Test fun offlineUsesCacheOrReportsOffline() {
        val http = FakeMediaHttp().on("/search/shows", body = fixture("tvmaze-search-babylon5.json"))
        val s = service(http, TvMazeProvider())
        val q = (MediaQuerySanitizer.manual(MediaKind.TV_SERIES, "Babylon 5", year = 1993) as MediaQueryDecision.Allowed).query
        s.lookup(q, consent, online)
        clock.now += MediaEnrichmentService.FOUND_TTL_MS + 1
        val off = MediaNetworkState(online = false, metered = false)
        val stale = s.lookup(q, consent, off)
        assertEquals(MediaLookupStatus.MATCHED, stale.status); assertEquals("Found (cached, offline)", stale.attempts.single().outcome)
        assertEquals(MediaLookupStatus.OFFLINE, s.lookup(rec("Other.Show.S01E01.mkv"), consent, off).status)
    }

    @Test fun unauthorizedPausesProviderUntilReset() {
        val http = FakeMediaHttp().on("themoviedb", status = 401)
        val s = service(http, TmdbProvider { "0123456789abcdef0123456789abcdef" })
        assertEquals(MediaLookupStatus.NOT_FOUND, s.lookup(rec("Heat.1995.1080p.mkv"), consent, online).status)
        val second = s.lookup(rec("Heat.1995.1080p.BluRay.mkv"), consent, online)
        assertEquals("Paused: check provider key", second.attempts.single().outcome); assertEquals(1, http.requests.size)
        s.resetProviderPause("tmdb")
        s.lookup(rec("Heat.1995.720p.mkv"), consent, online)
        assertEquals(2, http.requests.size)
    }

    @Test fun moviesWithoutUserKeyReportNoProvider() {
        val r = service(FakeMediaHttp(), TvMazeProvider(), TmdbProvider { null }).lookup(rec("Heat.1995.1080p.mkv"), consent, online)
        assertEquals(MediaLookupStatus.NO_PROVIDER, r.status); assertTrue(r.message.contains("key"))
    }

    @Test fun disabledProvidersAreNeverCalled() {
        val http = FakeMediaHttp()
        val r = service(http, OpenLibraryProvider()).lookup(rec("Frank Herbert - Dune.epub"), consent.copy(disabledProviders = setOf("openlibrary")), online)
        assertEquals(MediaLookupStatus.NO_PROVIDER, r.status); assertTrue(http.requests.isEmpty())
    }

    @Test fun bookLookupMatchesAuthorAndTitle() {
        val http = FakeMediaHttp().on("search.json", body = fixture("ol-search-dune.json"))
        val r = service(http, OpenLibraryProvider()).lookup(rec("Frank Herbert - Dune.epub"), consent, online)
        assertEquals(MediaLookupStatus.MATCHED, r.status); assertEquals("OL893414W", r.match!!.ref.externalId)
        assertTrue(http.requests.single().contains("title=Dune&author=Frank%20Herbert"))
    }

    @Test fun forbiddenFromKeylessProviderIsTemporaryCooldownNotKeyPause() {
        val http = FakeMediaHttp().on("api.tvmaze.com", status = 403)
        val s = service(http, TvMazeProvider())
        val r = s.lookup(rec("Babylon.5.S02E07.mkv"), consent, online)
        assertEquals(MediaLookupStatus.PROVIDER_COOLDOWN, r.status)
        assertEquals("Access refused; will retry later", r.attempts.single().outcome)
        clock.now += 10 * 60_000L + 1
        http.routes.clear(); http.on("/search/shows", body = fixture("tvmaze-search-babylon5.json"))
            .on("/shows/475/episodebynumber", body = fixture("tvmaze-episode-475-s02e07.json")).on("/shows/57926/", status = 404)
        assertEquals(MediaLookupStatus.MATCHED, s.lookup(rec("Babylon.5.S02E07.mkv"), consent, online).status)
    }
}
