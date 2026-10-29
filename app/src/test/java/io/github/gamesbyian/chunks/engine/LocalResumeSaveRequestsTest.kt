package io.github.gamesbyian.chunks.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalResumeSaveRequestsTest {
    @Test fun overlappingSavesShareOneNativeRequestAndOneCompletion() {
        val saves = LocalResumeSaveRequests()
        var calls = 0
        val a = saves.acquire("hash") { calls++ }
        val b = saves.acquire("hash") { calls++ }
        assertSame(a, b)
        assertEquals(1, calls)
        saves.complete("hash", true)
        assertTrue(a.awaitConfirmed(0))
        assertTrue(b.awaitConfirmed(0))
    }

    @Test fun timeoutDoesNotAdmitSecondGenerationBeforeOldAlert() {
        val saves = LocalResumeSaveRequests()
        var calls = 0
        val first = saves.acquire("hash") { calls++ }
        assertFalse(first.awaitConfirmed(0))
        assertSame(first, saves.acquire("hash") { calls++ })
        assertEquals(1, calls)
        saves.complete("hash", true)
        assertTrue(first.awaitConfirmed(0))
        val second = saves.acquire("hash") { calls++ }
        assertEquals(2, calls)
        assertFalse(second.awaitConfirmed(0))
        saves.complete("hash", false)
        assertFalse(second.awaitConfirmed(0))
    }

    @Test fun failedAndCancelledSavesAreNeverCountedAsPersisted() {
        val saves = LocalResumeSaveRequests()
        val refused = saves.acquire("a") { throw IllegalStateException("native refused") }
        assertFalse(refused.awaitConfirmed(0))
        val pending = saves.acquire("b") {}
        saves.cancel("b")
        assertFalse(pending.awaitConfirmed(0))
        val failedAlert = saves.acquire("c") {}
        saves.complete("c", false)
        assertFalse(failedAlert.awaitConfirmed(0))
    }

    @Test fun independentHashesCanCompleteWhileAnotherSaveIsBlocked() {
        val saves = LocalResumeSaveRequests()
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val task = worker.submit<LocalResumeSaveRequests.Request> {
                saves.acquire("a") {
                    blocked.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
            }
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            val other = saves.acquire("b") {}
            saves.complete("b", true)
            assertTrue(other.awaitConfirmed(0))
            release.countDown()
            val first = task.get(2, TimeUnit.SECONDS)
            saves.complete("a", true)
            assertTrue(first.awaitConfirmed(0))
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }
    @Test fun shutdownCancelsAllUnresolvedWaiters() {
        val saves = LocalResumeSaveRequests()
        val one = saves.acquire("one") {}
        val two = saves.acquire("two") {}
        saves.cancelAll()
        assertFalse(one.awaitConfirmed(0))
        assertFalse(two.awaitConfirmed(0))
        val next = saves.acquire("one") {}
        assertFalse(next.awaitConfirmed(0))
        saves.complete("one", true)
        assertTrue(next.awaitConfirmed(0))
    }

}
