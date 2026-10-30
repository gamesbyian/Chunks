package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import kotlinx.coroutines.CancellationException

/**
 * Presentation boundary for one already-authorized remote lifecycle request.
 * A failed/ambiguous request remains unverified; an obsolete Compose coroutine
 * must stop when its scope is cancelled, rather than perform subsequent reads.
 * Remote adapters retain responsibility for write idempotence and readback.
 */
internal suspend fun remoteLifecycleOutcomeOrUnknown(
    action: suspend () -> LifecycleOutcome
): LifecycleOutcome? = try {
    action()
} catch (cancel: CancellationException) {
    throw cancel
} catch (_: Exception) {
    null
}
