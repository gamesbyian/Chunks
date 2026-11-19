package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentCapabilitySnapshot
import io.github.gamesbyian.chunks.model.UniversalAddAdmission
import io.github.gamesbyian.chunks.model.UniversalAddCoordinator
import io.github.gamesbyian.chunks.model.UniversalAddExecutor
import io.github.gamesbyian.chunks.model.UniversalAddIntent
import io.github.gamesbyian.chunks.model.UniversalAddObserver
import io.github.gamesbyian.chunks.model.UniversalAddRun

/**
 * The ONE submit step every Universal Add body uses after the explicit Add
 * tap: shared admission (review → user approval → the client's own mutation
 * gate → one immutable ticket), then exactly one launch of that ticket in the
 * app-owned [UniversalAddAttemptController], with its read-only follow-up.
 *
 * Invariants, for every client:
 * - no ticket → nothing launched; the caller learns whether its gate was taken;
 * - [freeze] runs with the minted ticket before any write, so the body records
 *   what was reviewed (receipt state, original-file policy, alias) against it;
 * - a second write for the same client is refused by the controller before
 *   any write ([Result.ClientBusy]; the ticket is disarmed, the gate is still
 *   the caller's to release);
 * - never retried. Engine executors, observers and messages stay engine-owned.
 */
internal object UniversalAddSubmission {
    sealed interface Result {
        /** The single write is running in the app-owned scope. */
        data class Launched(val ticket: UniversalAddCoordinator.Ticket) : Result
        /** Admission refused before any write; [decision] names the fixed reason. */
        data class Refused(val decision: UniversalAddAdmission.Decision) : Result
        /** Another write for this exact client is still settling; this ticket never wrote. */
        data class ClientBusy(val ticket: UniversalAddCoordinator.Ticket) : Result
    }

    fun <R : Any> admitAndLaunch(
        controller: UniversalAddAttemptController,
        coordinator: UniversalAddCoordinator,
        intent: UniversalAddIntent,
        capabilities: EnvironmentCapabilitySnapshot,
        availability: EnvironmentAvailability,
        acquireMutationGate: () -> Boolean,
        submittedAtEpochMillis: Long,
        /** Record the reviewed state against the minted ticket; returns this ticket's one executor. */
        freeze: (UniversalAddCoordinator.Ticket) -> UniversalAddExecutor<R>,
        followUp: UniversalAddObserver? = null,
        followUpSchedule: List<Long> = UniversalAddAttemptController.REMOTE_FOLLOW_UP_MILLIS,
        onInterrupted: (writeMayHaveHappened: Boolean) -> Unit = {},
        onFinished: () -> Unit = {},
        onSettled: suspend (UniversalAddRun<R>) -> Unit = {}
    ): Result {
        val decision = UniversalAddAdmission.authorize(coordinator, intent, capabilities, availability,
            acquireMutationGate, submittedAtEpochMillis)
        val ticket = decision.ticket ?: return Result.Refused(decision)
        val executor = try {
            freeze(ticket)
        } catch (failure: Exception) {
            // Nothing was written: the reviewed state could not be recorded.
            coordinator.refusedBeforeAnyWrite(ticket)
            return Result.Refused(decision.copy(ticket = null,
                refusal = UniversalAddAdmission.Refusal.TICKET,
                explanation = "The Add could not be prepared. Nothing was sent."))
        }
        val launched = controller.launch(coordinator, ticket, executor,
            onInterrupted = onInterrupted, onFinished = onFinished,
            followUp = followUp, followUpSchedule = followUpSchedule, onSettled = onSettled)
        return if (launched) Result.Launched(ticket) else Result.ClientBusy(ticket)
    }
}
