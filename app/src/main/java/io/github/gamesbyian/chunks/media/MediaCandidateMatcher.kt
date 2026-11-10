package io.github.gamesbyian.chunks.media

import kotlin.math.abs

data class ScoredCandidate(
    val candidate: MediaCandidate,
    val score: Double,
    val titleSimilarity: Double,
    val eligible: Boolean,
    val reasons: List<String>
)

sealed class MediaMatchDecision {
    abstract val ranked: List<ScoredCandidate>
    /** One eligible record clearly fits. May be applied automatically (still correctable). */
    data class Confident(val best: ScoredCandidate, override val ranked: List<ScoredCandidate>) : MediaMatchDecision()
    /** Several records fit similarly well; user must choose. Category art stays until then. */
    data class Ambiguous(override val ranked: List<ScoredCandidate>, val reason: String) : MediaMatchDecision()
    data class NoMatch(override val ranked: List<ScoredCandidate>) : MediaMatchDecision()
}

/**
 * Deterministic, explainable scoring. Title equality is never sufficient on its own when several
 * works share it: a disambiguating year, creator or episode title is required for a confident result.
 */
object MediaCandidateMatcher {
    const val MIN_TITLE_SIMILARITY = 0.78
    const val CONFIDENT_TITLE_SIMILARITY = 0.92
    const val CLOSE_MARGIN = 0.06
    const val PREFIX_ONLY_SIMILARITY = 0.9

    fun score(query: MediaLookupQuery, c: MediaCandidate, episodeTitle: String? = null): ScoredCandidate {
        val reasons = mutableListOf<String>()
        var eligible = query.kind.compatibleWith(c.kind)
        if (!eligible) reasons.add("Different kind (${c.kind.label})")
        val names = listOfNotNull(c.title, c.originalTitle) + c.aliases.take(12)
        val full = names.maxOf { MediaText.similarity(query.title, it) }
        // "Frieren" vs "Frieren: Beyond Journey's End": a title-before-subtitle match is capped below the
        // confidence threshold, so it can only ever be Suggested, never applied automatically.
        val prefix = names.mapNotNull { n -> n.substringBefore(':', "").takeIf { it.isNotBlank() } }
            .maxOfOrNull { minOf(MediaText.similarity(query.title, it), PREFIX_ONLY_SIMILARITY) } ?: 0.0
        val sim = maxOf(full, prefix)
        if (sim >= 1.0) reasons.add("Title matches") else if (sim >= MIN_TITLE_SIMILARITY) reasons.add("Title is similar")
        else { reasons.add("Title differs"); eligible = false }
        var score = sim * 0.8

        val qy = query.year; val cy = c.year
        if (qy != null && cy != null) {
            val d = abs(qy - cy)
            when (query.kind) {
                MediaKind.MOVIE -> if (d == 0) {
                    score += 0.15; reasons.add("Same year $cy")
                } else {
                    // A one-year tolerance can attach a different release's identity/poster.
                    // The torrent's explicit movie year is a disambiguator, not a soft hint.
                    eligible = false; reasons.add("Different movie release year ($cy)")
                }
                MediaKind.GAME -> when {
                    d == 0 -> { score += 0.15; reasons.add("Same year $cy") }
                    d == 1 -> { score += 0.05; reasons.add("Year within one ($cy)") }
                    else -> { eligible = false; reasons.add("Different year ($cy)") }
                }
                MediaKind.MUSIC_ALBUM -> if (d == 0) { score += 0.12; reasons.add("Same year $cy") }
                    else { score -= 0.08; reasons.add("Different year ($cy)") }
                MediaKind.TV_SERIES, MediaKind.TV_SEASON, MediaKind.TV_EPISODE ->
                    if (d == 0) { score += 0.12; reasons.add("Premiered $cy") }
                    else if (qy < cy) { eligible = false; reasons.add("Premiered after $qy ($cy)") }
                else -> if (d == 0) score += 0.05 // book editions and reprints rarely share the original year
            }
        } else if (qy != null && cy == null && query.kind == MediaKind.MOVIE) {
            // Unknown provider year cannot establish the exact release requested.
            eligible = false
            reasons.add("Provider movie release year unknown")
        }
        query.creator?.let { want ->
            val best = c.creators.maxOfOrNull { MediaText.similarity(want, it) } ?: 0.0
            if (best >= 0.85) { score += 0.1; reasons.add("Creator matches") }
            else if (query.kind == MediaKind.MUSIC_ALBUM || (c.creators.isNotEmpty() && query.kind == MediaKind.BOOK)) {
                eligible = false; reasons.add("Different creator")
            }
        }
        if (episodeTitle != null && c.episodeTitle != null) {
            val es = MediaText.similarity(episodeTitle, c.episodeTitle)
            if (es >= 0.85) { score += 0.12; reasons.add("Episode title matches") }
            else if (es < 0.5) { score -= 0.12; reasons.add("Episode title differs") }
        }
        if (c.year == null && c.artwork.isEmpty()) { score -= 0.04; reasons.add("Sparse record") }
        score += 0.02 * (c.providerScore ?: 0.0).coerceIn(0.0, 1.0)
        return ScoredCandidate(c, score, sim, eligible, reasons)
    }

    fun match(query: MediaLookupQuery, candidates: List<MediaCandidate>, episodeTitle: String? = null): MediaMatchDecision {
        val ranked = candidates.distinctBy { it.ref }.map { score(query, it, episodeTitle) }
            .sortedWith(compareByDescending<ScoredCandidate> { it.eligible }.thenByDescending { it.score }
                .thenBy { it.candidate.ref.provider }.thenBy { it.candidate.ref.externalId })
        val eligible = ranked.filter { it.eligible }
        val best = eligible.firstOrNull() ?: return MediaMatchDecision.NoMatch(ranked)
        if (best.titleSimilarity < CONFIDENT_TITLE_SIMILARITY) return MediaMatchDecision.Ambiguous(ranked, "Closest title is not an exact match")
        val rival = eligible.drop(1).firstOrNull { it.score > best.score - CLOSE_MARGIN }
        if (rival != null) {
            val sameTitle = MediaText.withoutArticle(MediaText.normalize(rival.candidate.title)) ==
                MediaText.withoutArticle(MediaText.normalize(best.candidate.title))
            return MediaMatchDecision.Ambiguous(ranked, if (sameTitle) "Several works share this title" else "Several close matches")
        }
        return MediaMatchDecision.Confident(best, ranked)
    }
}
