package io.github.gamesbyian.chunks

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Installed Android scheduler test; does not deliberately hang the native library. */
@RunWith(AndroidJUnit4::class)
class LocalScreenObservationPumpInstrumentedTest {
    @Test fun blockedObservationDoesNotAccumulateStatusRequestsAndRecovers() {
        val worker = Executors.newSingleThreadExecutor()
        val pump = LocalScreenObservationPump<List<String>>(worker)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            assertTrue(pump.tryObserve {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                listOf("preserved transfer")
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(100) { assertFalse(pump.tryObserve { emptyList() }) }
            assertTrue(pump.inFlightMillis(System.nanoTime() + TimeUnit.SECONDS.toNanos(16))!! >= 15_000)
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var result: LocalScreenPollOutcome<List<String>>? = null
            while (result == null && System.nanoTime() < deadline) {
                result = pump.takeCompleted()
                if (result == null) Thread.sleep(10)
            }
            assertEquals(LocalScreenPollOutcome.Fresh(listOf("preserved transfer")), result)
        } finally {
            release.countDown()
            pump.shutdown()
            worker.shutdownNow()
        }
    }
}
