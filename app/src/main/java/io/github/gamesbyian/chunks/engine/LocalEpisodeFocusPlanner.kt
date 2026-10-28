package io.github.gamesbyian.chunks.engine

import java.util.Locale

/**
 * Conservative opt-in episode ordering. A one-shot, explicitly reviewed native
 * priority batch, NOT a guaranteed sequential-playback or background scheduler.
 * Undefined or ambiguous filenames remain in normal native rarest-first mode.
 */
internal object LocalEpisodeFocusPlanner {
    data class Change(val reviewed: LocalTransferFile, val requestedPriority: Int)
    data class Plan(
        val firstEpisode: LocalTransferFile,
        val orderedEpisodes: List<LocalTransferFile>,
        val changes: List<Change>
    )

    private val seasonEpisode = Regex("""(?i)(?:^|[^a-z0-9])s(\d{1,2})[ ._-]*e(\d{1,3})(?:[^0-9]|$)""")
    private val xEpisode = Regex("""(?i)(?:^|[^a-z0-9])(\d{1,2})x(\d{1,3})(?:[^0-9]|$)""")
    private val episode = Regex("""(?i)(?:^|[^a-z0-9])(?:episode|ep)[ ._-]*(\d{1,3})(?:[^0-9]|$)""")
    private val extras = Regex("""(?i)(?:^|[/ ._-])(sample|trailer|featurette|extras?|behind.the.scenes)(?:[/ ._-]|$)""")

    data class EpisodeKey(val season: Int, val episode: Int)

    fun episodeKey(path: String): EpisodeKey? {
        val name = path.substringAfterLast('/')
        if (extras.containsMatchIn(path)) return null
        if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) in
            setOf("srt", "ass", "ssa", "sub", "nfo", "txt", "jpg", "png")) return null
        // Use ALL matches: S01E01.S02E02 is ambiguous, not episode one.
        val candidates = buildList {
            seasonEpisode.findAll(name).forEach {
                add(EpisodeKey(it.groupValues[1].toInt(),
                    it.groupValues[2].toInt()))
            }
            xEpisode.findAll(name).forEach {
                add(EpisodeKey(it.groupValues[1].toInt(),
                    it.groupValues[2].toInt()))
            }
            episode.findAll(name).forEach {
                add(EpisodeKey(0, it.groupValues[1].toInt()))
            }
        }.distinct()
        return candidates.singleOrNull()?.takeIf {
            it.season in 0..99 && it.episode in 1..999
        }
    }

    /**
     * Require at least two distinguishable episode labels, one future wanted
     * episode and a bounded complete file list. The preview tells the user
     * exactly which existing manual priorities a confirmation will replace.
     */
    fun plan(files: List<LocalTransferFile>): Plan? {
        if (files.size !in 2..256 || files.map { it.index }.toSet().size != files.size)
            return null
        val identified = files.mapNotNull { file ->
            episodeKey(file.path)?.let { key -> file to key }
        }.sortedWith(compareBy<Pair<LocalTransferFile, EpisodeKey>>(
            { it.second.season }, { it.second.episode },
            { it.first.path.lowercase(Locale.ROOT) }, { it.first.index }))
        if (identified.map { it.second }.distinct().size < 2) return null
        val unfinished = identified.map { it.first }.filter {
            it.wanted && it.sizeBytes > 0L && it.doneBytes < it.sizeBytes
        }
        if (unfinished.isEmpty()) return null
        val changes = unfinished.mapIndexedNotNull { index, file ->
            val target = when (index) {
                0 -> 7       // First incomplete episode: highest priority
                1 -> 4       // Lookahead: ordinary background rarest-first
                else -> 1    // Subsequent episodes still permitted to progress
            }
            if (file.priority == target) null else Change(file, target)
        }
        return Plan(unfinished.first(), unfinished, changes)
    }
}
