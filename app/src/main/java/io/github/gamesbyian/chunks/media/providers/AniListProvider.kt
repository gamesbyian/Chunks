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
 * AniList public GraphQL (POST https://graphql.anilist.co). Free for non-commercial use; no bulk
 * collection or storage-backend use; nominal 90 req/min, currently degraded to 30/min (we pace 2.5 s).
 * **Off by default**: used only after the user turns it on, and only for TV lookups with local anime
 * naming evidence or explicit manual searches. Adult entries are excluded server- and client-side.
 */
class AniListProvider : MediaMetadataProvider {
    override val info = MediaProviderInfo(
        id = ID, displayName = "AniList",
        kinds = setOf(MediaKind.TV_SERIES, MediaKind.TV_SEASON, MediaKind.TV_EPISODE),
        license = "AniList API terms (non-commercial)", attribution = "Anime data and covers from AniList (unofficial use)",
        termsUrl = "https://docs.anilist.co/guide/terms-of-use", minIntervalMs = 2_500,
        apiHosts = setOf("graphql.anilist.co"), artworkHosts = setOf("s4.anilist.co"),
        enabledByDefault = false)

    override fun supports(query: MediaLookupQuery) = query.kind in info.kinds && (query.animeHint || query.manual)

    override fun search(query: MediaLookupQuery, http: MediaHttp): MediaProviderOutcome {
        val body = Json.obj("query" to Json.Str(QUERY), "variables" to Json.obj("s" to Json.Str(query.title))).encode()
        val (json, failure) = ProviderHttp.fetchJson(http, info, "https://graphql.anilist.co", postBody = body)
        if (failure != null) return failure
        if (json?.get("errors")?.array?.isNotEmpty() == true) return MediaProviderOutcome.BadSchema("Provider reported an error")
        val media = json?.get("data")?.get("Page")?.get("media") as? Json.Arr ?: return MediaProviderOutcome.BadSchema("Missing media")
        val candidates = media.items.mapNotNull { m ->
            if (m["isAdult"]?.bool != false) return@mapNotNull null
            val id = m["id"]?.long ?: return@mapNotNull null
            val t = m["title"]
            val english = t?.get("english")?.string; val romaji = t?.get("romaji")?.string; val native = t?.get("native")?.string
            val title = (english ?: romaji)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cover = (m["coverImage"]?.get("extraLarge")?.string ?: m["coverImage"]?.get("large")?.string)
                ?.takeIf { it.startsWith("https://s4.anilist.co/") }
            MediaCandidate(
                ref = MediaMetadataCacheKey(ID, "anime", id.toString()), kind = MediaKind.TV_SERIES, title = title,
                originalTitle = native, year = m["startDate"]?.get("year")?.int?.takeIf { it in 1900..2100 },
                genres = listOfNotNull(m["format"]?.string),
                artwork = listOfNotNull(cover?.let { ArtworkCandidate(ID, it, ArtworkRole.POSTER) }),
                attribution = MediaAttribution("AniList", info.license, "https://anilist.co/anime/$id"),
                crossReferences = listOfNotNull(m["idMal"]?.long?.let { MediaMetadataCacheKey("myanimelist", "anime", it.toString()) }),
                aliases = (listOfNotNull(romaji, native) + (m["synonyms"]?.array?.mapNotNull { it.string } ?: emptyList()))
                    .filter { it != title }.distinct().take(12))
        }
        return if (candidates.isEmpty()) MediaProviderOutcome.NotFound else MediaProviderOutcome.Found(candidates)
    }

    companion object {
        const val ID = "anilist"
        private const val QUERY = "query(\$s:String){Page(perPage:6){media(search:\$s,type:ANIME,isAdult:false,sort:SEARCH_MATCH)" +
            "{id idMal title{romaji english native} synonyms startDate{year} format isAdult siteUrl coverImage{extraLarge large}}}}"
    }
}
