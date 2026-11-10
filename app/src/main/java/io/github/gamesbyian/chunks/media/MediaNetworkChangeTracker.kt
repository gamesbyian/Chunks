package io.github.gamesbyian.chunks.media

/**
 * One lifecycle-scoped change detector, not an independent polling/network worker.
 * A host can feed it its existing inventory/clock observations; only meaningful
 * reachability, mobile-data or power-saver transitions demand a new media pass.
 *
 * The first observation does not schedule a duplicate initial pass: that was
 * already started from the host's exact authorized inventory.
 */
class MediaNetworkChangeTracker {
    private var previous: MediaNetworkState? = null

    @Synchronized fun changedAfterFirstObservation(current: MediaNetworkState): Boolean {
        val before = previous
        previous = current
        return before != null && before != current
    }
}
