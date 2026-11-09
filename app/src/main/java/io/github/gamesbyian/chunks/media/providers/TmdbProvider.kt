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
 * TMDB v3 movie/TV search. Requires the **user's own** API key or read-access token: Chunks never
 * ships a shared credential. TMDB's free tier is noncommercial with mandatory attribution
 * ("This product uses the TMDB API but is not endorsed or certified by TMDB").
 */
class TmdbProvider(private val credential: () -> String?) : MediaMetadataProvider {
    override val info = MediaProviderInfo(
        id = ID, displayName = "TMDB",
        kinds = setOf(MediaKind.MOVIE, MediaKind.TV_SERIES, MediaKind.TV_SEASON, MediaKind.TV_EPISODE),
        license = "TMDB terms (noncommercial, attribution required)",
        attribution = "This product uses the TMDB API but is not endorsed or certified by TMDB",
        termsUrl = "https://www.themoviedb.org/api-terms-of-use", minIntervalMs = 250,
        apiHosts = setOf("api.themoviedb.org"), artworkHosts = setOf("image.tmdb.org"),
        requiresUserKey = true)

    override fun isConfigured(): Boolean = credential()?.let { isPlausibleCredential(it) } == true

    override fun search(query: MediaLookupQuery, http: MediaHttp): MediaProviderOutcome {
        val key = credential()?.trim()?.takeIf { isPlausibleCredential(it) } ?: return MediaProviderOutcome.Unauthorized
        val movie = query.kind == MediaKind.MOVIE
        val path = if (movie) "search/movie" else "search/tv"
        val yearParam = query.year?.let { if (movie) "&year=$it" else "&first_air_date_year=$it" } ?: ""
        // A v4 read-access token is a JWT and goes in the header; a v3 key is a query parameter.
        val bearer = key.startsWith("eyJ")
        val auth = if (bearer) "" else "&api_key=${ProviderHttp.enc(key)}"
        val headers = if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap()
        val (json, failure) = ProviderHttp.fetchJson(http, info,
            "https://api.themoviedb.org/3/$path?query=${ProviderHttp.enc(query.title)}$yearParam&include_adult=false$auth", headers)
        if (failure != null) return failure
        val results = json?.get("results") as? Json.Arr ?: return MediaProviderOutcome.BadSchema("Missing results")
        val candidates = results.items.take(10).mapNotNull { r ->
            val id = r["id"]?.long ?: return@mapNotNull null
            if (r["adult"]?.bool == true) return@mapNotNull null
            val title = (if (movie) r["title"] else r["name"])?.string?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val original = (if (movie) r["original_title"] else r["original_name"])?.string?.takeIf { it != title }
            val date = (if (movie) r["release_date"] else r["first_air_date"])?.string
            MediaCandidate(
                ref = MediaMetadataCacheKey(ID, if (movie) "movie" else "tv", id.toString()),
                kind = if (movie) MediaKind.MOVIE else MediaKind.TV_SERIES, title = title, originalTitle = original,
                year = ProviderHttp.year(date), overview = ProviderHttp.plain(r["overview"]?.string),
                artwork = listOfNotNull(
                    r["poster_path"]?.string?.let(::imagePath)?.let { ArtworkCandidate(ID, "https://image.tmdb.org/t/p/w500$it", ArtworkRole.POSTER) },
                    r["backdrop_path"]?.string?.let(::imagePath)?.let { ArtworkCandidate(ID, "https://image.tmdb.org/t/p/w780$it", ArtworkRole.BANNER) }),
                attribution = MediaAttribution("TMDB", info.license, "https://www.themoviedb.org/${if (movie) "movie" else "tv"}/$id"),
                providerScore = r["popularity"]?.double?.let { minOf(1.0, it / 100.0) })
        }
        return if (candidates.isEmpty()) MediaProviderOutcome.NotFound else MediaProviderOutcome.Found(candidates)
    }

    private fun imagePath(p: String): String? = p.takeIf {
        // TMDB image IDs may contain URL-safe '-'/'_'. Keep a single bounded
        // root filename: never accept traversal, query strings or external URLs.
        it.matches(Regex("/[A-Za-z0-9_-]{1,120}\\.(?:jpg|png)"))
    }

    companion object {
        const val ID = "tmdb"
        fun isPlausibleCredential(s: String): Boolean {
            val t = s.trim()
            return t.matches(Regex("[0-9a-f]{32}")) || t.matches(Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
        }
    }
}
