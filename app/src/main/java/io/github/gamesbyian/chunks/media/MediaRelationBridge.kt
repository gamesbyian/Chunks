package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.model.MediaContentMember
import io.github.gamesbyian.chunks.model.MediaTransferMembers
import io.github.gamesbyian.chunks.model.UnifiedTransferKey

/**
 * Feeds confirmed media identities into the shared, offline [io.github.gamesbyian.chunks.model.MediaRelationIndex]
 * (Similar Transfers) instead of building a parallel resolver. Only AUTOMATIC or CHOSEN_BY_USER
 * matches count for media facets; unconfirmed transfers retain their exact torrent key, but no facets.
 * Facets are provider-qualified (`tvmaze:show:475`), so equal titles never join on their own.
 */
object MediaRelationBridge {
    private val qualifiedId = Regex("[a-z][a-z0-9-]*:[a-z][a-z0-9-]*:[A-Za-z0-9][A-Za-z0-9_.-]*")

    fun members(key: UnifiedTransferKey, assignment: TransferMediaAssignment?): MediaTransferMembers {
        val unidentified = MediaTransferMembers(key, emptyList())
        val m = assignment?.takeIf { it.key == MediaTransferKey.of(key) }?.displayMatch ?: return unidentified
        val id = "${m.ref.provider}:${m.ref.entityType}:${m.ref.externalId}"
        if (id.length !in 3..256 || !qualifiedId.matches(id)) return unidentified
        if (m.kind.isTv && (m.season?.let { it < 0 } == true || m.episode?.let { it < 0 } == true)) return unidentified
        val member = when {
            m.kind.isTv && m.season != null && m.episode != null ->
                MediaContentMember("media:episode", seriesId = id, season = m.season, episode = m.episode)
            m.kind.isTv -> MediaContentMember("media:series", seriesId = id)
            else -> MediaContentMember("media:work", workId = id)
        }
        return MediaTransferMembers(key, listOf(member))
    }

    /** Builds index input for the caller's already-authorized, visible inventory. */
    fun members(entries: Collection<Pair<UnifiedTransferKey, TransferMediaAssignment?>>): List<MediaTransferMembers> =
        entries.distinctBy { it.first }.map { (k, a) -> members(k, a) }
}
