package io.github.gamesbyian.chunks.model

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LocalStoppedCheckpointBudgetTest {
    private val first = "a".repeat(40)
    private val second = "b".repeat(40)

    @Test fun oneMonotonicBudgetAcrossIndependentConfirmedStops() {
        var now = 1_000_000_000L
        val waits = ArrayList<Pair<String, Long>>()
        val budget = LocalStoppedCheckpointBudget(totalTimeoutMs = 7L, nowNanos = { now })
        assertTrue(budget.confirm(first) { hash, millis ->
            waits += hash to millis
            now += 4_000_000L
            true
        })
        assertFalse(budget.confirm(second) { hash, millis ->
            waits += hash to millis
            now += 3_000_000L
            false
        })
        assertFalse(budget.confirm("c".repeat(40)) { _, _ ->
            fail("Exhausted budget may never start another native save")
            true
        })
        assertEquals(listOf(first to 7L, second to 3L), waits)
        assertTrue(LocalStoppedCheckpointBudget.warning(2).contains("2 stopped local transfer"))
        assertEquals("", LocalStoppedCheckpointBudget.warning(0))
    }

    @Test fun exceptionOrZeroBudgetNeverManufacturesDurableEvidence() {
        val budget = LocalStoppedCheckpointBudget(totalTimeoutMs = 10L, nowNanos = { 0L })
        assertFalse(budget.confirm(first) { _, _ -> throw IllegalStateException("disk") })
        val noTime = LocalStoppedCheckpointBudget(totalTimeoutMs = 0L)
        assertFalse(noTime.confirm(second) { _, _ ->
            fail("No callback when no time remains"); true
        })
    }

    @Test fun cancellationIsNotDisguisedAsMissingPersistence() {
        val budget = LocalStoppedCheckpointBudget(totalTimeoutMs = 10L, nowNanos = { 0L })
        try {
            budget.confirm(first) { _, _ -> throw CancellationException("screen changed") }
            fail("Must propagate cancellation")
        } catch (_: CancellationException) {}
    }

    @Test fun fractionalMillisecondDoesNotDispatchZeroTimeoutSave() {
        var now = 0L
        val budget = LocalStoppedCheckpointBudget(totalTimeoutMs = 1L, nowNanos = { now })
        now = 999_999L
        assertFalse(budget.confirm(first) { _, _ ->
            fail("No zero-millisecond persisted checkpoint")
            true
        })
    }

    @Test fun oneFailedCheckpointDoesNotTurnAConfirmedStopIntoAnUnknownMutation() {
        val stopped = ArrayList<String>()
        val targets = listOf(
            LocalBatchLifecycle.Target(first, stopped = false),
            LocalBatchLifecycle.Target(second, stopped = false))
        val report = LocalBatchLifecycle.run(targets, start = false) { hash, start ->
            assertFalse(start)
            stopped += hash
            io.github.gamesbyian.chunks.engine.LocalCommandOutcome.VERIFIED
        }
        val budget = LocalStoppedCheckpointBudget(totalTimeoutMs = 10L, nowNanos = { 0L })
        val unconfirmed = stopped.count { hash ->
            !budget.confirm(hash) { it, _ -> it != first }
        }
        assertEquals(2, report.verified)
        assertEquals(listOf(first, second), stopped)
        assertEquals(1, unconfirmed)
        assertTrue(LocalStoppedCheckpointBudget.warning(unconfirmed).contains("could not be confirmed"))
    }
}
