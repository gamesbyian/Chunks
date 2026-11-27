package io.github.gamesbyian.chunks

import androidx.compose.runtime.mutableStateMapOf
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddExecutor
import io.github.gamesbyian.chunks.model.UniversalAddLifecycle
import io.github.gamesbyian.chunks.model.UniversalAddObserver
import io.github.gamesbyian.chunks.model.UniversalAddRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app-owned owner of every in-flight Universal Add write and read-only
 * recheck, for Local, rTorrent and the Transmission/qBittorrent/Deluge clients.
 *
 * It holds no source, credential or private path. It owns:
 * - the coroutine lifetime: the Activity-retained [scope], never a
 *   disposable Add route;
 * - one in-flight write per exact client;
 * - one in-flight read-only recheck per exact client;
 * - the observable "submitting" fact for that client.
 *
 * The shared [UniversalAddLifecycle] settles each ticket. Process death loses
 * an in-flight entry. It is never persisted or automatically resubmitted.
 */
internal class UniversalAddAttemptController(private val scope: () -> CoroutineScope) {
    private val writing = mutableStateMapOf<EnvironmentId, UniversalAddCoordinator.Ticket>()
    private val checking = mutableStateMapOf<EnvironmentId, Boolean>()
    // Compose-observable count of read-only observations applied per client, so
    // any screen showing a receipt recomposes when retained evidence changes.
    private val observations = mutableStateMapOf<EnvironmentId, Int>()
    private val following = mutableMapOf<EnvironmentId, Job>()
    // A person's explicit recheck that arrived while a background follow-up check was in flight
    // (#2138): queued, at most one per client, and started as soon as that follow-up check ends.
    private class QueuedRecheck(
        val coordinator: UniversalAddCoordinator,
        val observer: UniversalAddObserver,
        val onResult: (UniversalAddCoordinator.Readback?) -> Unit
    )
    private val followUpChecking = mutableSetOf<EnvironmentId>()
    private val queuedRechecks = mutableMapOf<EnvironmentId, QueuedRecheck>()

    /** A write for this exact client is between admission and settlement. */
    fun isSubmitting(environment: EnvironmentId): Boolean = writing.containsKey(environment)
    fun isChecking(environment: EnvironmentId): Boolean = checking[environment] == true
    /** Changes whenever a read-only observation for this client was applied. */
    fun observationRevision(environment: EnvironmentId): Int = observations[environment] ?: 0
    /** A bounded app-owned read-only follow-up is scheduled for this client. */
    fun isFollowingUp(environment: EnvironmentId): Boolean = following[environment]?.isActive == true

    /**
     * Execute [ticket] once in the app-owned scope. A second ticket for a
     * client that already has a write in flight is refused before any write
     * (its own ticket is disarmed) and returns false.
     *
     * [onSettled] runs in the same retained coroutine after the coordinator
     * has been settled (post-processing such as notices, aliases or source
     * handling). [onInterrupted] runs if the coroutine is cancelled first;
     * the coordinator has already kept or released the fence correctly.
     */
    fun <R : Any> launch(
        coordinator: UniversalAddCoordinator,
        ticket: UniversalAddCoordinator.Ticket,
        executor: UniversalAddExecutor<R>,
        onInterrupted: (writeMayHaveHappened: Boolean) -> Unit = {},
        /** Always runs last, after settlement or interruption (e.g. releasing a screen gate). */
        onFinished: () -> Unit = {},
        /**
         * Optional read-only observer. When the ticket is still unresolved after
         * settlement, the app-owned scope keeps observing it on a bounded
         * schedule ([followUp]); never a write.
         */
        followUp: UniversalAddObserver? = null,
        followUpSchedule: List<Long> = REMOTE_FOLLOW_UP_MILLIS,
        onSettled: suspend (UniversalAddRun<R>) -> Unit = {}
    ): Boolean {
        val environment = ticket.intent.target.environment
        if (!claim(environment, coordinator, ticket)) return false
        // UNDISPATCHED: the try/finally is entered even if the scope is cancelled before
        // dispatch, so the in-flight claim and the coordinator fence always settle.
        scope().launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val run = try {
                    UniversalAddLifecycle.run(coordinator, ticket, executor)
                } catch (cancel: CancellationException) {
                    onInterrupted(coordinator.phase != UniversalAddCoordinator.Phase.REVIEWED)
                    throw cancel
                }
                try {
                    onSettled(run)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    // Post-processing must not crash the app-owned scope. The
                    // coordinator was already settled from engine evidence.
                }
            } finally {
                release(environment, ticket)
                onFinished()
            }
            if (followUp != null) followUp(coordinator, followUp, followUpSchedule)
        }
        return true
    }

    /**
     * The same lifecycle inside a caller's own retained coroutine (Local's
     * native mutation lane already runs in the app-owned scope and holds the
     * process-wide native gate). Returns null when another write for the same
     * client was in flight; that ticket was disarmed without a write.
     */
    suspend fun <R : Any> run(
        coordinator: UniversalAddCoordinator,
        ticket: UniversalAddCoordinator.Ticket,
        executor: UniversalAddExecutor<R>,
        /** As for [launch]: bounded read-only observation of an unresolved settlement. */
        followUp: UniversalAddObserver? = null,
        followUpSchedule: List<Long> = LOCAL_FOLLOW_UP_MILLIS
    ): UniversalAddRun<R>? {
        val environment = ticket.intent.target.environment
        if (!claim(environment, coordinator, ticket)) return null
        val run = try {
            UniversalAddLifecycle.run(coordinator, ticket, executor)
        } finally {
            release(environment, ticket)
        }
        if (followUp != null) followUp(coordinator, followUp, followUpSchedule)
        return run
    }

    /**
     * Keep observing the client's retained, unresolved ticket from the
     * app-owned scope: one read-only [UniversalAddLifecycle.recheck] after each
     * delay in [schedule], until the ticket is resolved, replaced, or the
     * schedule ends. It survives leaving Add and Activity recreation (not
     * process death). It never overlaps a write or a manual recheck for the
     * same client, and never writes. A newer follow-up for the same client
     * replaces an older one. Returns false when nothing is unresolved.
     */
    fun followUp(
        coordinator: UniversalAddCoordinator,
        observer: UniversalAddObserver,
        schedule: List<Long> = REMOTE_FOLLOW_UP_MILLIS,
        onResult: (UniversalAddCoordinator.Readback?) -> Unit = {}
    ): Boolean {
        val ticket = coordinator.retainedTicket ?: return false
        if (coordinator.phase !in UniversalAddLifecycle.UNRESOLVED) return false
        val environment = ticket.intent.target.environment
        following.remove(environment)?.cancel()
        following[environment] = scope().launch {
            for (wait in schedule) {
                delay(wait)
                if (coordinator.retainedTicket !== ticket ||
                    coordinator.phase !in UniversalAddLifecycle.UNRESOLVED) break
                // Never alongside a write or a person's own Check status.
                if (writing.containsKey(environment) || checking[environment] == true) continue
                checking[environment] = true
                followUpChecking += environment
                val readback = try {
                    UniversalAddLifecycle.recheck(coordinator, observer)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) { null } finally {
                    followUpChecking -= environment
                    checking.remove(environment)
                    startQueuedRecheck(environment)
                }
                if (readback != null) observations[environment] = observationRevision(environment) + 1
                onResult(readback)
            }
        }
        return true
    }

    /**
     * One read-only exact-hash recheck of the client's retained ticket. False
     * when a write or another recheck for this client is in flight, or no
     * unresolved ticket exists. [onResult] receives the coordinator's readback
     * (null if no proof was obtained).
     */
    fun recheck(
        coordinator: UniversalAddCoordinator,
        observer: UniversalAddObserver,
        onResult: (UniversalAddCoordinator.Readback?) -> Unit = {}
    ): Boolean {
        val ticket = coordinator.retainedTicket ?: return false
        val environment = ticket.intent.target.environment
        if (writing.containsKey(environment)) return false
        if (coordinator.phase !in UniversalAddLifecycle.UNRESOLVED &&
            coordinator.phase != UniversalAddCoordinator.Phase.SESSION_CONFIRMED) return false
        if (checking[environment] == true) {
            // A background follow-up must not swallow the person's own check: run it right after.
            // Another manual check in flight still refuses (single-flight), as does a second queue.
            if (environment !in followUpChecking || environment in queuedRechecks) return false
            queuedRechecks[environment] = QueuedRecheck(coordinator, observer, onResult)
            return true
        }
        checking[environment] = true
        scope().launch {
            try {
                val readback = try {
                    UniversalAddLifecycle.recheck(coordinator, observer)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) { null }
                if (readback != null) observations[environment] = observationRevision(environment) + 1
                onResult(readback)
            } finally {
                checking.remove(environment)
            }
        }
        return true
    }

    /**
     * Start the person's queued recheck once the follow-up check that delayed it has ended. It is
     * admitted again from scratch (no write in flight, still unresolved); if it no longer may run,
     * its caller is told that no proof was obtained instead of hearing nothing.
     */
    private fun startQueuedRecheck(environment: EnvironmentId) {
        val queued = queuedRechecks.remove(environment) ?: return
        if (!recheck(queued.coordinator, queued.observer, queued.onResult)) queued.onResult(null)
    }

    private fun claim(
        environment: EnvironmentId,
        coordinator: UniversalAddCoordinator,
        ticket: UniversalAddCoordinator.Ticket
    ): Boolean {
        if (writing.containsKey(environment)) {
            // Never two writes for one client: this ticket never reached a write.
            coordinator.refusedBeforeAnyWrite(ticket)
            return false
        }
        writing[environment] = ticket
        return true
    }

    private fun release(environment: EnvironmentId, ticket: UniversalAddCoordinator.Ticket) {
        if (writing[environment] === ticket) writing.remove(environment)
    }

    companion object {
        /**
         * Local: a cheap native list. Every second at first, then every 5 s for
         * about ten minutes, so a magnet whose metadata arrives later can still
         * prove its deferred payload Start without the Add screen being shown.
         */
        val LOCAL_FOLLOW_UP_MILLIS: List<Long> = List(10) { 1_000L } + List(118) { 5_000L }
        /** Remote: one exact-hash getter at a modest, bounded cadence (about two minutes). */
        val REMOTE_FOLLOW_UP_MILLIS: List<Long> = listOf(2_000L, 3_000L, 5_000L, 10_000L) + List(10) { 10_000L }
    }
}
