package io.github.gamesbyian.chunks.media

import io.github.gamesbyian.chunks.model.MediaContentMember
import io.github.gamesbyian.chunks.model.MediaTransferMembers
import io.github.gamesbyian.chunks.model.UnifiedTransferKey

/**
 * Feeds confirmed media identities into the shared, offline [io.github.gamesbyian.chunks.model.MediaRelationIndex]
 * (Similar Transfers) instead of building a parallel resolver. Only AUTOMATIC or CHOSEN_BY_USER
 * matches count; suggestions, category-only choices and unidentified transfers contribute nothing.
 * Facets are provider-qualified (`tvmaze:show:475`), so equal titles never join on their own.
 */
object MediaRelationBridge {
    fun members(key: UnifiedTransferKey, assignment: TransferMediaAssignment?): MediaTransferMembers? {
        val m = assignment?.displayMatch ?: return null
        val id = "${m.ref.provider}:${m.ref.entityType}:${m.ref.externalId}"
        if (!id.matches(Regex("[a-z][a-z0-9-]*:[a-z][a-z0-9-]*:[A-Za-z0-9][A-Za-z0-9_.-]*"))) return null
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
        entries.distinctBy { it.first }.mapNotNull { (k, a) -> members(k, a) }
}
