package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus

/** Local queue is observed data: searching/sorting must never call libtorrent. */
internal enum class LocalQueueFilter(val label: String) {
    ALL("All"), RUNNING("Running"), STOPPED("Stopped"),
    COMPLETE("Complete"), NEEDS_ATTENTION("Attention")
}

internal enum class LocalQueueOrder(val label: String, val kind: TransferSortOrdering.Kind) {
    NAME("Name", TransferSortOrdering.Kind.NAME),
    PROGRESS("Progress", TransferSortOrdering.Kind.PROGRESS),
    SIZE("Size", TransferSortOrdering.Kind.SIZE),
    PEERS("Peers", TransferSortOrdering.Kind.PEERS);

    val naturalDirection: UnifiedSortDirection get() = TransferSortOrdering.naturalDirection(kind)
    fun directionLabel(direction: UnifiedSortDirection): String = TransferSortOrdering.label(kind, direction)
}

internal object LocalQueueCatalog {
    fun visible(
        items: List<LocalTransferStatus>,
        search: String,
        filter: LocalQueueFilter,
        order: LocalQueueOrder,
        direction: UnifiedSortDirection = order.naturalDirection,
        displayName: (LocalTransferStatus) -> String = { it.name }
    ): List<LocalTransferStatus> {
        val needle = search.trim()
        val filtered = items.asSequence().filter { item ->
            when (filter) {
                LocalQueueFilter.ALL -> true
                LocalQueueFilter.RUNNING -> !item.stopped && item.state in setOf(
                    LocalTransferState.DOWNLOADING,
                    LocalTransferState.DOWNLOADING_METADATA,
                    LocalTransferState.CHECKING,
                    LocalTransferState.FINISHED,
                    LocalTransferState.SEEDING)
                LocalQueueFilter.STOPPED -> item.stopped
                LocalQueueFilter.COMPLETE -> item.metadataKnown &&
                    item.wantedBytes > 0 && item.progressPpm >= 1_000_000
                LocalQueueFilter.NEEDS_ATTENTION -> !item.error.isNullOrBlank() ||
                    (!item.metadataKnown && item.stopped)
            }
        }.map { item -> item to displayName(item) }.filter { (item, label) ->
            needle.isBlank() || label.contains(needle, ignoreCase = true) ||
                item.name.contains(needle, ignoreCase = true) ||
                item.infoHash.contains(needle, ignoreCase = true) ||
                item.savePath.contains(needle, ignoreCase = true)
        }.toList()

        // Cached display labels are resolved once per candidate, never in a
        // comparator's O(N log N) calls or via a native engine read. A
        // metadata-less magnet has UNKNOWN size/progress, not zero: it stays
        // last in both directions instead of jumping to the top when reversed.
        fun known(item: LocalTransferStatus): Long? = when (order) {
            LocalQueueOrder.NAME -> null
            LocalQueueOrder.PROGRESS -> item.progressPpm.toLong().takeIf { item.metadataKnown }
            LocalQueueOrder.SIZE -> item.wantedBytes.takeIf { item.metadataKnown && it >= 0 }
            LocalQueueOrder.PEERS -> item.peers.toLong().takeIf { it >= 0 }
        }
        val ordered = filtered.sortedWith(Comparator { x, y ->
            val primary = if (order == LocalQueueOrder.NAME)
                TransferSortOrdering.compareNames(x.second, y.second, direction)
            else TransferSortOrdering.compareKnown(known(x.first), known(y.first), direction)
            if (primary != 0) primary
            // Fixed A–Z then infohash tie-break: equal values never reshuffle
            // between refreshes or flip order with the direction toggle.
            else String.CASE_INSENSITIVE_ORDER.compare(x.second, y.second).takeIf { it != 0 }
                ?: x.first.infoHash.compareTo(y.first.infoHash)
        })
        return ordered.map { it.first }
    }
}
