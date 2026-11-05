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
 * MusicBrainz WS/2 release-group search + Cover Art Archive front image. No key; meaningful
 * User-Agent required; ~1 request/second per IP (we use 1.1 s). Core data CC0; CAA images have
 * their own rights and may 404 (handled as missing artwork). The release *group* is the album
 * identity; an artist portrait is never used as album art.
 */
class MusicBrainzProvider : MediaMetadataProvider {
    override val info = MediaProviderInfo(
        id = ID, displayName = "MusicBrainz",
        kinds = setOf(MediaKind.MUSIC_ALBUM),
        license = "CC0 (data); cover images © their owners via Cover Art Archive",
        attribution = "Album data from MusicBrainz; covers from the Cover Art Archive",
        termsUrl = "https://musicbrainz.org/doc/MusicBrainz_API", minIntervalMs = 1_100,
        apiHosts = setOf("musicbrainz.org"),
        artworkHosts = setOf("coverartarchive.org", ".archive.org"))

    override fun search(query: MediaLookupQuery, http: MediaHttp): MediaProviderOutcome {
        val lucene = buildString {
            append("releasegroup:\"").append(luceneEscape(query.title)).append('"')
            query.creator?.let { append(" AND artist:\"").append(luceneEscape(it)).append('"') }
        }
        val (json, failure) = ProviderHttp.fetchJson(http, info,
            "https://musicbrainz.org/ws/2/release-group/?query=${ProviderHttp.enc(lucene)}&fmt=json&limit=8")
        if (failure != null) return failure
        val groups = json?.get("release-groups") as? Json.Arr ?: return MediaProviderOutcome.BadSchema("Missing release-groups")
        val candidates = groups.items.mapNotNull { g ->
            val id = g["id"]?.string?.takeIf { it.matches(UUID) } ?: return@mapNotNull null
            val title = g["title"]?.string?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artists = g["artist-credit"]?.array?.mapNotNull { it["name"]?.string } ?: emptyList()
            val secondary = g["secondary-types"]?.array?.mapNotNull { it.string } ?: emptyList()
            MediaCandidate(
                ref = MediaMetadataCacheKey(ID, "release-group", id), kind = MediaKind.MUSIC_ALBUM, title = title,
                year = ProviderHttp.year(g["first-release-date"]?.string), creators = artists,
                genres = listOfNotNull(g["primary-type"]?.string) + secondary,
                artwork = listOf(ArtworkCandidate(ID, "https://coverartarchive.org/release-group/$id/front-500", ArtworkRole.SQUARE, 500, 500)),
                attribution = MediaAttribution("MusicBrainz", "CC0", "https://musicbrainz.org/release-group/$id"),
                providerScore = g["score"]?.double?.div(100.0))
        }
        return if (candidates.isEmpty()) MediaProviderOutcome.NotFound else MediaProviderOutcome.Found(candidates)
    }

    private fun luceneEscape(s: String) = s.replace(Regex("([+\\-!(){}\\[\\]^\"~*?:\\\\/]|&&|\\|\\|)"), "\\\\$1")

    companion object {
        const val ID = "musicbrainz"
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
