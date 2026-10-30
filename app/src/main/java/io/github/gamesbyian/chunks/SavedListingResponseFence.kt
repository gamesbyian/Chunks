package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId

/**
 * UI-only fence for optional read-only inventories of saved (not signed-in) servers.
 *
 * Every response belongs to a specific stable profile AND authenticated endpoint/account
 * scope. A newer load, removed profile, changed identity, or privacy lock revokes the
 * old ticket. The fence does not authorize commands or grant access to remote files.
 */
internal class SavedListingResponseFence {
    data class Source(val environment: EnvironmentId, val aliasScope: String) {
        init { require(aliasScope.isNotBlank()) }
    }

    data class Ticket internal constructor(val source: Source, val generation: Long)

    private var generation = 0L
    private val latest = mutableMapOf<EnvironmentId, Ticket>()

    /** Called when the user explicitly requests a fresh read of this saved profile. */
    fun begin(source: Source): Ticket = Ticket(source, ++generation).also {
        latest[source.environment] = it
    }

    /**
     * The active map includes only currently configured, privacy-unlocked profiles
     * whose read-only loader is still available. A matching hash or display label
     * is never adequate identity evidence.
     */
    fun accepts(ticket: Ticket, active: Map<EnvironmentId, String>): Boolean =
        latest[ticket.source.environment] == ticket &&
            active[ticket.source.environment] == ticket.source.aliasScope

    /** Permanently retire pending reads removed or locked during this UI lifetime. */
    fun retireOutside(active: Map<EnvironmentId, String>) {
        latest.entries.removeAll { (id, ticket) ->
            active[id] != ticket.source.aliasScope
        }
    }
}
