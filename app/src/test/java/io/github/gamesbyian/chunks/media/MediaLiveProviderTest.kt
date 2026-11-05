package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.media.artwork.ArtworkDiskCache
import io.github.gamesbyian.chunks.media.artwork.ArtworkLoadResult
import io.github.gamesbyian.chunks.media.artwork.ArtworkRepository
import io.github.gamesbyian.chunks.media.providers.MusicBrainzProvider
import io.github.gamesbyian.chunks.media.providers.OpenLibraryProvider
import io.github.gamesbyian.chunks.media.providers.TvMazeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

/**
 * Opt-in *live* acceptance (CHUNKS_LIVE_MEDIA=1): real HTTPS client, real public providers and
 * real artwork bytes. A handful of polite, human-scale requests; never run automatically in PR CI.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MediaLiveProviderTest {
    @Before fun gate() { assumeTrue("Set CHUNKS_LIVE_MEDIA=1 for live provider checks", System.getenv("CHUNKS_LIVE_MEDIA") == "1") }

    private val http = UrlConnectionMediaHttp()
    private val consent = MediaPrivacySettings(onlineLookupConsent = true)
    private val online = MediaNetworkState(true, false)
    private val providers = listOf(TvMazeProvider(), MusicBrainzProvider(), OpenLibraryProvider())
    private val service = MediaEnrichmentService(providers, http, InMemoryMediaQueryCache())
    private val art = ArtworkRepository(http, ArtworkDiskCache(Files.createTempDirectory("live-art").toFile()),
        hostPolicyFor = { id: String -> providers.firstOrNull { it.info.id == id }?.info?.artworkHostPolicy })

    private fun squareArt(c: MediaCandidate): ArtworkLoadResult.Loaded {
        val r = art.load(c.artwork, ArtworkRole.SQUARE, allowNetwork = true)
        assertTrue("artwork: $r", r is ArtworkLoadResult.Loaded)
        return (r as ArtworkLoadResult.Loaded).also { assertEquals(384, it.bitmap.width); assertEquals(384, it.bitmap.height) }
    }

    @Test fun tvEpisodeEndToEnd() {
        val r = service.lookup(MediaRecognizer.recognize("Babylon.5.S02E07.Soul.Mates.1080p.WEB-DL.mkv"), consent, online)
        println("LIVE tvmaze: ${r.status} ${r.match?.ref} ep=${r.match?.episodeTitle} attempts=${r.attempts}")
        assertEquals(MediaLookupStatus.MATCHED, r.status)
        assertEquals("475", r.match!!.ref.externalId); assertEquals("Soul Mates", r.match!!.episodeTitle)
        println("LIVE tvmaze art: ${squareArt(r.match!!).source.url}")
    }

    @Test fun ambiguousShowNeedsReview() {
        val r = service.lookup(MediaRecognizer.recognize("The.Office.S03.720p.WEB-DL"), consent, online)
        println("LIVE office: ${r.status} alternates=${r.alternates.map { it.ref.externalId to it.year }}")
        assertEquals(MediaLookupStatus.NEEDS_REVIEW, r.status)
    }

    @Test fun albumEndToEndWithCoverArtArchiveRedirects() {
        val q = (MediaQuerySanitizer.manual(MediaKind.MUSIC_ALBUM, "OK Computer", 1997, creator = "Radiohead") as MediaQueryDecision.Allowed).query
        val r = service.lookup(q, consent, online)
        println("LIVE musicbrainz: ${r.status} ${r.match?.ref}")
        assertEquals(MediaLookupStatus.MATCHED, r.status)
        println("LIVE caa art: ${squareArt(r.match!!).source.url}")
    }

    @Test fun bookEndToEnd() {
        val r = service.lookup(MediaRecognizer.recognize("Frank Herbert - Dune.epub"), consent, online)
        println("LIVE openlibrary: ${r.status} ${r.match?.ref} ${r.match?.year}")
        assertEquals(MediaLookupStatus.MATCHED, r.status); assertEquals("OL893414W", r.match!!.ref.externalId)
        println("LIVE ol art: ${squareArt(r.match!!).source.url}")
    }
}
