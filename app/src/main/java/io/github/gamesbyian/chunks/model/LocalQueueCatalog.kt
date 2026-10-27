package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus

/** Local queue is observed data: searching/sorting must never call libtorrent. */
internal enum class LocalQueueFilter(val label: String) {
    ALL("All"), RUNNING("Running"), STOPPED("Stopped"),
    COMPLETE("Complete"), NEEDS_ATTENTION("Attention")
}

internal enum class LocalQueueOrder(val label: String) {
    NAME("Name"), PROGRESS("Progress"), SIZE("Size"), PEERS("Peers")
}

internal object LocalQueueCatalog {
    fun visible(
        items: List<LocalTransferStatus>,
        search: String,
        filter: LocalQueueFilter,
        order: LocalQueueOrder,
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
        // comparator's O(N log N) calls or via a native engine read.
        val stable: Comparator<Pair<LocalTransferStatus, String>> =
            compareBy(String.CASE_INSENSITIVE_ORDER) {
                pair: Pair<LocalTransferStatus, String> -> pair.second
            }.thenBy { it.first.infoHash }
        val ordered = when (order) {
            LocalQueueOrder.NAME -> filtered.sortedWith(stable)
            LocalQueueOrder.PROGRESS -> filtered.sortedWith(
                compareByDescending<Pair<LocalTransferStatus, String>> {
                    it.first.progressPpm
                }.then(stable))
            LocalQueueOrder.SIZE -> filtered.sortedWith(
                compareByDescending<Pair<LocalTransferStatus, String>> {
                    it.first.wantedBytes
                }.then(stable))
            LocalQueueOrder.PEERS -> filtered.sortedWith(
                compareByDescending<Pair<LocalTransferStatus, String>> {
                    it.first.peers
                }.then(stable))
        }
        return ordered.map { it.first }
    }
}
