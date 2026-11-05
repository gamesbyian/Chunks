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
 * TVmaze public API: no key, CC BY-SA 4.0 with attribution/link-back, "at least 20 calls / 10 s"
 * per IP (we use 1 call per 600 ms). Show images are posters; episode images are landscape stills.
 * Verified 2026-11-05 at https://www.tvmaze.com/api.
 */
class TvMazeProvider : MediaMetadataProvider {
    override val info = MediaProviderInfo(
        id = ID, displayName = "TVmaze",
        kinds = setOf(MediaKind.TV_SERIES, MediaKind.TV_SEASON, MediaKind.TV_EPISODE),
        license = "CC BY-SA 4.0", attribution = "TV data and images from TVmaze (CC BY-SA)",
        termsUrl = "https://www.tvmaze.com/api", minIntervalMs = 600,
        apiHosts = setOf("api.tvmaze.com"), artworkHosts = setOf("static.tvmaze.com"))

    override fun search(query: MediaLookupQuery, http: MediaHttp): MediaProviderOutcome {
        val (json, failure) = ProviderHttp.fetchJson(http, info, "https://api.tvmaze.com/search/shows?q=${ProviderHttp.enc(query.title)}")
        if (failure != null) return failure
        val items = (json as? Json.Arr)?.items ?: return MediaProviderOutcome.BadSchema("Expected array")
        // TVmaze "weight" (0..100 popularity) is a better tiebreaker than its fuzzy search score.
        val candidates = items.mapNotNull { item -> item["show"]?.let { show(it, it["weight"]?.double?.div(100.0)) } }
        return if (candidates.isEmpty()) MediaProviderOutcome.NotFound else MediaProviderOutcome.Found(candidates)
    }

    override fun refine(candidate: MediaCandidate, query: MediaLookupQuery, http: MediaHttp): MediaCandidate {
        val id = candidate.ref.externalId
        val season = query.season ?: return candidate
        val episode = query.episode
        if (episode != null) {
            val (json, failure) = ProviderHttp.fetchJson(http, info,
                "https://api.tvmaze.com/shows/${ProviderHttp.enc(id)}/episodebynumber?season=$season&number=$episode")
            if (failure != null || json == null) return candidate
            val name = json["name"]?.string ?: return candidate
            val still = image(json["image"], ArtworkRole.BANNER)
            return candidate.copy(kind = MediaKind.TV_EPISODE, season = json["season"]?.int ?: season,
                episode = json["number"]?.int ?: episode, episodeTitle = name,
                overview = ProviderHttp.plain(json["summary"]?.string) ?: candidate.overview,
                artwork = candidate.artwork + listOfNotNull(still),
                attribution = candidate.attribution.copy(pageUrl = json["url"]?.string?.takeIf { it.startsWith("https://www.tvmaze.com/") } ?: candidate.attribution.pageUrl))
        }
        val (json, failure) = ProviderHttp.fetchJson(http, info, "https://api.tvmaze.com/shows/${ProviderHttp.enc(id)}/seasons")
        if (failure != null || json == null) return candidate
        val s = json.array.firstOrNull { it["number"]?.int == season } ?: return candidate
        // Season-specific art is preferred only once that exact season is established.
        val poster = image(s["image"], ArtworkRole.POSTER)
        return candidate.copy(kind = MediaKind.TV_SEASON, season = season,
            artwork = listOfNotNull(poster) + candidate.artwork)
    }

    private fun show(s: Json, score: Double?): MediaCandidate? {
        val id = s["id"]?.long ?: return null
        val name = s["name"]?.string?.takeIf { it.isNotBlank() } ?: return null
        val externals = s["externals"]
        val cross = listOfNotNull(
            externals?.get("imdb")?.string?.takeIf { it.matches(Regex("tt\\d{5,10}")) }?.let { MediaMetadataCacheKey("imdb", "title", it) },
            externals?.get("thetvdb")?.long?.let { MediaMetadataCacheKey("thetvdb", "series", it.toString()) })
        val network = s["network"]?.get("name")?.string ?: s["webChannel"]?.get("name")?.string
        return MediaCandidate(
            ref = MediaMetadataCacheKey(ID, "show", id.toString()), kind = MediaKind.TV_SERIES, title = name,
            year = ProviderHttp.year(s["premiered"]?.string),
            creators = listOfNotNull(network),
            overview = ProviderHttp.plain(s["summary"]?.string),
            genres = s["genres"].let { g -> g?.array?.mapNotNull { it.string } ?: emptyList() }.take(5),
            artwork = listOfNotNull(image(s["image"], ArtworkRole.POSTER)),
            attribution = MediaAttribution("TVmaze", info.license,
                s["url"]?.string?.takeIf { it.startsWith("https://www.tvmaze.com/") }),
            providerScore = score, crossReferences = cross)
    }

    private fun image(img: Json?, role: ArtworkRole): ArtworkCandidate? {
        val url = img?.get("original")?.string ?: img?.get("medium")?.string ?: return null
        if (!url.startsWith("https://static.tvmaze.com/")) return null
        return ArtworkCandidate(ID, url, role)
    }

    companion object { const val ID = "tvmaze" }
}
