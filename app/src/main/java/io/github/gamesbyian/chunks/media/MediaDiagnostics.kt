package io.github.gamesbyian.chunks.media

import java.util.Locale

/**
 * Local, title-free counts for settings (TRANSFER-ARTWORK "diagnostic counts ... without telemetry
 * or content titles"). Never uploaded; contains no names, IDs or paths.
 */
data class MediaDiagnostics(
    val byState: Map<MediaAssignmentState, Int>,
    val byProvider: Map<String, Int>,
    val userImages: Int,
    val corrections: Int
) {
    fun summary(providerName: (String) -> String = { it }): String {
        if (byState.isEmpty()) return "No transfers identified yet"
        val states = listOf(MediaAssignmentState.AUTOMATIC, MediaAssignmentState.CHOSEN_BY_USER, MediaAssignmentState.SUGGESTED,
            MediaAssignmentState.COULDNT_IDENTIFY, MediaAssignmentState.CATEGORY_ART_BY_USER)
            .mapNotNull { s -> byState[s]?.takeIf { it > 0 }?.let { "${s.label.lowercase(Locale.ROOT)} $it" } }
        val providers = byProvider.entries.sortedByDescending { it.value }.joinToString(", ") { "${providerName(it.key)} ${it.value}" }
        return buildString {
            append(states.joinToString(" · ").replaceFirstChar { it.titlecase(Locale.ROOT) })
            if (providers.isNotEmpty()) append("\nMatches by source: ").append(providers)
            if (userImages > 0) append("\nYour images: ").append(userImages)
            if (corrections > 0) append(" · corrections: ").append(corrections)
        }
    }

    companion object {
        fun of(assignments: Collection<TransferMediaAssignment>) = MediaDiagnostics(
            byState = assignments.groupingBy { it.state }.eachCount(),
            byProvider = assignments.mapNotNull { it.displayMatch?.ref?.provider }.groupingBy { it }.eachCount(),
            userImages = assignments.count { it.userArtwork != null },
            // A user choice made where an automatic answer or suggestions existed counts as a correction.
            corrections = assignments.count { it.state == MediaAssignmentState.CHOSEN_BY_USER &&
                (it.suggestions.isNotEmpty() || it.lastStatus == MediaLookupStatus.MATCHED) })
    }
}

/**
 * Finds a conventional cover image already inside a transfer's own file list (folder.jpg, cover.jpg…).
 * Selection only: reading it is the host's job, from completed local files it is already permitted
 * to read — never a remote payload read. Using it is an explicit user action.
 */
object LocalCoverFinder {
    private val names = listOf("cover", "folder", "front", "poster", "albumartsmall", "albumart", "thumb")
    private val exts = setOf("jpg", "jpeg", "png", "webp")

    fun pick(files: List<MediaFileHint>): MediaFileHint? = files.mapNotNull { f ->
        val leaf = f.path.substringAfterLast('/').substringAfterLast('\\')
        val ext = leaf.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val stem = leaf.substringBeforeLast('.').lowercase(Locale.ROOT).replace(Regex("[^a-z]"), "")
        val rank = names.indexOfFirst { stem == it || stem.startsWith(it) }
        val depth = f.path.count { it == '/' || it == '\\' }
        val size = f.sizeBytes
        if (ext !in exts || rank < 0 || depth > 2 || (size != null && size !in 4_096L..6L * 1024 * 1024)) null
        else Triple(f, rank, depth)
    }.minWithOrNull(compareBy<Triple<MediaFileHint, Int, Int>> { it.third }.thenBy { it.second }.thenBy { it.first.path })?.first
}
