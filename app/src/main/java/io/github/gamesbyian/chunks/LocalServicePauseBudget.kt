package io.github.gamesbyian.chunks

import java.util.concurrent.TimeUnit

/**
 * A shared readback deadline for Android's forced dataSync timeout.
 *
 * Stop requests are still issued for every observed active transfer, even
 * after the budget is exhausted. The caller then uses readbackTimeoutMs=0
 * for each remaining transfer: one native status observation, no wait loop.
 * Native JNI and filesystem operations themselves are not cancellable here.
 */
internal class LocalServicePauseBudget(
    private val totalReadbackMs: Long = 2_500L,
    private val perTransferReadbackMs: Long = 2_000L,
    private val nowNanos: () -> Long = System::nanoTime
) {
    init {
        require(totalReadbackMs in 1L..60_000L)
        require(perTransferReadbackMs in 1L..60_000L)
    }

    private val startedNanos = nowNanos()

    fun nextReadbackTimeoutMs(): Long {
        val elapsed = (nowNanos() - startedNanos).coerceAtLeast(0L)
        val remaining = (TimeUnit.MILLISECONDS.toNanos(totalReadbackMs) - elapsed)
            .coerceAtLeast(0L)
        return minOf(perTransferReadbackMs, TimeUnit.NANOSECONDS.toMillis(remaining))
    }
}
