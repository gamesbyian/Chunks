package io.github.gamesbyian.chunks

/**
 * Monotonic request identity for Compose's asynchronous rTorrent reads.
 *
 * A captured view label travels with its specific server response. Invalidate
 * on disconnect or when an old inspection is superseded so slow HTTP replies
 * cannot repopulate a different session or overwrite a newer inspection.
 *
 * Confined to the UI coroutine dispatcher; no concurrent mutable thread use.
 */
internal class RemoteUiRequestFence {
    data class Ticket(val generation: Long, val view: String)

    private var generation = 0L
    private var anotherRefreshRequested = false

    fun begin(view: String): Ticket {
        anotherRefreshRequested = false
        return Ticket(++generation, view)
    }

    fun current(ticket: Ticket): Boolean = ticket.generation == generation

    fun queueRefresh() {
        anotherRefreshRequested = true
    }

    fun consumeQueuedRefresh(): Boolean {
        val pending = anotherRefreshRequested
        anotherRefreshRequested = false
        return pending
    }

    fun invalidate() {
        ++generation
        anotherRefreshRequested = false
    }
}
