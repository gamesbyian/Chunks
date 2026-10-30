package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.LocalServiceTimeoutNotice.PauseSummary
import org.junit.Assert.*
import org.junit.Test

class LocalServiceTimeoutStopTest {
    private class Fixture {
        var pending: Runnable? = null
        var scheduledMs: Long? = null
        val results = ArrayList<PauseSummary>()
        val gate = LocalServiceTimeoutStop(
            postDelayed = { callback, delay -> pending = callback; scheduledMs = delay },
            removeCallbacks = { callback -> if (pending === callback) pending = null },
            onFinished = results::add
        )
    }

    @Test fun noWorkerResultEndsWithUnknownAndCannotBeUpgradedByLateReadback() {
        val fixture = Fixture()
        assertTrue(fixture.gate.begin())
        assertEquals(4_000L, fixture.scheduledMs)
        val deadline = requireNotNull(fixture.pending)
        deadline.run()
        assertEquals(listOf(PauseSummary(null, 0)), fixture.results)
        assertNull(fixture.pending)

        fixture.gate.complete(PauseSummary(3, 3))
        deadline.run()
        assertEquals("The unknown timeout result must not be replaced", 1, fixture.results.size)
        assertFalse("Another timeout cannot extend the deadline", fixture.gate.begin())
    }

    @Test fun confirmedResultBeforeDeadlineCancelsWatchdogAndFinishesOnce() {
        val fixture = Fixture()
        assertTrue(fixture.gate.begin())
        val staleDeadline = requireNotNull(fixture.pending)
        fixture.gate.complete(PauseSummary(2, 2))
        assertNull(fixture.pending)
        staleDeadline.run()
        fixture.gate.complete(PauseSummary(null, 0))
        assertEquals(listOf(PauseSummary(2, 2)), fixture.results)
    }

    @Test fun repeatedTimeoutDoesNotPostAnotherDeadline() {
        val fixture = Fixture()
        fixture.gate.begin()
        val original = fixture.pending
        assertFalse(fixture.gate.begin())
        assertSame(original, fixture.pending)
    }

    @Test fun destructionInvalidatesBothDeadlineAndLateWorker() {
        val fixture = Fixture()
        fixture.gate.begin()
        val staleDeadline = requireNotNull(fixture.pending)
        fixture.gate.abandon()
        assertNull(fixture.pending)
        staleDeadline.run()
        fixture.gate.complete(PauseSummary(1, 1))
        assertTrue(fixture.results.isEmpty())
    }
}
