package io.github.gamesbyian.chunks.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * At most one pending native resume-save request per infohash and handle.
 * Timed-out requests remain pending until their callback or explicit cancellation,
 * so a timeout alone never makes a subsequent request eligible for an old alert.
 * The native handle identity is required for completion and cancellation: an
 * alert for a removed handle cannot acknowledge a re-added torrent of the same hash.
 */
internal class LocalResumeSaveRequests<H : Any> {
    internal class Request<H : Any>(val handle: H) {
        private val latch = CountDownLatch(1)
        @Volatile private var confirmed = false

        fun resolve(success: Boolean) {
            confirmed = success
            latch.countDown()
        }

        fun awaitConfirmed(timeoutNanos: Long): Boolean =
            latch.await(timeoutNanos.coerceAtLeast(0L), TimeUnit.NANOSECONDS) && confirmed
    }

    private val outstanding = ConcurrentHashMap<String, Request<H>>()

    fun acquire(key: String, handle: H, invokeNative: () -> Unit): Request<H> {
        val proposed = Request(handle)
        val existing = outstanding.putIfAbsent(key, proposed)
        if (existing != null) {
            // Fail closed if removal/re-add raced an outstanding old generation.
            // Never coalesce two different native handles on the same infohash.
            if (existing.handle == handle) return existing
            proposed.resolve(false)
            return proposed
        }
        try {
            invokeNative()
        } catch (_: Exception) {
            if (outstanding.remove(key, proposed)) proposed.resolve(false)
        }
        return proposed
    }

    fun complete(key: String, handle: H, persisted: Boolean) {
        val request = outstanding[key] ?: return
        if (request.handle != handle) return
        if (outstanding.remove(key, request)) request.resolve(persisted)
    }

    fun cancel(key: String, handle: H) {
        val request = outstanding[key] ?: return
        if (request.handle == handle && outstanding.remove(key, request)) request.resolve(false)
    }

    fun cancelAll() {
        // Conditional removal prevents a retired generation's cancellation from
        // accidentally retiring a newly registered request at the same key.
        outstanding.entries.toList().forEach { (key, request) ->
            if (outstanding.remove(key, request)) request.resolve(false)
        }
    }
}
