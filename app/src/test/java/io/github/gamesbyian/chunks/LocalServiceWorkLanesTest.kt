package io.github.gamesbyian.chunks

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalServiceWorkLanesTest {
    @Test fun stopControlDoesNotWaitBehindAHungObservation() {
        val lanes = LocalServiceWorkLanes(
            Executors.newSingleThreadExecutor(),
            Executors.newSingleThreadExecutor()
        )
        val pollEntered = CountDownLatch(1)
        val unblockPoll = CountDownLatch(1)
        val controlRan = CountDownLatch(1)
        try {
            assertTrue(lanes.submitPoll {
                pollEntered.countDown()
                unblockPoll.await(5, TimeUnit.SECONDS)
            })
            assertTrue(pollEntered.await(2, TimeUnit.SECONDS))
            repeat(10_000) {
                assertFalse(lanes.submitPoll { error("Overlapping poll was enqueued") })
            }
            assertTrue(lanes.submitControl { controlRan.countDown() })
            // The observation is still blocked. Control must get its own worker.
            assertTrue(controlRan.await(2, TimeUnit.SECONDS))
            assertTrue(unblockPoll.count == 1L)
        } finally {
            unblockPoll.countDown()
            lanes.shutdown()
        }
    }

    @Test fun aFailedPollReleasesAdmissionForNextPoll() {
        val lanes = LocalServiceWorkLanes(
            Executors.newSingleThreadExecutor(),
            Executors.newSingleThreadExecutor()
        )
        val failed = CountDownLatch(1)
        val succeeded = CountDownLatch(1)
        try {
            assertTrue(lanes.submitPoll {
                try { throw IllegalStateException("fixture failure") }
                finally { failed.countDown() }
            })
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            // Poll admission is released from finally by the worker, which
            // can lag the failure callback very briefly.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var accepted = false
            while (!accepted && System.nanoTime() < deadline) {
                accepted = lanes.submitPoll { succeeded.countDown() }
                if (!accepted) Thread.sleep(1)
            }
            assertTrue(accepted)
            assertTrue(succeeded.await(2, TimeUnit.SECONDS))
        } finally {
            lanes.shutdown()
        }
    }

    @Test fun shutdownRejectsNewCommandsAndDoesNotLeakPollAdmission() {
        val lanes = LocalServiceWorkLanes(
            Executors.newSingleThreadExecutor(),
            Executors.newSingleThreadExecutor()
        )
        lanes.shutdown()
        assertFalse(lanes.submitPoll { error("Cannot run after shutdown") })
        assertFalse(lanes.submitControl { error("Cannot run after shutdown") })
        assertFalse(lanes.submitPoll { error("Admission must reset") })
    }
}
