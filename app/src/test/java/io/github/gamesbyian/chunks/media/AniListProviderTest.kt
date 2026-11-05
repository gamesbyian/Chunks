package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.media.providers.AniListProvider
import io.github.gamesbyian.chunks.media.providers.TvMazeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AniList GraphQL adapter on real responses recorded 2026-11-05. */
class AniListProviderTest {
    private val consent = MediaPrivacySettings(onlineLookupConsent = true)
    private val online = MediaNetworkState(true, false)
    private val clock = FakeClock()

    @Test fun parsesRealResponseWithAliasesCoversAndNoAdult() {
        val http = FakeMediaHttp().on("graphql.anilist.co", body = fixture("anilist-search-frieren.json"))
        val q = (MediaQuerySanitizer.manual(MediaKind.TV_SERIES, "Frieren") as MediaQueryDecision.Allowed).query
        val out = AniListProvider().search(q, http) as MediaProviderOutcome.Found
        val first = out.candidates.first()
        assertEquals("154587", first.ref.externalId); assertEquals("Frieren: Beyond Journey’s End", first.title)
        assertEquals("葬送のフリーレン", first.originalTitle); assertTrue("Sousou no Frieren" in first.aliases)
        assertTrue(first.artwork.single().url.startsWith("https://s4.anilist.co/"))
        assertTrue(http.postBodies.single().contains("isAdult:false") && http.postBodies.single().contains("\"s\":\"Frieren\""))
        val adult = """{"data":{"Page":{"media":[{"id":1,"isAdult":true,"title":{"romaji":"X"}}]}}}"""
        assertEquals(MediaProviderOutcome.NotFound, AniListProvider().search(q, FakeMediaHttp().on("graphql", body = adult)))
        assertEquals(MediaProviderOutcome.NotFound, AniListProvider().search(q, FakeMediaHttp().on("graphql", body = fixture("anilist-search-nomatch.json"))))
    }

    @Test fun prefixOnlyTitlesAndSeasonEntriesStaySuggested() {
        val http = FakeMediaHttp().on("graphql.anilist.co", body = fixture("anilist-search-frieren.json"))
        val q = (MediaQuerySanitizer.manual(MediaKind.TV_SERIES, "Frieren") as MediaQueryDecision.Allowed).query
        val m = MediaCandidateMatcher.match(q, (AniListProvider().search(q, http) as MediaProviderOutcome.Found).candidates)
        assertTrue(m is MediaMatchDecision.Ambiguous)
        // The exact romaji title is a confident match for the first season only.
        val exact = (MediaQuerySanitizer.manual(MediaKind.TV_SERIES, "Sousou no Frieren", 2023) as MediaQueryDecision.Allowed).query
        val e = MediaCandidateMatcher.match(exact, (AniListProvider().search(exact, http) as MediaProviderOutcome.Found).candidates)
        assertEquals("154587", (e as MediaMatchDecision.Confident).best.candidate.ref.externalId)
    }

    @Test fun offByDefaultAndOnlyForAnimeEvidenceOrManualSearch() {
        val http = FakeMediaHttp().on("graphql.anilist.co", body = fixture("anilist-search-frieren.json"))
        val s = MediaEnrichmentService(listOf(AniListProvider()), http, InMemoryMediaQueryCache(), { clock.now }, clock::sleep)
        val anime = MediaRecognizer.recognize("[SubsPlease] Sousou no Frieren - 12 (1080p) [ABCD1234].mkv")
        assertEquals(MediaLookupStatus.NO_PROVIDER, s.lookup(anime, consent, online).status)
        assertTrue(http.requests.isEmpty())
        val on = consent.copy(enabledProviders = setOf("anilist"))
        assertTrue((MediaQuerySanitizer.fromRecognition(anime) as MediaQueryDecision.Allowed).query.animeHint)
        assertEquals(MediaLookupStatus.MATCHED, s.lookup(anime, on, online).status)
        // A western show name without anime evidence never goes to AniList.
        val western = MediaRecognizer.recognize("Babylon.5.S02E07.mkv")
        assertFalse((MediaQuerySanitizer.fromRecognition(western) as MediaQueryDecision.Allowed).query.animeHint)
        assertEquals(MediaLookupStatus.NO_PROVIDER, s.lookup(western, on, online).status)
        assertEquals(1, http.requests.size)
        assertTrue(on.providerOn(AniListProvider().info)); assertFalse(consent.providerOn(AniListProvider().info))
        assertTrue(consent.providerOn(TvMazeProvider().info))
    }
}
