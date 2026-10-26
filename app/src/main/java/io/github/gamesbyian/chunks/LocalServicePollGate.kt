package io.github.gamesbyian.chunks

import java.util.concurrent.atomic.AtomicReference

/**
 * At most one outstanding native observation. A stalled call holds the slot
 * so ticks cannot accumulate thousands of queued JNI reads.
 *
 * The monotonic start time is held atomically with admission: a warning
 * observer never sees a running poll with an uninitialized timestamp.
 * This gate does not cancel a blocked native call.
 */
internal class LocalServicePollGate {
    private val startedNanos = AtomicReference<Long?>(null)

    fun tryBegin(nowNanos: Long = System.nanoTime()): Boolean =
        startedNanos.compareAndSet(null, nowNanos)

    /** Elapsed monotonic time for the current poll; null when idle. */
    fun inFlightMillis(nowNanos: Long = System.nanoTime()): Long? =
        startedNanos.get()?.let { ((nowNanos - it) / 1_000_000L).coerceAtLeast(0L) }

    /** Release from a finally block, including when the poll fails. */
    fun finish() {
        startedNanos.set(null)
    }
}
