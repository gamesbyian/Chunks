package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UnifiedTransferKey

/**
 * Read-only capability probes may finish after a profile reconnects, a
 * different transfer opens, or another probe supersedes the previous one.
 * Bind them to the *authenticated session object*, not its remembered URL
 * or user name: identical credentials never make old evidence current.
 *
 * This guard carries no credentials, performs no RPC, caches no results,
 * and grants no authorization to mutate a transfer.
 */
internal class RemoteCapabilityProbeSessionGate {
    private var session: Any? = null
    private var environment: EnvironmentId? = null
    private var focus: UnifiedTransferKey? = null
    private var revision: Long = 0L

    internal class Ticket internal constructor(
        internal val session: Any,
        internal val environment: EnvironmentId,
        internal val focus: UnifiedTransferKey,
        internal val revision: Long
    )

    /**
     * Call whenever connection or focused transfer changes, including when
     * the user closes the detail view. Null values invalidate all tickets.
     */
    fun bind(activeSession: Any?, environmentId: EnvironmentId?, focusedKey: UnifiedTransferKey?) {
        val valid = activeSession != null && environmentId != null &&
            focusedKey?.environment == environmentId
        val nextSession = if (valid) activeSession else null
        val nextEnvironment = if (valid) environmentId else null
        val nextFocus = if (valid) focusedKey else null
        if (session !== nextSession || environment != nextEnvironment || focus != nextFocus) {
            revision++
            session = nextSession
            environment = nextEnvironment
            focus = nextFocus
        }
    }

    /** A fresh, explicit user-initiated request supersedes all prior requests. */
    fun begin(): Ticket? {
        val owner = session ?: return null
        val id = environment ?: return null
        val key = focus ?: return null
        revision++
        return Ticket(owner, id, key, revision)
    }

    /**
     * Recheck fresh caller identity after the suspension boundary *and* the
     * remembered binding. A stale Compose frame cannot publish a late reply.
     */
    fun current(
        ticket: Ticket,
        activeSession: Any?,
        environmentId: EnvironmentId?,
        focusedKey: UnifiedTransferKey?
    ): Boolean =
        ticket.revision == revision &&
            ticket.session === session &&
            ticket.session === activeSession &&
            ticket.environment == environment &&
            ticket.environment == environmentId &&
            ticket.focus == focus &&
            ticket.focus == focusedKey

    fun invalidate() {
        bind(null, null, null)
    }
}
