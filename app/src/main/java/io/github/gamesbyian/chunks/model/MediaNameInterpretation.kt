package io.github.gamesbyian.chunks.model

import java.util.Locale

/** Local filename hints only. No catalog identity, rename proposal, network query or torrent command. */
enum class MediaNameCategory { UNKNOWN, VIDEO, TV_EPISODE, AUDIO, BOOK, COMIC, ARCHIVE, SOFTWARE }

enum class MediaNameSpanKind { EXTENSION, EPISODE, YEAR, YEAR_RANGE, COMIC_ISSUE, RELEASE_TECHNICAL }

/** Offsets are UTF-16 indices into [MediaNameInterpretation.raw], never normalized-string offsets. */
data class MediaNameSpan(val kind: MediaNameSpanKind, val start: Int, val endExclusive: Int, val literal: String) {
    init { require(start >= 0 && endExclusive > start && literal.isNotEmpty()) }
}

data class MediaEpisodeHint(val season: Int, val firstEpisode: Int, val lastEpisode: Int?) {
    init {
        require(season in 0..99 && firstEpisode in 0..999)
        require(lastEpisode == null || lastEpisode in firstEpisode..999)
    }
    /** A range is not evidence that every intermediate episode is actually present in the torrent. */
    val isRange: Boolean get() = lastEpisode != null && lastEpisode != firstEpisode
}

data class MediaYearHint(val year: Int) { init { require(year in 1900..2099) } }

data class MediaNameInterpretation(
    val raw: String,
    val parserVersion: Int,
    val category: MediaNameCategory,
    val titleCandidate: String?,
    val episode: MediaEpisodeHint?,
    val year: MediaYearHint?,
    val issueNumber: Int?,
    val spans: List<MediaNameSpan>,
    val ambiguities: Set<String>
) {
    init {
        require(parserVersion > 0)
        require(spans.all { it.endExclusive <= raw.length && raw.substring(it.start, it.endExclusive) == it.literal })
        require(spans.zipWithNext().all { (a, b) -> a.endExclusive <= b.start })
    }
}

/**
 * Versioned, conservative standalone interpreter. The caller still needs embedded metadata,
 * type-specific provider IDs and explicit member inventory to resolve actual identities.
 * No guessing title equality, people or media membership from text.
 */
object MediaNameInterpreter {
    const val VERSION = 1

    private val extension = Regex("(?i)\\.(?:tar\\.gz|mkv|mp4|m4v|avi|webm|mov|flac|mp3|m4a|ogg|opus|" +
        "epub|mobi|azw3|pdf|cbz|cbr|cb7|zip|7z|rar|tar|gz|apk|aab)$")
    private val episode = Regex("(?i)(?<![\\p{L}\\p{N}])S(\\d{1,2})E(\\d{1,3})(?:[-._ ]?E?(\\d{1,3}))?(?![\\p{L}\\p{N}])")
    private val alternateEpisode = Regex("(?i)(?<![\\p{L}\\p{N}])(\\d{1,2})x(\\d{1,3})(?![\\p{L}\\p{N}])")
    private val yearRange = Regex("(?<![\\p{L}\\p{N}])((?:19|20)\\d{2})[–—-]((?:19|20)\\d{2})(?![\\p{L}\\p{N}])")
    private val year = Regex("(?<![\\p{L}\\p{N}])((?:19|20)\\d{2})(?![\\p{L}\\p{N}])")
    private val issue = Regex("(?i)(?<![\\p{L}\\p{N}])(?:issue[ ._#-]*|#)(\\d{1,4})(?![\\p{L}\\p{N}])")
    private val technical = Regex("(?i)(?<![\\p{L}\\p{N}])(?:2160p|1080p|720p|480p|x264|x265|h264|h265|hevc|" +
        "bluray|brrip|dvdrip|webrip|web[.-]?dl|hdr10?|aac|dts|24bit|16bit)(?![\\p{L}\\p{N}])")

    fun interpret(raw: String): MediaNameInterpretation {
        val leafStart = maxOf(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1
        val leaf = raw.substring(leafStart)
        val ext = extension.find(leaf)?.takeIf { it.range.last == leaf.lastIndex }
        val category = when (ext?.value?.lowercase(Locale.ROOT)) {
            ".mkv", ".mp4", ".m4v", ".avi", ".webm", ".mov" -> MediaNameCategory.VIDEO
            ".flac", ".mp3", ".m4a", ".ogg", ".opus" -> MediaNameCategory.AUDIO
            ".epub", ".mobi", ".azw3", ".pdf" -> MediaNameCategory.BOOK
            ".cbz", ".cbr", ".cb7" -> MediaNameCategory.COMIC
            ".zip", ".7z", ".rar", ".tar", ".gz", ".tar.gz" -> MediaNameCategory.ARCHIVE
            ".apk", ".aab" -> MediaNameCategory.SOFTWARE
            else -> MediaNameCategory.UNKNOWN
        }
        val stem = leaf.substring(0, ext?.range?.first ?: leaf.length)
        val tokens = mutableListOf<MediaNameSpan>()
        val ambiguities = mutableSetOf<String>()
        fun add(kind: MediaNameSpanKind, start: Int, end: Int) {
            tokens.add(MediaNameSpan(kind, leafStart + start, leafStart + end, leaf.substring(start, end)))
        }

        // Year ranges are labels for candidate collection coverage, not guaranteed years or members.
        val range = yearRange.find(stem)
        if (range != null) add(MediaNameSpanKind.YEAR_RANGE, range.range.first, range.range.last + 1)
        val episodeMatches = (episode.findAll(stem) + alternateEpisode.findAll(stem)).toList().sortedBy { it.range.first }
        if (episodeMatches.size > 1) ambiguities.add("multiple-episode-markers")
        val ep = episodeMatches.singleOrNull()
        val epHint = ep?.let { match ->
            val isAlternate = match.value.contains('x', ignoreCase = true)
            val season = match.groupValues[1].toIntOrNull()
            val number = match.groupValues[2].toIntOrNull()
            val last = if (isAlternate) null else match.groupValues[3].takeIf { it.isNotBlank() }?.toIntOrNull()
            if (season == null || number == null || (last != null && last < number)) {
                ambiguities.add("invalid-episode-range")
                null
            } else {
                if (last != null && last - number > 24) ambiguities.add("episode-range-requires-file-inventory")
                add(MediaNameSpanKind.EPISODE, match.range.first, match.range.last + 1)
                MediaEpisodeHint(season, number, last)
            }
        }
        // A YEAR_RANGE suppresses independent release-year claims from both its endpoints.
        val releaseYears = year.findAll(stem).filter { found ->
            range == null || found.range.first < range.range.first || found.range.last > range.range.last
        }.toList()
        val yearMatch = if (releaseYears.size == 1) releaseYears.single() else null
        if (releaseYears.size > 1) ambiguities.add("multiple-release-years")
        val yearHint = yearMatch?.groupValues?.get(1)?.toIntOrNull()?.let { MediaYearHint(it) }
        if (yearMatch != null) add(MediaNameSpanKind.YEAR, yearMatch.range.first, yearMatch.range.last + 1)

        val issues = if (category == MediaNameCategory.COMIC) issue.findAll(stem).toList() else emptyList()
        if (issues.size > 1) ambiguities.add("multiple-issue-markers")
        val issueMatch = issues.singleOrNull()
        val issueHint = issueMatch?.groupValues?.get(1)?.toIntOrNull()
        if (issueMatch != null) add(MediaNameSpanKind.COMIC_ISSUE, issueMatch.range.first, issueMatch.range.last + 1)
        technical.findAll(stem).forEach { add(MediaNameSpanKind.RELEASE_TECHNICAL, it.range.first, it.range.last + 1) }
        if (ext != null) add(MediaNameSpanKind.EXTENSION, ext.range.first, ext.range.last + 1)

        // Stable, non-overlapping spans: skip incidental technical tokens embedded in a stronger marker.
        val priority = mapOf(MediaNameSpanKind.YEAR_RANGE to 0, MediaNameSpanKind.EPISODE to 1,
            MediaNameSpanKind.COMIC_ISSUE to 2, MediaNameSpanKind.YEAR to 3,
            MediaNameSpanKind.RELEASE_TECHNICAL to 4, MediaNameSpanKind.EXTENSION to 5)
        val nonoverlapping = mutableListOf<MediaNameSpan>()
        for (span in tokens.sortedWith(compareBy<MediaNameSpan> { priority.getValue(it.kind) }.thenBy { it.start })) {
            if (nonoverlapping.none { it.start < span.endExclusive && span.start < it.endExclusive }) nonoverlapping.add(span)
        }
        nonoverlapping.sortBy { it.start }
        val stopAt = nonoverlapping.filter { it.kind in setOf(MediaNameSpanKind.EPISODE,
            MediaNameSpanKind.YEAR, MediaNameSpanKind.YEAR_RANGE, MediaNameSpanKind.COMIC_ISSUE,
            MediaNameSpanKind.RELEASE_TECHNICAL) }.minOfOrNull { it.start - leafStart } ?: stem.length
        val title = stem.substring(0, stopAt).trim(' ', '.', '_', '-', '(', ')', '[', ']')
            .replace(Regex("[._]+"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { null }
        return MediaNameInterpretation(raw, VERSION,
            if (epHint != null && category == MediaNameCategory.VIDEO) MediaNameCategory.TV_EPISODE else category,
            title, epHint, yearHint, issueHint, nonoverlapping, ambiguities)
    }
}
