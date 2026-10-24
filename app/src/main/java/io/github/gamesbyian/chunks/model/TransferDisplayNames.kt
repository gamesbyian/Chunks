package io.github.gamesbyian.chunks.model

/**
 * Chunks-only naming. These values never enter torrent metainfo, RPC calls or filesystem paths.
 * A suggestion is an untrusted candidate to review, never permission to mutate anything.
 */
data class DisplayNameSuggestion(val name: String, val explanation: String)

object TransferDisplayNames {
    const val MAX_ALIAS_LENGTH = 160

    fun validateAlias(input: String): String {
        val alias = input.trim()
        require(alias.isNotEmpty()) { "Enter a display name or choose Use original." }
        require(alias.length <= MAX_ALIAS_LENGTH) { "Display name is too long (160 characters maximum)." }
        require(alias.none { it.isISOControl() || it in '\u202A'..'\u202E' ||
            it in '\u2066'..'\u2069' }) { "Display name contains hidden control characters." }
        return alias
    }

    /**
     * Conservatively extracts only text already in the supplied name. In particular, guest,
     * actor and episode titles cannot be inferred if the filename doesn't contain them.
     * No network access, guessing, metadata service, or automatic write.
     */
    fun suggestions(originalName: String): List<DisplayNameSuggestion> {
        val leaf = originalName.substringAfterLast('/').substringAfterLast('\\').trim()
        if (leaf.isBlank()) return emptyList()
        // Unknown dotted suffixes may be real extensions, not release tokens.
        // Abstain instead of recommending a potentially misleading cleanup.
        val knownExtension = Regex("(?i)\\.(?:mkv|mp4|m4v|avi|webm|mov|mp3|flac|m4a|cbz|cbr|epub|pdf|torrent)$")
        val finalToken = leaf.substringAfterLast('.', "")
        val knownReleaseToken = Regex("(?i)(?:2160p|1080p|720p|480p|hevc|x26[45]|h26[45]|hdr10?|s\\d{1,2}e\\d{1,3})")
        if (!knownExtension.containsMatchIn(leaf) &&
            finalToken.matches(Regex("[A-Za-z][A-Za-z0-9]{1,7}")) &&
            !knownReleaseToken.matches(finalToken)) return emptyList()
        val stem = leaf.replace(knownExtension, "")
        // Cut at the first recognizable *technical* release token, not at an arbitrary word.
        val technical = Regex(
            "(?i)(?:^|[ ._\\-])(?:2160p|1080p|720p|480p|x264|x265|h264|h265|hevc|" +
                "bluray|brrip|dvdrip|webrip|web[-.]?dl|hdr10?|aac|dts)(?=$|[ ._\\-])"
        )
        val technicalStart = technical.find(stem)?.range?.first ?: stem.length
        val meaningful = stem.substring(0, technicalStart)
            .replace(Regex("(?i)-(?:MeGusta|RARBG|EZTV|YTS)$"), "")
            .trim(' ', '.', '_', '-')
        fun readable(value: String): String = value.replace(Regex("[._]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val clean = readable(meaningful)
        if (clean.isBlank()) return emptyList()

        val candidates = linkedMapOf<String, String>()
        fun add(name: String, explanation: String) {
            val candidate = name.trim()
            if (candidate.isNotBlank() && candidate.length <= MAX_ALIAS_LENGTH &&
                candidate != leaf && candidate != originalName) {
                candidates.putIfAbsent(candidate, explanation)
            }
        }

        val episode = Regex("(?i)(?:^|[ ._\\-])S(\\d{1,2})E(\\d{1,3})(?=$|[ ._\\-])")
            .find(meaningful)
        if (episode != null) {
            val title = readable(meaningful.substring(0, episode.range.first))
            val tail = readable(meaningful.substring(episode.range.last + 1))
            val season = episode.groupValues[1].toInt()
            val number = episode.groupValues[2].toInt()
            val code = "S" + season.toString().padStart(2, '0') +
                "E" + number.toString().padStart(2, '0')
            if (title.isNotEmpty()) {
                add(title + " " + code, "Extracted show title, season and episode from filename")
                if (tail.isNotEmpty()) {
                    add(title + ": " + tail + " (" + code + ")",
                        "Also retained words present after the episode number")
                }
                add(title + " · Season " + season + ", Episode " + number,
                    "Expanded season and episode for readability")
            }
        } else {
            val film = Regex("^(.*?)[ ._\\-]+((?:19|20)\\d{2})$").matchEntire(meaningful)
            if (film != null && film.groupValues[1].isNotBlank()) {
                add(readable(film.groupValues[1]) + " (" + film.groupValues[2] + ")",
                    "Formatted a year already present in the filename")
            }
            add(clean, "Removed filename separators and recognizable encoding tags")
        }
        return candidates.map { (name, why) -> DisplayNameSuggestion(name, why) }.take(3)
    }
}
