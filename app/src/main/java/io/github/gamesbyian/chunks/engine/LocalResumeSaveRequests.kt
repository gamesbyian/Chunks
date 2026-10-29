package io.github.gamesbyian.chunks.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Coalesces outstanding save-resume requests for each exact infohash.
 * A timed-out request remains registered until its alert arrives or removal
 * cancels it. An old alert cannot certify a subsequently issued request.
 */
internal class LocalResumeSaveRequests {
    internal class Request {
        private val latch = CountDownLatch(1)
        @Volatile private var confirmed = false

        fun resolve(success: Boolean) {
            confirmed = success
            latch.countDown()
        }

        fun awaitConfirmed(timeoutNanos: Long): Boolean =
            latch.await(timeoutNanos.coerceAtLeast(0L), TimeUnit.NANOSECONDS) && confirmed
    }

    private val outstanding = ConcurrentHashMap<String, Request>()

    fun acquire(key: String, invokeNative: () -> Unit): Request {
        val proposed = Request()
        val existing = outstanding.putIfAbsent(key, proposed)
        if (existing != null) return existing
        try {
            invokeNative()
        } catch (_: Exception) {
            if (outstanding.remove(key, proposed)) proposed.resolve(false)
        }
        return proposed
    }

    fun complete(key: String, persisted: Boolean) {
        outstanding.remove(key)?.resolve(persisted)
    }

    fun cancel(key: String) {
        outstanding.remove(key)?.resolve(false)
    }

    fun cancelAll() {
        outstanding.keys.toList().forEach(::cancel)
    }
}
