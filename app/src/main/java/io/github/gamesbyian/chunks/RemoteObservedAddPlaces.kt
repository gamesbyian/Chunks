package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.rpc.StoppedRemoteAddPlan

/**
 * Read-only hints from the CURRENT authenticated server's fresh transfer list.
 * rTorrent exposes d.directory, not a general filesystem browsing API.
 * Hints are never treated as proof of a writable directory.
 *
 * A multi-file torrent commonly reports <parent>/<torrent name> as its
 * effective directory. Offer the parent rather than accidentally nesting
 * future torrents under that torrent's existing data.
 */
internal object RemoteObservedAddPlaces {
    private const val LIMIT = 8
    private const val VALIDATION_MAGNET =
        "magnet:?xt=urn:btih:0000000000000000000000000000000000000000"

    fun from(transfers: List<RemoteTransfer>): List<String> {
        val counts = linkedMapOf<String, Int>()
        for (transfer in transfers) {
            val directory = transfer.directory.trimEnd('/')
            val suffix = "/" + transfer.name
            val parent = if (transfer.name.isNotEmpty() &&
                directory.endsWith(suffix) && directory.length > suffix.length)
                directory.dropLast(suffix.length)
            else directory
            // Only offer paths that the existing ordinary Add validator accepts.
            if (runCatching {
                    StoppedRemoteAddPlan.fromMagnet(VALIDATION_MAGNET, parent)
                }.isFailure) continue
            counts[parent] = (counts[parent] ?: 0) + 1
        }
        return counts.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { it.key }
        ).take(LIMIT).map { it.key }
    }
}
