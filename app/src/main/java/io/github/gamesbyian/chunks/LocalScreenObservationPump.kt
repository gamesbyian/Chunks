package io.github.gamesbyian.chunks

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

/**
 * Screen-side single-flight adapter for potentially blocking JNI observations.
 *
 * Kotlin coroutine cancellation cannot be relied on to interrupt a native
 * call. Never await the call on the UI's refresh coroutine: a stuck worker
 * owns its one admission slot instead of creating unbounded blocked threads.
 */
internal sealed interface LocalScreenPollOutcome<out T> {
    data class Fresh<T>(val value: T) : LocalScreenPollOutcome<T>
    data object Unavailable : LocalScreenPollOutcome<Nothing>
}

internal class LocalScreenObservationPump<T>(
    private val executor: ExecutorService
) {
    private val admission = LocalServicePollGate()
    private val latest = AtomicReference<LocalScreenPollOutcome<T>?>(null)

    /** At most one queued or running native observation, including when JNI hangs. */
    fun tryObserve(read: () -> T): Boolean {
        if (!admission.tryBegin()) return false
        return try {
            executor.execute {
                try {
                    latest.set(LocalScreenPollOutcome.Fresh(read()))
                } catch (_: Exception) {
                    latest.set(LocalScreenPollOutcome.Unavailable)
                } finally {
                    admission.finish()
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            admission.finish()
            false
        }
    }

    /** A completed read is consumed once; a missing result is not an empty inventory. */
    fun takeCompleted(): LocalScreenPollOutcome<T>? = latest.getAndSet(null)

    /** Monotonic age; null when no JNI observation is outstanding. */
    fun inFlightMillis(nowNanos: Long = System.nanoTime()): Long? =
        admission.inFlightMillis(nowNanos)

    fun shutdown() {
        executor.shutdown()
    }
}
