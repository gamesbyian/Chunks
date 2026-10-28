package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalTransferFile
import java.util.Locale

/**
 * A non-mutating, user-selectable *display* order for episodic multi-file
 * torrents. Never rewrites torrent file indexes, native file priorities,
 * download selection, payload, or paths.
 *
 * This is intentionally not labelled "Finish in Order": rarest-first piece
 * picking and peer availability are unaffected by sorting a UI list.
 */
internal object LocalFileNaturalOrder {
    private val seasonEpisode = Regex(
        "(?i)(?:^|[^a-z0-9])s([0-9]{1,2})[ ._-]*e([0-9]{1,3})(?=[^0-9]|$)")
    private val tvX = Regex(
        "(?i)(?:^|[^a-z0-9])([0-9]{1,2})x([0-9]{1,3})(?=[^0-9]|$)")
    private val seasonFolder = Regex(
        "(?i)(?:^|/)season[ ._-]*([0-9]{1,2})(?:/|$)")
    private val episodeName = Regex(
        "(?i)(?:^|[^a-z0-9])(?:episode|ep)[ ._-]*([0-9]{1,3})(?=[^0-9]|$)")
    private val numericStart = Regex("^([0-9]{1,3})(?:[ ._-]|$)")
    private val subtitleExtensions = setOf("srt", "ass", "ssa", "vtt", "sub", "idx")
    private val extras = Regex("(?i)(?:sample|proof|readme|trailer|extras?|nfo)")

    data class Position(val season: Int, val episode: Int, val companionRank: Int)

    fun position(file: LocalTransferFile): Position? {
        val path = file.path
        val base = path.substringAfterLast('/').substringAfterLast('\\')
        val matched = seasonEpisode.find(base)?.let {
            it.groupValues[1].toInt() to it.groupValues[2].toInt()
        } ?: tvX.find(base)?.let {
            it.groupValues[1].toInt() to it.groupValues[2].toInt()
        } ?: run {
            val folder = seasonFolder.find(path.replace('\\', '/'))
            val season = folder?.groupValues?.get(1)?.toIntOrNull()
            val episode = episodeName.find(base)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (season != null) numericStart.find(base)?.groupValues?.get(1)?.toIntOrNull()
                    else null
            if (season == null || episode == null) null else season to episode
        } ?: return null
        if (matched.first !in 0..99 || matched.second !in 0..999 ||
            extras.containsMatchIn(base)) return null
        val extension = base.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return Position(matched.first, matched.second,
            if (extension in subtitleExtensions) 1 else 0)
    }

    /** Offer only when at least two distinct explicit episode identities exist. */
    fun available(files: List<LocalTransferFile>): Boolean =
        files.asSequence().mapNotNull(::position).map { it.season to it.episode }
            .distinct().take(2).count() == 2

    fun sorted(files: List<LocalTransferFile>): List<LocalTransferFile> =
        files.sortedWith(compareBy<LocalTransferFile>(
            { if (position(it) == null) 1 else 0 },
            { position(it)?.season ?: Int.MAX_VALUE },
            { position(it)?.episode ?: Int.MAX_VALUE },
            { position(it)?.companionRank ?: Int.MAX_VALUE },
            { it.index }
        ))
}
