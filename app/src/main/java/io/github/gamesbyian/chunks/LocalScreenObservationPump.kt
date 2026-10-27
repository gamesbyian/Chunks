package io.github.gamesbyian.chunks

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

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

/**
 * A read started before an uncertain native mutation is not evidence of the
 * post-mutation state, even when that read finishes after the failed command.
 * Only samples scheduled in the current revision can clear a stale UI.
 */
internal class LocalScreenObservationRevision {
    private val revision = AtomicLong(0L)
    fun snapshot(): Long = revision.get()
    fun invalidate() { revision.incrementAndGet() }
    fun accepts(sampleRevision: Long): Boolean = sampleRevision == revision.get()
}
