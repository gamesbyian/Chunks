package io.github.gamesbyian.chunks.model

import kotlinx.coroutines.CancellationException

/**
 * The one engine-neutral execution and observation lifecycle for an
 * authorized Universal Add ticket. Local (libtorrent4j), rTorrent and the
 * Transmission/qBittorrent/Deluge clients each supply an executor. None of
 * them settles the shared [UniversalAddCoordinator] itself.
 *
 * The lifecycle enforces the same rules for every client:
 * - a result or failure **before** [UniversalAddWriteBoundary.enter] proves no write, so the
 *   ticket may disarm;
 * - an exception, cancellation or unclassifiable result **after** the boundary
 *   keeps the attempted-write fence (READBACK_REQUIRED). Nothing is ever retried;
 * - only executor-supplied exact-session evidence can verify the Add;
 * - [recheck] is read-only. It can never reach an executor's write path.
 *
 * Call from one serialized (main) lane, like the coordinator itself. Executors
 * move their own IO off it. No source, credential or path is logged here.
 */
internal class UniversalAddWriteBoundary {
    @Volatile private var crossed = false
    val entered: Boolean get() = crossed

    /** Call immediately before the first non-idempotent native/RPC command. */
    fun enter() { crossed = true }
}

/** How one completed, boundary-crossing execution settles its ticket. */
internal sealed interface UniversalAddSettlement {
    /** The engine proved that nothing was written (definite refusal, precondition, already present). */
    data object KnownNoWrite : UniversalAddSettlement
    /** Something may have landed; only read-only reconciliation may continue. */
    data object ReadbackRequired : UniversalAddSettlement
    /** The engine's exact-hash readback of this write. */
    data class Observed(val observation: UniversalAddCoordinator.ExactSessionObservation) : UniversalAddSettlement
}

/** One engine's single write for a frozen ticket. */
internal interface UniversalAddExecutor<R : Any> {
    /**
     * Perform exactly one Add for [ticket]: pre-write checks, [boundary] entry,
     * then the engine's own write and readback. Never retry. Throwing before
     * the boundary is a known no-write. Throwing after it is uncertain.
     */
    suspend fun execute(ticket: UniversalAddCoordinator.Ticket, boundary: UniversalAddWriteBoundary): R

    /**
     * Classify a result whose execution crossed the boundary. Allowed:
     * read-only getters and recording adapter-owned positive receipts.
     * Not allowed: another write.
     */
    suspend fun settle(ticket: UniversalAddCoordinator.Ticket, result: R): UniversalAddSettlement

    /**
     * The execution failed or was cancelled after crossing the boundary.
     * Record any adapter-owned positive receipt it already returned (for
     * example Local's native registration token). Never write here.
     */
    fun recordInterrupted(ticket: UniversalAddCoordinator.Ticket) {}
}

/** One read-only exact-hash observation of a retained ticket's client. */
internal fun interface UniversalAddObserver {
    /** Null means no proof was obtained. It is never an invitation to write. */
    suspend fun observe(ticket: UniversalAddCoordinator.Ticket): UniversalAddCoordinator.ExactSessionObservation?
}

internal sealed interface UniversalAddRun<out R : Any> {
    val ticket: UniversalAddCoordinator.Ticket
    /** Whether the execution crossed its write boundary. */
    val writeMayHaveHappened: Boolean

    data class Completed<R : Any>(
        override val ticket: UniversalAddCoordinator.Ticket,
        val result: R,
        val settlement: UniversalAddSettlement,
        val readback: UniversalAddCoordinator.Readback?,
        override val writeMayHaveHappened: Boolean
    ) : UniversalAddRun<R>

    data class Failed(
        override val ticket: UniversalAddCoordinator.Ticket,
        override val writeMayHaveHappened: Boolean
    ) : UniversalAddRun<Nothing>
}

internal object UniversalAddLifecycle {
    /** Phases in which a ticket's write may have landed but is not yet settled. */
    val UNRESOLVED: Set<UniversalAddCoordinator.Phase> = setOf(
        UniversalAddCoordinator.Phase.WRITE_ATTEMPTED,
        UniversalAddCoordinator.Phase.READBACK_REQUIRED,
        UniversalAddCoordinator.Phase.OBSERVED_UNCERTAIN,
        UniversalAddCoordinator.Phase.JOB_CONFIRMED_START_UNVERIFIED
    )

    suspend fun <R : Any> run(
        coordinator: UniversalAddCoordinator,
        ticket: UniversalAddCoordinator.Ticket,
        executor: UniversalAddExecutor<R>
    ): UniversalAddRun<R> {
        require(coordinator.retainedTicket === ticket &&
            coordinator.phase == UniversalAddCoordinator.Phase.WRITE_ATTEMPTED) {
            "Only a freshly minted ticket can be executed, and only once"
        }
        val boundary = UniversalAddWriteBoundary()
        val result = try {
            executor.execute(ticket, boundary)
        } catch (cancel: CancellationException) {
            interrupted(coordinator, ticket, boundary, executor)
            throw cancel
        } catch (_: Exception) {
            interrupted(coordinator, ticket, boundary, executor)
            return UniversalAddRun.Failed(ticket, boundary.entered)
        }
        if (!boundary.entered) {
            coordinator.refusedBeforeAnyWrite(ticket)
            return UniversalAddRun.Completed(ticket, result, UniversalAddSettlement.KnownNoWrite, null, false)
        }
        val settlement = try {
            executor.settle(ticket, result)
        } catch (cancel: CancellationException) {
            coordinator.requireReadback(ticket)
            throw cancel
        } catch (_: Exception) {
            UniversalAddSettlement.ReadbackRequired
        }
        val readback = when (settlement) {
            UniversalAddSettlement.KnownNoWrite -> { coordinator.refusedBeforeAnyWrite(ticket); null }
            UniversalAddSettlement.ReadbackRequired -> { coordinator.requireReadback(ticket); null }
            is UniversalAddSettlement.Observed -> {
                coordinator.requireReadback(ticket)
                coordinator.reconcileExactSession(ticket, settlement.observation)
            }
        }
        return UniversalAddRun.Completed(ticket, result, settlement, readback, true)
    }

    /**
     * Read-only recovery of an unresolved ticket. Returns null when there is
     * nothing to check or no proof was obtained; the fence is unchanged then.
     */
    suspend fun recheck(
        coordinator: UniversalAddCoordinator,
        observer: UniversalAddObserver
    ): UniversalAddCoordinator.Readback? {
        val ticket = coordinator.retainedTicket ?: return null
        if (coordinator.phase !in UNRESOLVED && coordinator.phase != UniversalAddCoordinator.Phase.SESSION_CONFIRMED)
            return null
        val observation = observer.observe(ticket) ?: return null
        // The ticket may have been resolved while the read was in flight.
        if (coordinator.retainedTicket !== ticket) return null
        return coordinator.reconcileExactSession(ticket, observation)
    }

    private fun interrupted(
        coordinator: UniversalAddCoordinator,
        ticket: UniversalAddCoordinator.Ticket,
        boundary: UniversalAddWriteBoundary,
        executor: UniversalAddExecutor<*>
    ) {
        if (boundary.entered) {
            runCatching { executor.recordInterrupted(ticket) }
            coordinator.requireReadback(ticket)
        } else coordinator.refusedBeforeAnyWrite(ticket)
    }
}
