package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.model.MediaMetadataCacheKey

/**
 * Presentation-only media identity types. Nothing here can rename, move or mutate a
 * transfer: the raw transfer name and torrent metainfo always remain authoritative.
 */
enum class MediaKind(val label: String) {
    MOVIE("Movie"), TV_SERIES("TV series"), TV_SEASON("TV season"), TV_EPISODE("TV episode"),
    VIDEO("Video"), MUSIC_ALBUM("Album"), AUDIO("Audio"), AUDIOBOOK("Audiobook"), BOOK("Book"),
    COMIC("Comic"), MAGAZINE("Magazine"), GAME("Game"), SOFTWARE("Software"), DISC_IMAGE("Disc image"),
    ARCHIVE("Archive"), DOCUMENT("Document"), IMAGE("Images"), ADULT("Adult"), UNKNOWN("Transfer");

    val isTv: Boolean get() = this == TV_SERIES || this == TV_SEASON || this == TV_EPISODE

    /** Kinds that may be compared as the same family when matching provider records. */
    fun compatibleWith(other: MediaKind): Boolean = this == other || (isTv && other.isTv) ||
        (this == VIDEO && (other == MOVIE || other.isTv)) || (other == VIDEO && (this == MOVIE || isTv)) ||
        (this == AUDIO && (other == MUSIC_ALBUM || other == AUDIOBOOK)) ||
        (other == AUDIO && (this == MUSIC_ALBUM || this == AUDIOBOOK)) ||
        (this == AUDIOBOOK && other == BOOK) || (this == BOOK && other == AUDIOBOOK)
}

/** Ordinal confidence; never presented as a fabricated percentage. */
enum class MediaConfidence { NONE, LOW, MEDIUM, HIGH }

enum class ArtworkRole { SQUARE, POSTER, BANNER, THUMB }

data class ArtworkCandidate(
    val provider: String,
    val url: String,
    val role: ArtworkRole,
    val width: Int? = null,
    val height: Int? = null,
    val language: String? = null
) {
    init {
        // Remote art is HTTPS only; "local:<digest>" names a user-imported derivative in app storage.
        require(url.startsWith("https://") || (provider == LOCAL_PROVIDER && url.matches(Regex("local:[0-9a-f]{40}")))) { "Artwork must be HTTPS" }
        require(width == null || width > 0); require(height == null || height > 0)
    }
}

const val LOCAL_PROVIDER = "user"

data class MediaAttribution(val providerName: String, val license: String, val pageUrl: String?)

/** A typed provider record normalized into Chunks terms. [ref] is the provider-scoped identity. */
data class MediaCandidate(
    val ref: MediaMetadataCacheKey,
    val kind: MediaKind,
    val title: String,
    val year: Int? = null,
    val originalTitle: String? = null,
    val creators: List<String> = emptyList(),
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val artwork: List<ArtworkCandidate> = emptyList(),
    val attribution: MediaAttribution,
    /** Provider's own relevance hint; used only as a tie-breaker. */
    val providerScore: Double? = null,
    /** Other provider-scoped identifiers the record itself asserts (e.g. TVmaze → IMDb/TheTVDB). */
    val crossReferences: List<MediaMetadataCacheKey> = emptyList(),
    /** Alternative titles the record itself lists (romanized, translated, native script). */
    val aliases: List<String> = emptyList()
) {
    init { require(title.isNotBlank()) }
}

/** What the user sees; matches TRANSFER-ARTWORK "Automatic / Suggested / Chosen by you / Couldn't identify". */
enum class MediaAssignmentState(val label: String) {
    NOT_LOOKED_UP("Not identified"),
    AUTOMATIC("Automatic"),
    SUGGESTED("Suggested"),
    CHOSEN_BY_USER("Chosen by you"),
    COULDNT_IDENTIFY("Couldn't identify"),
    CATEGORY_ART_BY_USER("Category art chosen by you")
}
