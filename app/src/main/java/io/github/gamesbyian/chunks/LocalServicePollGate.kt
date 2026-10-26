package io.github.gamesbyian.chunks

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Admission control for periodic native-engine status reads.
 *
 * A stalled poll occupies the only slot, so later scheduler ticks are dropped
 * rather than placed in an unbounded executor queue. Never use this gate for
 * explicit user Stop all or Android foreground-service timeout handling.
 */
internal class LocalServicePollGate {
    private val inFlight = AtomicBoolean(false)

    fun tryBegin(): Boolean = inFlight.compareAndSet(false, true)

    /** Release from a finally block, including when the poll fails. */
    fun finish() {
        inFlight.set(false)
    }
}
