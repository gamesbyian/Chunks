package io.github.gamesbyian.chunks.rpc

/**
 * A user-approved Start after Add is a second mutation, performed ONLY after
 * the new exact hash and stopped destination have been positively verified.
 * Never issue Start for an existing hash, pending Add or lost acknowledgement.
 *
 * This must be called under the screen's single mutation gate. The caller
 * settles the gate once after both attempted operations, then refreshes the
 * inventory. A non-verified Start remains uncertain and is never retried.
 */
internal sealed interface RemoteAddAndStart {
    data class AddedStopped(val result: RemoteAddOutcome) : RemoteAddAndStart
    data class Started(val hash: String) : RemoteAddAndStart
    data class StartNotVerified(val hash: String, val result: LifecycleOutcome?) : RemoteAddAndStart
}

internal object RemoteAddStarter {
    suspend fun run(
        startAfterVerifiedAdd: Boolean,
        add: suspend () -> RemoteAddOutcome,
        start: suspend (String) -> LifecycleOutcome
    ): RemoteAddAndStart {
        val result = add()
        if (!startAfterVerifiedAdd || result !is RemoteAddOutcome.VerifiedStopped)
            return RemoteAddAndStart.AddedStopped(result)
        // The newly added exact hash, never a filename, supplies the target.
        val followup = try {
            start(result.hash)
        } catch (_: Exception) {
            null // Start might have applied. Do not retry.
        }
        return if (followup is LifecycleOutcome.Verified && followup.started)
            RemoteAddAndStart.Started(result.hash)
        else RemoteAddAndStart.StartNotVerified(result.hash, followup)
    }
}
