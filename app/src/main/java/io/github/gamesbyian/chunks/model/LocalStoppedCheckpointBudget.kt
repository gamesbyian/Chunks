package io.github.gamesbyian.chunks.model

import kotlinx.coroutines.CancellationException

/**
 * A shared, monotonic deadline for post-Stop fast-resume persistence.
 * No batch item may borrow a prior pre-Stop save as its recovery receipt.
 *
 * The native API can block inside JNI, so this bounds its asynchronous wait
 * budgets, not the execution time of arbitrary native calls. An exhausted
 * budget remains unverified; it is never permission to repeat Stop.
 */
internal class LocalStoppedCheckpointBudget(
    totalTimeoutMs: Long = 5_000L,
    private val nowNanos: () -> Long = System::nanoTime
) {
    init { require(totalTimeoutMs in 0L..30_000L) }
    private val deadlineNanos = nowNanos() + totalTimeoutMs * 1_000_000L

    fun confirm(hash: String, persist: (String, Long) -> Boolean): Boolean {
        val remainingNanos = (deadlineNanos - nowNanos()).coerceAtLeast(0L)
        val remainingMs = remainingNanos / 1_000_000L
        if (remainingMs == 0L) return false
        return try {
            persist(hash, remainingMs)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        fun warning(unconfirmed: Int): String =
            if (unconfirmed <= 0) "" else
                " $unconfirmed stopped phone transfer recovery checkpoint(s) could not be " +
                    "confirmed. Keep Chunks open for the next save; downloaded files remain untouched."
    }
}
