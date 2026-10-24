package io.github.gamesbyian.chunks.organize

/**
 * Local-only, non-mutating classifier. Filename hints are weak evidence;
 * category confidence is intentionally NOT authorization to move files.
 */
data class Suggestion(
    val category: String,
    val collection: String?,
    val confidence: Int,
    val reasons: List<String>
)

object Classifier {
    private val episode = Regex("""(?i)\bS(\d{1,2})E(\d{1,3})\b""")
    private val normalizedSeparators = Regex("[._-]+")
    private val starTrek = Regex("""(?i)\bstar\s+trek\b""")
    private val spiderMan = Regex("""(?i)\bspider\s*man\b""")

    fun suggest(filename: String): Suggestion {
        val base = filename.substringAfterLast('/').substringAfterLast('\\')
        val lower = base.lowercase()
        val readable = base.replace(normalizedSeparators, " ")
        val collection = when {
            starTrek.containsMatchIn(readable) -> "Star Trek"
            spiderMan.containsMatchIn(readable) -> "Spider-Man"
            else -> null
        }
        val ext = lower.substringAfterLast('.', "")
        val hasEpisode = episode.containsMatchIn(readable)
        val category = when {
            hasEpisode -> "TV"
            ext in setOf("cbz", "cbr", "cb7") -> "Comics"
            ext in setOf("epub", "mobi", "azw3", "pdf") -> "Books"
            ext in setOf("flac", "mp3", "ogg", "m4a", "opus") -> "Music"
            ext in setOf("mkv", "mp4", "avi", "mov", "webm") -> "Video"
            else -> "Unsorted"
        }
        val reasons = buildList {
            if (hasEpisode) add("Recognized season/episode pattern")
            if (ext.isNotEmpty()) add("File extension: $ext")
            if (collection != null) add("Recognized collection name")
        }
        return Suggestion(category, collection, when {
            hasEpisode -> 92
            category == "Unsorted" -> 0
            else -> 70
        }, reasons)
    }
}
