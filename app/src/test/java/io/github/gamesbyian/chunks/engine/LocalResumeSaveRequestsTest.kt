package io.github.gamesbyian.chunks.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalResumeSaveRequestsTest {
    private class Handle(val number: Int)

    @Test fun overlappingSavesShareOneNativeRequestAndOneCompletion() {
        val saves = LocalResumeSaveRequests<Handle>()
        val handle = Handle(1)
        var calls = 0
        val a = saves.acquire("hash", handle) { calls++ }
        val b = saves.acquire("hash", handle) { calls++ }
        assertSame(a, b)
        assertEquals(1, calls)
        saves.complete("hash", handle, true)
        assertTrue(a.awaitConfirmed(0))
        assertTrue(b.awaitConfirmed(0))
    }

    @Test fun timeoutDoesNotAdmitSecondGenerationBeforeOldAlert() {
        val saves = LocalResumeSaveRequests<Handle>()
        val handle = Handle(1)
        var calls = 0
        val first = saves.acquire("hash", handle) { calls++ }
        assertFalse(first.awaitConfirmed(0))
        assertSame(first, saves.acquire("hash", handle) { calls++ })
        assertEquals(1, calls)
        saves.complete("hash", handle, true)
        assertTrue(first.awaitConfirmed(0))
        val second = saves.acquire("hash", handle) { calls++ }
        assertEquals(2, calls)
        assertFalse(second.awaitConfirmed(0))
        saves.complete("hash", handle, false)
        assertFalse(second.awaitConfirmed(0))
    }

    @Test fun failedAndCancelledSavesAreNeverCountedAsPersisted() {
        val saves = LocalResumeSaveRequests<Handle>()
        val a = Handle(1)
        val b = Handle(2)
        val c = Handle(3)
        val refused = saves.acquire("a", a) { throw IllegalStateException("native refused") }
        assertFalse(refused.awaitConfirmed(0))
        val pending = saves.acquire("b", b) {}
        saves.cancel("b", b)
        assertFalse(pending.awaitConfirmed(0))
        val failedAlert = saves.acquire("c", c) {}
        saves.complete("c", c, false)
        assertFalse(failedAlert.awaitConfirmed(0))
    }

    @Test fun independentHashesCanCompleteWhileAnotherSaveIsBlocked() {
        val saves = LocalResumeSaveRequests<Handle>()
        val a = Handle(1)
        val b = Handle(2)
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val task = worker.submit<LocalResumeSaveRequests.Request<Handle>> {
                saves.acquire("a", a) {
                    blocked.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
            }
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            val other = saves.acquire("b", b) {}
            saves.complete("b", b, true)
            assertTrue(other.awaitConfirmed(0))
            release.countDown()
            val first = task.get(2, TimeUnit.SECONDS)
            saves.complete("a", a, true)
            assertTrue(first.awaitConfirmed(0))
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun shutdownCancelsAllUnresolvedWaiters() {
        val saves = LocalResumeSaveRequests<Handle>()
        val a = Handle(1)
        val b = Handle(2)
        val one = saves.acquire("one", a) {}
        val two = saves.acquire("two", b) {}
        saves.cancelAll()
        assertFalse(one.awaitConfirmed(0))
        assertFalse(two.awaitConfirmed(0))
        val next = saves.acquire("one", a) {}
        assertFalse(next.awaitConfirmed(0))
        saves.complete("one", a, true)
        assertTrue(next.awaitConfirmed(0))
    }

    @Test fun failedNativeInvocationDoesNotPreventAnotherHashFromSaving() {
        val saves = LocalResumeSaveRequests<Handle>()
        val a = Handle(1)
        val b = Handle(2)
        val failed = saves.acquire("bad", a) { throw IllegalStateException("I/O unavailable") }
        assertFalse(failed.awaitConfirmed(0))
        val healthy = saves.acquire("good", b) {}
        saves.complete("good", b, true)
        assertTrue(healthy.awaitConfirmed(0))
        val retry = saves.acquire("bad", a) {}
        assertFalse(retry.awaitConfirmed(0))
        saves.complete("bad", a, true)
        assertTrue(retry.awaitConfirmed(0))
    }

    @Test fun oldHandleCompletionCannotConfirmNewHandleWithSameInfohash() {
        val saves = LocalResumeSaveRequests<Handle>()
        val old = Handle(1)
        val replacement = Handle(2)
        val first = saves.acquire("same-hash", old) {}
        saves.cancel("same-hash", old)
        assertFalse(first.awaitConfirmed(0))
        val second = saves.acquire("same-hash", replacement) {}
        saves.complete("same-hash", old, true)
        assertFalse(second.awaitConfirmed(0))
        saves.cancel("same-hash", old)
        assertFalse(second.awaitConfirmed(0))
        saves.complete("same-hash", replacement, true)
        assertTrue(second.awaitConfirmed(0))
    }

    @Test fun oldPendingHandleCannotBeCoalescedWithReaddedHandle() {
        val saves = LocalResumeSaveRequests<Handle>()
        val old = Handle(1)
        val replacement = Handle(2)
        var calls = 0
        val first = saves.acquire("same-hash", old) { calls++ }
        val refused = saves.acquire("same-hash", replacement) { calls++ }
        assertEquals(1, calls)
        assertFalse(refused.awaitConfirmed(0))
        saves.complete("same-hash", old, true)
        assertTrue(first.awaitConfirmed(0))
        val next = saves.acquire("same-hash", replacement) { calls++ }
        assertEquals(2, calls)
        saves.complete("same-hash", replacement, true)
        assertTrue(next.awaitConfirmed(0))
    }

    @Test fun unexpectedHandleFailureCannotDiscardValidPendingSave() {
        val saves = LocalResumeSaveRequests<Handle>()
        val old = Handle(1)
        val newHandle = Handle(2)
        val valid = saves.acquire("same-hash", newHandle) {}
        saves.complete("same-hash", old, false)
        assertFalse(valid.awaitConfirmed(0))
        saves.complete("same-hash", newHandle, true)
        assertTrue(valid.awaitConfirmed(0))
    }
}
