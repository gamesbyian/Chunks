package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM policy contract; Android's actual FGS state requires API35 instrumentation. */
class LocalServiceStartGateTest {
    @Test fun initialAndOrdinaryRepeatedStartsStillPromote() {
        assertEquals(LocalServiceStartGate.Decision.PROMOTE,
            LocalServiceStartGate.decide(finished = false, timedOut = false))
        // Before onTimeout, a repeated explicit user start must still service
        // Android's ordinary startForegroundService delivery.
        assertEquals(LocalServiceStartGate.Decision.PROMOTE,
            LocalServiceStartGate.decide(finished = false, timedOut = false))
    }

    @Test fun timeoutPendingNeverRepromotesOrPrematurelyEndsTheWatchdog() {
        assertEquals(LocalServiceStartGate.Decision.IGNORE_WHILE_TIMING_OUT,
            LocalServiceStartGate.decide(finished = false, timedOut = true))
        // Replayed Stop-all / another UI start during the grace interval
        // cannot reset Android's exhausted foreground service lifetime.
        repeat(20) {
            assertEquals(LocalServiceStartGate.Decision.IGNORE_WHILE_TIMING_OUT,
                LocalServiceStartGate.decide(finished = false, timedOut = true))
        }
    }

    @Test fun completedInstanceAlwaysStopsIncludingAfterTimeout() {
        assertEquals(LocalServiceStartGate.Decision.RETIRE_FINISHED,
            LocalServiceStartGate.decide(finished = true, timedOut = false))
        assertEquals(LocalServiceStartGate.Decision.RETIRE_FINISHED,
            LocalServiceStartGate.decide(finished = true, timedOut = true))
    }

    @Test fun androidTimeoutTransitionNeverReturnsToPromotion() {
        val timeline = listOf(
            false to false,
            false to true,
            false to true,
            true to true
        )
        assertEquals(listOf(
            LocalServiceStartGate.Decision.PROMOTE,
            LocalServiceStartGate.Decision.IGNORE_WHILE_TIMING_OUT,
            LocalServiceStartGate.Decision.IGNORE_WHILE_TIMING_OUT,
            LocalServiceStartGate.Decision.RETIRE_FINISHED
        ), timeline.map { (finished, timedOut) ->
            LocalServiceStartGate.decide(finished, timedOut)
        })
    }
}
