package io.github.gamesbyian.chunks

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalScreenObservationPumpTest {
    private fun <T> awaitResult(pump: LocalScreenObservationPump<T>): LocalScreenPollOutcome<T> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            pump.takeCompleted()?.let { return it }
            Thread.sleep(2)
        }
        error("Timed out awaiting observation")
    }

    @Test fun aHungReadCannotQueueMoreNativeCallsOrBlockAgeCheck() {
        val worker = Executors.newSingleThreadExecutor()
        val pump = LocalScreenObservationPump<Int>(worker)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            assertTrue(pump.tryObserve {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                7
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(10_000) {
                assertFalse(pump.tryObserve { error("No overlapping native reads") })
            }
            assertNull(pump.takeCompleted())
            assertTrue(pump.inFlightMillis(System.nanoTime() + TimeUnit.SECONDS.toNanos(20))!! >= 19_999L)
            release.countDown()
            assertEquals(LocalScreenPollOutcome.Fresh(7), awaitResult(pump))
            // The worker's finally releases admission after publishing the result.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var accepted = false
            while (!accepted && System.nanoTime() < deadline) {
                accepted = pump.tryObserve { 8 }
                if (!accepted) Thread.sleep(2)
            }
            assertTrue(accepted)
            assertEquals(LocalScreenPollOutcome.Fresh(8), awaitResult(pump))
        } finally {
            release.countDown()
            pump.shutdown()
            worker.shutdownNow()
        }
    }

    @Test fun thrownNativeReadIsUnavailableAndNextSuccessfulReadCanRecover() {
        val worker = Executors.newSingleThreadExecutor()
        val pump = LocalScreenObservationPump<List<String>>(worker)
        try {
            assertTrue(pump.tryObserve { throw IllegalStateException("native status unavailable") })
            assertEquals(LocalScreenPollOutcome.Unavailable, awaitResult(pump))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var accepted = false
            while (!accepted && System.nanoTime() < deadline) {
                accepted = pump.tryObserve { emptyList() }
                if (!accepted) Thread.sleep(2)
            }
            assertTrue(accepted)
            assertEquals(LocalScreenPollOutcome.Fresh(emptyList<String>()), awaitResult(pump))
        } finally {
            pump.shutdown()
            worker.shutdownNow()
        }
    }

    @Test fun shutdownRejectsNewReadsWithoutLeavingAdmissionStuck() {
        val worker = Executors.newSingleThreadExecutor()
        val pump = LocalScreenObservationPump<Int>(worker)
        pump.shutdown()
        assertFalse(pump.tryObserve { error("Must not execute after shutdown") })
        assertNull(pump.inFlightMillis())
    }
}
