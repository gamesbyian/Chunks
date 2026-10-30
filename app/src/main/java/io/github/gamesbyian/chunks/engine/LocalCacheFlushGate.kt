package io.github.gamesbyian.chunks.engine

import java.util.concurrent.TimeUnit

/**
 * A cache-flush witness belongs to one native torrent handle, not just its
 * infohash. The engine monitor serializes registration/readback with removal
 * and close; awaiting an alert must release that monitor.
 */
internal class LocalCacheFlushGate<H : Any>(private val engineMonitor: Any) {
    // Reuse the existing callback coalescing and handle-generation guard.
    private val requests = LocalResumeSaveRequests<H>()

    fun flush(
        key: String,
        timeoutMs: Long,
        currentHandle: () -> H?,
        isComplete: (H) -> Boolean,
        invokeNative: (H) -> Unit
    ): Boolean {
        if (timeoutMs <= 0L) return false
        val (handle, pending) = synchronized(engineMonitor) {
            val current = currentHandle() ?: return false
            if (!isComplete(current)) return false
            current to requests.acquire(key, current) { invokeNative(current) }
        }
        val completed = try {
            pending.awaitConfirmed(TimeUnit.MILLISECONDS.toNanos(timeoutMs))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) return false
        return synchronized(engineMonitor) {
            currentHandle() == handle && isComplete(handle)
        }
    }

    fun complete(key: String, handle: H) = requests.complete(key, handle, true)

    /** Call while holding the engine monitor that retires this handle. */
    fun cancel(key: String, handle: H) = requests.cancel(key, handle)

    /** Call while holding the engine monitor that closes the session. */
    fun cancelAll() = requests.cancelAll()
}
