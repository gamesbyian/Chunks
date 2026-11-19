package io.github.gamesbyian.chunks.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ORDER of a stopped file check, proven deterministically.
 *
 * [Libtorrent] models exactly the documented semantics the engine relies on (libtorrent
 * RC_2_0 `torrent.cpp`):
 * - `force_recheck` posts its disk job at once, even while paused; the job finishes
 *   whenever the disk thread delivers it ([complete]). With no payload on disk it is
 *   delivered without needing the torrent to run; with payload it makes progress only
 *   while unpaused.
 * - `stop_when_ready(true)` while the torrent is ALREADY in a downloading/finished/
 *   seeding state pauses it immediately and clears the flag; while checking it stays
 *   armed and pauses when the torrent leaves checking.
 * No threads, timers or sleeps: the completion event is placed explicitly before the
 * k-th native call (or after the last), for every k.
 */
class StoppedCheckTest {
    private enum class State { CHECKING, DOWNLOADING }

    private class Libtorrent(
        private val payloadOnDisk: Boolean,
        startsAlreadyChecking: Boolean = false,
        private val completeBeforeCall: Int,
        private val failAtCall: Int = -1
    ) : StoppedCheckNative {
        var paused = true
        var armed = false
        var state = if (startsAlreadyChecking) State.CHECKING else State.DOWNLOADING
        private var diskJobPending = startsAlreadyChecking
        private var diskJobDelivered = false
        private var calls = 0

        private fun handler(block: () -> Unit) {
            if (calls == completeBeforeCall) complete()
            val index = calls++
            if (index == failAtCall) throw IllegalStateException("native failure at call $index")
            block()
        }

        /** The disk thread delivers the check result (at most once). */
        fun complete() {
            if (!diskJobPending || diskJobDelivered) return
            // Hashing real payload needs the torrent to run; an empty check does not.
            if (payloadOnDisk && paused) return
            diskJobDelivered = true
            diskJobPending = false
            val wasChecking = state == State.CHECKING
            state = State.DOWNLOADING // downloading/finished/seeding: "a downloading state"
            if (wasChecking && armed) { paused = true; armed = false } // set_state trigger
        }

        /** After the command sequence: let any outstanding disk job finish. */
        fun drain() { complete() }

        override fun forceRecheck() = handler {
            if (state == State.CHECKING) return@handler
            state = State.CHECKING
            diskJobPending = true
        }
        override fun resume() = handler { paused = false }
        override fun armStopWhenReady() = handler {
            armed = true
            if (state != State.CHECKING) { paused = true; armed = false } // immediate trigger
        }
        override fun disarmStopWhenReady() { armed = false }
        override fun pause() { paused = true }

        /** What a correct stopped check must always end as. */
        val endedStopped: Boolean get() = paused && !armed && state != State.CHECKING
    }

    private fun run(payload: Boolean, completeBeforeCall: Int, queueCheck: Boolean = true): Libtorrent {
        val job = Libtorrent(payload, startsAlreadyChecking = !queueCheck, completeBeforeCall = completeBeforeCall)
        assertTrue(StoppedCheck.begin(job, queueCheck))
        job.drain()
        return job
    }

    @Test fun everyPlacementOfTheCheckCompletionEndsStoppedWhenThereIsNoPayload() {
        // 3 native calls: completion before call 0, 1, 2, or after all of them (3).
        for (slot in 0..3) {
            val job = run(payload = false, completeBeforeCall = slot)
            assertTrue("no payload, completion before call $slot: paused=${job.paused} armed=${job.armed} " +
                "state=${job.state}", job.endedStopped)
        }
    }

    @Test fun everyPlacementOfTheCheckCompletionEndsStoppedWithPayloadOnDisk() {
        for (slot in 0..3) {
            val job = run(payload = true, completeBeforeCall = slot)
            assertTrue("payload, completion before call $slot: paused=${job.paused} armed=${job.armed} " +
                "state=${job.state}", job.endedStopped)
        }
    }

    @Test fun anAlreadyQueuedCheckIsLetRunAndStillEndsStopped() {
        // queueCheck=false: no second forceRecheck; only resume + arm (2 calls).
        for (slot in 0..2) for (payload in listOf(false, true)) {
            val job = run(payload = payload, completeBeforeCall = slot, queueCheck = false)
            assertTrue("queued check, payload=$payload, completion before call $slot", job.endedStopped)
        }
    }

    @Test fun theOldOrderLeavesTheJobRunningWhenTheCheckCompletesBeforeTheFlagIsSet() {
        // NEGATIVE CONTROL: arm BEFORE resume, as the engine used to. With no payload the
        // check finishes between forceRecheck and arm: arming then pauses immediately and
        // clears the flag, and the following resume() starts the stopped job again.
        val job = Libtorrent(payloadOnDisk = false, completeBeforeCall = 1)
        job.forceRecheck(); job.armStopWhenReady(); job.resume(); job.drain()
        assertFalse("the model reproduces the production defect: the job is running", job.paused)
        assertFalse(job.armed)
        assertFalse(job.endedStopped)
    }

    @Test fun aNativeFailureAtAnyStepPutsTheJobBackToStopped() {
        for (failAt in 0..2) {
            val job = Libtorrent(payloadOnDisk = false, completeBeforeCall = 99, failAtCall = failAt)
            assertFalse("failure at call $failAt is reported", StoppedCheck.begin(job, queueCheck = true))
            assertTrue("failure at call $failAt: paused=${job.paused}", job.paused)
            assertFalse("failure at call $failAt must not leave the stop armed", job.armed)
        }
    }

    @Test fun restopStillPausesWhenDisarmingFails() {
        var paused = false
        val job = object : StoppedCheckNative {
            override fun forceRecheck() = Unit
            override fun resume() = Unit
            override fun armStopWhenReady() = Unit
            override fun disarmStopWhenReady() = throw IllegalStateException("flag write failed")
            override fun pause() { paused = true }
        }
        StoppedCheck.restop(job)
        assertTrue("every step is attempted even if an earlier one fails", paused)
    }

    @Test fun onlyALostStopIsRepairedAfterTheDeadlineNeverASlowLegitimateCheck() {
        // (paused, armed, checking)
        assertTrue("running, unarmed, not checking = the stop was lost",
            StoppedCheck.stopWasLost(paused = false, armed = false, checking = false))
        assertFalse("stopped", StoppedCheck.stopWasLost(paused = true, armed = false, checking = false))
        assertFalse("a slow check that will stop itself", StoppedCheck.stopWasLost(paused = false, armed = true, checking = true))
        assertFalse("armed, between states", StoppedCheck.stopWasLost(paused = false, armed = true, checking = false))
        assertFalse("checking without the flag is a different case, left alone",
            StoppedCheck.stopWasLost(paused = false, armed = false, checking = true))
        assertEquals(false, StoppedCheck.stopWasLost(paused = true, armed = true, checking = true))
    }
}
