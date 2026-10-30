package io.github.gamesbyian.chunks.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCacheFlushGateTest {
    private class Handle(var complete: Boolean = true)

    @Test fun incompleteTransferNeverRequestsAFlush() {
        val gate = LocalCacheFlushGate<Handle>(Any())
        val incomplete = Handle(complete = false)
        var calls = 0
        assertFalse(gate.flush("hash", 100, { incomplete }, { it.complete }) { calls++ })
        assertFalse(gate.flush("missing", 100, { null }, { it.complete }) { calls++ })
        assertEquals(0, calls)
    }

    @Test fun captureAndNativeRequestAndReadbackShareTheRemovalMonitor() {
        val monitor = Any()
        val gate = LocalCacheFlushGate<Handle>(monitor)
        val handle = Handle()
        var observations = 0
        assertTrue(gate.flush("hash", 100,
            currentHandle = {
                assertTrue(Thread.holdsLock(monitor))
                handle
            },
            isComplete = {
                assertTrue(Thread.holdsLock(monitor))
                observations++
                it.complete
            },
            invokeNative = {
                assertTrue(Thread.holdsLock(monitor))
                gate.complete("hash", it)
            }))
        assertEquals(2, observations)
    }

    @Test fun completedFlushCannotAuthorizeAReplacementWithTheSameInfohash() {
        val monitor = Any()
        val gate = LocalCacheFlushGate<Handle>(monitor)
        val old = Handle()
        val replacement = Handle()
        var current = old
        assertFalse(gate.flush("hash", 100, { current }, { it.complete }) {
            gate.complete("hash", old)
            // The old cache was flushed, but a new registration now owns this
            // hash by the time the caller performs its publication readback.
            gate.cancel("hash", old)
            current = replacement
        })
    }

    @Test fun readbackMustStillBeCompleteAfterARealFlush() {
        val gate = LocalCacheFlushGate<Handle>(Any())
        val handle = Handle()
        assertFalse(gate.flush("hash", 100, { handle }, { it.complete }) {
            gate.complete("hash", it)
            it.complete = false
        })
    }

    @Test fun lateRemovedHandleAlertCannotAcknowledgeReplacementCache() {
        val monitor = Any()
        val gate = LocalCacheFlushGate<Handle>(monitor)
        val old = Handle()
        val replacement = Handle()
        var current = old
        var calls = 0
        // First request times out but remains outstanding until removal.
        assertFalse(gate.flush("hash", 1, { current }, { it.complete }) { calls++ })
        synchronized(monitor) {
            gate.cancel("hash", old)
            current = replacement
        }
        assertFalse(gate.flush("hash", 1, { current }, { it.complete }) {
            calls++
            // An alert's old key lookup may have happened before removal.
            gate.complete("hash", old)
        })
        assertEquals(2, calls)
        // The replacement's own alert is the only acceptable cache witness.
        gate.complete("hash", replacement)
        assertTrue(gate.flush("hash", 100, { current }, { it.complete }) {
            calls++
            gate.complete("hash", it)
        })
        assertEquals(3, calls)
    }

    @Test fun timedOutFlushRemainsCoalescedUntilItsActualCallback() {
        val gate = LocalCacheFlushGate<Handle>(Any())
        val handle = Handle()
        var calls = 0
        assertFalse(gate.flush("hash", 1, { handle }, { it.complete }) { calls++ })
        assertFalse(gate.flush("hash", 1, { handle }, { it.complete }) { calls++ })
        assertEquals(1, calls)
        gate.complete("hash", handle)
    }

    @Test fun waitingForNativeAlertDoesNotHoldTheRemovalMonitor() {
        val monitor = Any()
        val gate = LocalCacheFlushGate<Handle>(monitor)
        val handle = Handle()
        val requested = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> {
                gate.flush("hash", 5_000, { handle }, { it.complete }) { requested.countDown() }
            }
            assertTrue(requested.await(2, TimeUnit.SECONDS))
            synchronized(monitor) { gate.cancel("hash", handle) }
            assertFalse(result.get(2, TimeUnit.SECONDS))
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun nativeRefusalAndSessionCloseCannotAuthorizePublication() {
        val monitor = Any()
        val gate = LocalCacheFlushGate<Handle>(monitor)
        val handle = Handle()
        assertFalse(gate.flush("hash", 100, { handle }, { it.complete }) {
            throw IllegalStateException("native flush failed")
        })
        assertFalse(gate.flush("hash", 100, { handle }, { it.complete }) {
            gate.cancelAll()
        })
    }

    @Test fun interruptedWaitRefusesPublicationAndPreservesInterrupt() {
        val gate = LocalCacheFlushGate<Handle>(Any())
        val handle = Handle()
        try {
            Thread.currentThread().interrupt()
            assertFalse(gate.flush("hash", 100, { handle }, { it.complete }) {})
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
