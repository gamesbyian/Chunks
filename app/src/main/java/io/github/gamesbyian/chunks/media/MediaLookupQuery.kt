package io.github.gamesbyian.chunks.media

import java.text.Normalizer
import java.util.Locale

/**
 * The only data that may leave the device for a metadata lookup: a sanitized title plus typed
 * year/season/episode/creator hints. Never an infohash, magnet, tracker, path, host, raw name,
 * file inventory or credential. Construct via [MediaQuerySanitizer].
 */
class MediaLookupQuery private constructor(
    val kind: MediaKind,
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val creator: String?,
    val issue: Int?,
    /** True when typed by the user in manual search rather than derived from a transfer name. */
    val manual: Boolean
) {
    /** Stable cache identity of the outbound query (not of the transfer). */
    val canonical: String get() = listOf(kind.name, MediaText.normalize(title), year ?: "", season ?: "",
        episode ?: "", creator?.let { MediaText.normalize(it) } ?: "", issue ?: "").joinToString("|")

    override fun equals(other: Any?) = other is MediaLookupQuery && other.canonical == canonical && other.manual == manual
    override fun hashCode() = canonical.hashCode()
    override fun toString() = "MediaLookupQuery($canonical)"

    fun withKind(kind: MediaKind) = MediaLookupQuery(kind, title, year, season, episode, creator, issue, manual)

    internal companion object {
        fun create(kind: MediaKind, title: String, year: Int?, season: Int?, episode: Int?, creator: String?,
                   issue: Int?, manual: Boolean) = MediaLookupQuery(kind, title, year, season, episode, creator, issue, manual)
    }
}

sealed class MediaQueryDecision {
    data class Allowed(val query: MediaLookupQuery) : MediaQueryDecision()
    /** Not sent anywhere. [reason] is safe to show; it never echoes the rejected text. */
    data class Refused(val reason: String) : MediaQueryDecision()
}

object MediaQuerySanitizer {
    const val MAX_TITLE_CHARS = 100
    const val MAX_WORDS = 14

    private val lookupKinds = setOf(MediaKind.MOVIE, MediaKind.TV_SERIES, MediaKind.TV_SEASON, MediaKind.TV_EPISODE,
        MediaKind.VIDEO, MediaKind.MUSIC_ALBUM, MediaKind.AUDIOBOOK, MediaKind.BOOK, MediaKind.COMIC, MediaKind.GAME)

    private val sensitive = listOf(
        Regex("(?i)[a-z][a-z0-9+.-]{1,15}:/"),                      // any URL scheme
        Regex("(?i)magnet:|xt=urn|urn:btih"),
        Regex("(?i)\\b(?:passkey|announce|torrent_pass|authkey|apikey|api_key|access_token|auth_token)\\b"),
        Regex("(?i)\\b[0-9a-f]{32,}\\b"),                          // infohashes, md5/sha digests, keys
        Regex("\\b[A-Z2-7]{32}\\b"),                                // base32 infohash
        Regex("[A-Za-z0-9_-]{24,}"),                                // long opaque tokens
        Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b"),                  // IPv4
        Regex("(?i)\\b[a-z0-9-]+\\.(?:com|org|net|io|to|me|cc|tv|xyz|info|ru|se|club|site|onion|local|lan)\\b"),
        Regex("[/\\\\]"),                                            // paths
        Regex("@"),                                                  // emails, handles
        Regex("%[0-9A-Fa-f]{2}"),                                   // URL-encoded content
        Regex("\\d{7,}")                                             // phone/account/ID-like numbers
    )

    fun fromRecognition(r: MediaRecognition): MediaQueryDecision {
        if (r.isSensitive) return MediaQueryDecision.Refused("Adult content is never looked up online by default")
        if (r.kind !in lookupKinds) return MediaQueryDecision.Refused("${r.kind.label} transfers are not looked up online")
        if (r.confidence == MediaConfidence.NONE || r.title == null) return MediaQueryDecision.Refused("Not enough information to identify")
        val kind = if (r.kind == MediaKind.VIDEO) MediaKind.MOVIE else r.kind
        return build(kind, r.title, r.year, r.season, r.episode ?: r.absoluteEpisode?.takeIf { r.season != null },
            r.artist, r.issue, manual = false)
    }

    fun manual(kind: MediaKind, text: String, year: Int? = null, season: Int? = null, episode: Int? = null,
               creator: String? = null): MediaQueryDecision {
        if (kind == MediaKind.ADULT) return MediaQueryDecision.Refused("Adult content is never looked up online by default")
        val effective = if (kind in lookupKinds) kind else MediaKind.MOVIE
        return build(effective, text, year, season, episode, creator, null, manual = true)
    }

    private fun build(kind: MediaKind, rawTitle: String, year: Int?, season: Int?, episode: Int?, creator: String?,
                      issue: Int?, manual: Boolean): MediaQueryDecision {
        val title = tidy(rawTitle) ?: return MediaQueryDecision.Refused("Search text is empty")
        val cleanCreator = creator?.let { tidy(it) }
        for (part in listOfNotNull(title, cleanCreator)) {
            if (part.length > MAX_TITLE_CHARS || part.split(' ').size > MAX_WORDS) return MediaQueryDecision.Refused("Search text is too long")
            if (sensitive.any { it.containsMatchIn(part) }) return MediaQueryDecision.Refused("Search text looks like a link, path, address or secret")
        }
        if (year != null && year !in 1870..2100) return MediaQueryDecision.Refused("Year out of range")
        return MediaQueryDecision.Allowed(MediaLookupQuery.create(kind, title, year, season?.takeIf { it in 0..99 },
            episode?.takeIf { it in 0..9999 }, cleanCreator, issue, manual))
    }

    private fun tidy(s: String): String? = Normalizer.normalize(s, Normalizer.Form.NFC)
        .filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }
        .replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
}

/** Comparison-only text normalization. Display values always keep the original script and accents. */
object MediaText {
    private val articles = setOf("the", "a", "an", "le", "la", "les", "der", "die", "das", "el", "il", "los")

    fun normalize(s: String): String {
        val folded = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT).replace("&", " and ").replace("'", "").replace("’", "")
        return folded.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
    }

    fun withoutArticle(normalized: String): String {
        val first = normalized.substringBefore(' ')
        return if (first in articles && normalized.contains(' ')) normalized.substringAfter(' ') else normalized
    }

    /** 0..1 similarity on comparison forms; exact (article-insensitive) equality scores 1. */
    fun similarity(a: String, b: String): Double {
        val x = withoutArticle(normalize(a)); val y = withoutArticle(normalize(b))
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val lev = 1.0 - levenshtein(x, y).toDouble() / maxOf(x.length, y.length)
        val tx = x.split(' ').toSet(); val ty = y.split(' ').toSet()
        val jac = tx.intersect(ty).size.toDouble() / tx.union(ty).size
        // Numbers are disambiguating (sequels, years in titles): mismatched numerals cap similarity.
        val nx = Regex("\\d+").findAll(x).map { it.value.trimStart('0') }.toSet()
        val ny = Regex("\\d+").findAll(y).map { it.value.trimStart('0') }.toSet()
        val score = maxOf(lev, jac)
        return if (nx != ny) minOf(score, 0.6) else score
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.length > 200 || b.length > 200) return maxOf(a.length, b.length)
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
