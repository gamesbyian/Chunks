package io.github.gamesbyian.chunks

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LocalServicePauseBudgetTest {
    @Test fun everyTransferSharesOneDeadlineRatherThanGettingTwoMoreSeconds() {
        var now = 5_000_000_000L
        val budget = LocalServicePauseBudget(nowNanos = { now })
        assertEquals(2_000L, budget.nextReadbackTimeoutMs())
        now += 1_900_000_000L
        assertEquals(600L, budget.nextReadbackTimeoutMs())
        now += 600_000_000L
        assertEquals(0L, budget.nextReadbackTimeoutMs())
        now += 1_000_000_000L
        assertEquals(0L, budget.nextReadbackTimeoutMs())
    }

    @Test fun negativeClockDriftCannotEnlargePerTransferLimit() {
        var now = 10_000_000_000L
        val budget = LocalServicePauseBudget(nowNanos = { now })
        now -= 100_000_000L
        assertEquals(2_000L, budget.nextReadbackTimeoutMs())
    }

    @Test fun exactBoundaryAndSubMillisecondRemainingProduceNoAdditionalWait() {
        var now = 0L
        val budget = LocalServicePauseBudget(totalReadbackMs = 100L,
            perTransferReadbackMs = 80L, nowNanos = { now })
        assertEquals(80L, budget.nextReadbackTimeoutMs())
        now = 99_500_000L
        assertEquals(0L, budget.nextReadbackTimeoutMs())
        now = 100_000_000L
        assertEquals(0L, budget.nextReadbackTimeoutMs())
    }

    @Test fun excessiveBudgetsAreRefusedBeforeUse() {
        try { LocalServicePauseBudget(totalReadbackMs = 0L); fail() }
        catch (_: IllegalArgumentException) {}
        try { LocalServicePauseBudget(perTransferReadbackMs = 60_001L); fail() }
        catch (_: IllegalArgumentException) {}
    }
}
