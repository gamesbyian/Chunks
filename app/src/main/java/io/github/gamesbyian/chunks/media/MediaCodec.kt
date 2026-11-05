package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.model.MediaMetadataCacheKey
import io.github.gamesbyian.chunks.model.MediaMetadataCacheReceipt
import io.github.gamesbyian.chunks.model.MediaMetadataReceiptState

/** JSON encoding for app-private media files. Decoding is defensive: malformed entries are dropped. */
object MediaCodec {
    fun key(k: MediaMetadataCacheKey): Json = Json.obj("p" to Json.Str(k.provider), "t" to Json.Str(k.entityType), "id" to Json.Str(k.externalId))
    fun key(j: Json?): MediaMetadataCacheKey? = runCatching {
        MediaMetadataCacheKey(j!!["p"]!!.string!!, j["t"]!!.string!!, j["id"]!!.string!!)
    }.getOrNull()

    fun candidate(c: MediaCandidate): Json = Json.obj(
        "ref" to key(c.ref), "kind" to Json.Str(c.kind.name), "title" to Json.Str(c.title),
        "year" to Json.num(c.year), "orig" to Json.str(c.originalTitle),
        "creators" to Json.arr(c.creators.map { Json.Str(it) }),
        "season" to Json.num(c.season), "episode" to Json.num(c.episode), "epTitle" to Json.str(c.episodeTitle),
        "overview" to Json.str(c.overview), "genres" to Json.arr(c.genres.map { Json.Str(it) }),
        "art" to Json.arr(c.artwork.map { a -> Json.obj("p" to Json.Str(a.provider), "u" to Json.Str(a.url),
            "r" to Json.Str(a.role.name), "w" to Json.num(a.width), "h" to Json.num(a.height), "l" to Json.str(a.language)) }),
        "attr" to Json.obj("n" to Json.Str(c.attribution.providerName), "l" to Json.Str(c.attribution.license),
            "u" to Json.str(c.attribution.pageUrl)),
        "score" to Json.num(c.providerScore),
        "xref" to Json.arr(c.crossReferences.map { key(it) }),
        "alias" to Json.arr(c.aliases.take(12).map { Json.Str(it) }))

    fun candidate(j: Json?): MediaCandidate? = runCatching {
        j!!
        val attr = j["attr"]!!
        MediaCandidate(
            ref = key(j["ref"])!!, kind = MediaKind.valueOf(j["kind"]!!.string!!), title = j["title"]!!.string!!,
            year = j["year"]?.int, originalTitle = j["orig"]?.string,
            creators = j["creators"]?.array?.mapNotNull { it.string } ?: emptyList(),
            season = j["season"]?.int, episode = j["episode"]?.int, episodeTitle = j["epTitle"]?.string,
            overview = j["overview"]?.string, genres = j["genres"]?.array?.mapNotNull { it.string } ?: emptyList(),
            artwork = j["art"]?.array?.mapNotNull { a -> runCatching {
                ArtworkCandidate(a["p"]!!.string!!, a["u"]!!.string!!, ArtworkRole.valueOf(a["r"]!!.string!!),
                    a["w"]?.int, a["h"]?.int, a["l"]?.string)
            }.getOrNull() } ?: emptyList(),
            attribution = MediaAttribution(attr["n"]!!.string!!, attr["l"]!!.string!!, attr["u"]?.string),
            providerScore = j["score"]?.double,
            crossReferences = j["xref"]?.array?.mapNotNull { key(it) } ?: emptyList(),
            aliases = j["alias"]?.array?.mapNotNull { it.string } ?: emptyList())
    }.getOrNull()

    fun receipt(r: MediaMetadataCacheReceipt): Json = Json.obj("key" to key(r.key), "rev" to Json.num(r.revision),
        "schema" to Json.num(r.normalizedSchemaVersion), "obs" to Json.num(r.observedAtMs), "exp" to Json.num(r.expiresAtMs),
        "state" to Json.Str(r.state.name), "retry" to Json.num(r.retryNotBeforeMs))

    fun receipt(j: Json?): MediaMetadataCacheReceipt? = runCatching {
        MediaMetadataCacheReceipt(key(j!!["key"])!!, j["rev"]!!.long!!, j["schema"]!!.int!!, j["obs"]!!.long!!,
            j["exp"]!!.long!!, MediaMetadataReceiptState.valueOf(j["state"]!!.string!!), j["retry"]?.long)
    }.getOrNull()
}
