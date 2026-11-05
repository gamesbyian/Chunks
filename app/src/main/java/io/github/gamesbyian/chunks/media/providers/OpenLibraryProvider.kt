package io.github.gamesbyian.chunks.media.providers

import io.github.gamesbyian.chunks.media.ArtworkCandidate
import io.github.gamesbyian.chunks.media.ArtworkRole
import io.github.gamesbyian.chunks.media.Json
import io.github.gamesbyian.chunks.media.MediaAttribution
import io.github.gamesbyian.chunks.media.MediaCandidate
import io.github.gamesbyian.chunks.media.MediaHttp
import io.github.gamesbyian.chunks.media.MediaKind
import io.github.gamesbyian.chunks.media.MediaLookupQuery
import io.github.gamesbyian.chunks.media.MediaMetadataProvider
import io.github.gamesbyian.chunks.media.MediaProviderInfo
import io.github.gamesbyian.chunks.media.MediaProviderOutcome
import io.github.gamesbyian.chunks.media.ProviderHttp
import io.github.gamesbyian.chunks.model.MediaMetadataCacheKey

/**
 * Open Library search (work level) + Covers API. No key; low-volume human-initiated lookups only:
 * 1 req/s unidentified, identified User-Agent recommended. Not to be used as bulk backend.
 * We request only a few small fields and `?default=false` so missing covers 404 cleanly.
 */
class OpenLibraryProvider : MediaMetadataProvider {
    override val info = MediaProviderInfo(
        id = ID, displayName = "Open Library",
        kinds = setOf(MediaKind.BOOK, MediaKind.AUDIOBOOK),
        license = "Open Library data; covers © their owners",
        attribution = "Book data and covers from Open Library (Internet Archive)",
        termsUrl = "https://openlibrary.org/developers/api", minIntervalMs = 1_100,
        apiHosts = setOf("openlibrary.org"),
        artworkHosts = setOf("covers.openlibrary.org", ".archive.org"))

    override fun search(query: MediaLookupQuery, http: MediaHttp): MediaProviderOutcome {
        val author = query.creator?.let { "&author=${ProviderHttp.enc(it)}" } ?: ""
        val (json, failure) = ProviderHttp.fetchJson(http, info,
            "https://openlibrary.org/search.json?title=${ProviderHttp.enc(query.title)}$author&limit=6" +
                "&fields=key,title,author_name,first_publish_year,cover_i,edition_count")
        if (failure != null) return failure
        val docs = json?.get("docs") as? Json.Arr ?: return MediaProviderOutcome.BadSchema("Missing docs")
        val candidates = docs.items.mapNotNull { d ->
            val key = d["key"]?.string?.takeIf { it.matches(Regex("/works/OL\\d+W")) } ?: return@mapNotNull null
            val title = d["title"]?.string?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = d["cover_i"]?.long?.takeIf { it > 0 }
            val olid = key.removePrefix("/works/")
            MediaCandidate(
                ref = MediaMetadataCacheKey(ID, "work", olid), kind = MediaKind.BOOK, title = title,
                year = d["first_publish_year"]?.int?.takeIf { it in 1000..2100 }?.takeIf { it >= 1870 },
                creators = d["author_name"]?.array?.mapNotNull { it.string }?.take(4) ?: emptyList(),
                artwork = listOfNotNull(cover?.let { ArtworkCandidate(ID, "https://covers.openlibrary.org/b/id/$it-L.jpg?default=false", ArtworkRole.POSTER) }),
                attribution = MediaAttribution("Open Library", info.license, "https://openlibrary.org$key"),
                // Edition count is only a tiebreaker between equally matching works.
                providerScore = d["edition_count"]?.long?.let { minOf(1.0, it / 200.0) })
        }
        return if (candidates.isEmpty()) MediaProviderOutcome.NotFound else MediaProviderOutcome.Found(candidates)
    }

    companion object { const val ID = "openlibrary" }
}
