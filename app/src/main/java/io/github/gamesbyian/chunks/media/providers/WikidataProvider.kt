package io.github.gamesbyian.chunks.media.providers

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
 * Keyless film/TV identity from Wikidata (`wbsearchentities`, CC0 data). Metadata only: Wikidata
 * images are rarely posters, so artwork is left to other providers. Kind, year and director come
 * from the standardized English description ("1995 film by Michael Mann"); entries whose
 * description is not a work of that kind (film series, festivals, characters...) are ignored.
 * Wikimedia: identified clients ~200 req/min, ≤3 concurrent; we pace 1 s and honour Retry-After.
 */
class WikidataProvider : MediaMetadataProvider {
    override val info = MediaProviderInfo(
        id = ID, displayName = "Wikidata",
        kinds = setOf(MediaKind.MOVIE, MediaKind.TV_SERIES, MediaKind.TV_SEASON, MediaKind.TV_EPISODE),
        license = "CC0", attribution = "Film and TV identity from Wikidata (CC0)",
        termsUrl = "https://www.mediawiki.org/wiki/Wikimedia_APIs/Rate_limits", minIntervalMs = 1_000,
        apiHosts = setOf("www.wikidata.org"), artworkHosts = emptySet(), providesArtwork = false)

    override fun search(query: MediaLookupQuery, http: MediaHttp): MediaProviderOutcome {
        val (json, failure) = ProviderHttp.fetchJson(http, info,
            "https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=en&uselang=en&type=item&limit=12&search=" +
                ProviderHttp.enc(query.title))
        if (failure != null) return failure
        val items = json?.get("search") as? Json.Arr ?: return MediaProviderOutcome.BadSchema("Missing search")
        val wantTv = query.kind.isTv
        val candidates = items.items.mapNotNull { item ->
            val id = item["id"]?.string?.takeIf { it.matches(Regex("Q\\d{1,12}")) } ?: return@mapNotNull null
            val label = item["label"]?.string?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val desc = item["description"]?.string ?: return@mapNotNull null
            val parsed = describe(desc) ?: return@mapNotNull null
            if (parsed.tv != wantTv) return@mapNotNull null
            MediaCandidate(
                ref = MediaMetadataCacheKey(ID, "item", id), kind = if (parsed.tv) MediaKind.TV_SERIES else MediaKind.MOVIE,
                title = label, year = parsed.year, creators = listOfNotNull(parsed.director), overview = desc,
                attribution = MediaAttribution("Wikidata", "CC0", "https://www.wikidata.org/wiki/$id"),
                aliases = item["aliases"]?.array?.mapNotNull { it.string }?.take(8) ?: emptyList())
        }
        return if (candidates.isEmpty()) MediaProviderOutcome.NotFound else MediaProviderOutcome.Found(candidates)
    }

    internal data class Description(val tv: Boolean, val year: Int?, val director: String?)

    companion object {
        const val ID = "wikidata"
        private val notAWork = Regex("(?i)\\b(film|television|tv)\\s+(series of films|franchise|festival|character|award|studio|company|" +
            "director|actor|actress|producer|soundtrack|score|genre|episode list)\\b|\\bfilm series\\b|\\bcharacter\\b|\\bwikimedia\\b|\\bnovel\\b|\\bsong\\b|\\balbum\\b")
        private val film = Regex("(?i)\\b(?:film|movie|motion picture)\\b")
        private val tv = Regex("(?i)\\b(?:television|tv) (?:series|show|programme|program|miniseries|sitcom|drama)\\b")
        private val year = Regex("\\b(1[89]\\d{2}|20\\d{2})\\b")
        private val by = Regex("(?i)\\b(?:directed by|by)\\s+([^,;()]{3,60})$")

        internal fun describe(desc: String): Description? {
            if (notAWork.containsMatchIn(desc)) return null
            val isTv = tv.containsMatchIn(desc)
            if (!isTv && !film.containsMatchIn(desc)) return null
            return Description(isTv, year.find(desc)?.value?.toIntOrNull(), if (isTv) null else by.find(desc.trim())?.groupValues?.get(1)?.trim())
        }
    }
}
