package io.github.gamesbyian.chunks.seeding

import io.github.gamesbyian.chunks.seeding.SeedingEnforcement.Decision
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class SeedingEnforcementTest {
    private fun seen(up: Long, down: Long, seconds: Long = 0, seeding: Boolean = true) =
        SeedingObservation(up, down, seconds, seeding)
    private val ratio1 = SeedingPolicy(ratioGoal = BigDecimal("1.00"))

    @Test fun ratioStopsOnlyOnceReachedAndNeverWithoutADenominator() {
        assertEquals(Decision.KeepSeeding, SeedingEnforcement.decide(ratio1, seen(999, 1000)))
        assertTrue(SeedingEnforcement.decide(ratio1, seen(1000, 1000)) is Decision.Stop)
        val copy = SeedingEnforcement.decide(ratio1, seen(5_000_000, 0))
        assertTrue("a seeded copy has no ratio", copy is Decision.CannotJudge)
    }

    @Test fun foreverAndNotSeedingNeverStop() {
        assertEquals(Decision.KeepSeeding, SeedingEnforcement.decide(SeedingPolicy(), seen(10, 1)))
        assertEquals(Decision.KeepSeeding, SeedingEnforcement.decide(ratio1, seen(10, 1, seeding = false)))
    }

    @Test fun minimumTimeComesFirstAndAnyOrAllCombineAsWritten() {
        val min = ratio1.copy(minSeedSeconds = 3600)
        assertEquals(Decision.KeepSeeding, SeedingEnforcement.decide(min, seen(2000, 1000, 600)))
        assertTrue(SeedingEnforcement.decide(min, seen(2000, 1000, 3600)) is Decision.Stop)
        val any = SeedingPolicy(ratioGoal = BigDecimal("2"), maxSeedSeconds = 86_400)
        assertTrue("time alone is enough", SeedingEnforcement.decide(any, seen(10, 1000, 86_400)) is Decision.Stop)
        val all = any.copy(combine = SeedingPolicy.Combine.ALL)
        assertEquals(Decision.KeepSeeding, SeedingEnforcement.decide(all, seen(10, 1000, 86_400)))
        val stop = SeedingEnforcement.decide(all, seen(2000, 1000, 86_400)) as Decision.Stop
        assertEquals("Ratio 2.00 reached the goal of 2.00 and seeded for the maximum time.", stop.reason)
        // With nothing downloaded, ALL cannot be judged; ANY still honours the time limit.
        assertTrue(SeedingEnforcement.decide(all, seen(10, 0, 86_400)) is Decision.CannotJudge)
        assertTrue(SeedingEnforcement.decide(any, seen(10, 0, 86_400)) is Decision.Stop)
    }
}
