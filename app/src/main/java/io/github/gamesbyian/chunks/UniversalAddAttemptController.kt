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

    /** A write for this exact client is between admission and settlement. */
    fun isSubmitting(environment: EnvironmentId): Boolean = writing.containsKey(environment)
    fun isChecking(environment: EnvironmentId): Boolean = checking[environment] == true

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
        executor: UniversalAddExecutor<R>
    ): UniversalAddRun<R>? {
        val environment = ticket.intent.target.environment
        if (!claim(environment, coordinator, ticket)) return null
        try {
            return UniversalAddLifecycle.run(coordinator, ticket, executor)
        } finally {
            release(environment, ticket)
        }
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
        if (writing.containsKey(environment) || checking[environment] == true) return false
        if (coordinator.phase !in UniversalAddLifecycle.UNRESOLVED &&
            coordinator.phase != UniversalAddCoordinator.Phase.SESSION_CONFIRMED) return false
        checking[environment] = true
        scope().launch {
            try {
                val readback = try {
                    UniversalAddLifecycle.recheck(coordinator, observer)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) { null }
                onResult(readback)
            } finally {
                checking.remove(environment)
            }
        }
        return true
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
}
