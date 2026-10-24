package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class StorageBudgetAdvisorTest {
    @Test fun boundaryWarningsAreDeterministic() {
        val cases = listOf(
            0L to StorageWarning.NONE,
            699L to StorageWarning.NONE,
            700L to StorageWarning.SEVENTY,
            849L to StorageWarning.SEVENTY,
            850L to StorageWarning.EIGHTY_FIVE,
            950L to StorageWarning.NINETY_FIVE,
            1000L to StorageWarning.OVER_CAP,
            1200L to StorageWarning.OVER_CAP
        )
        cases.forEach { (used, expected) ->
            assertEquals(expected, StorageBudgetAdvisor.assess(
                StorageBudget("internal", 1000, used, 500)).warning)
        }
    }

    @Test fun unknownIsNeverPretendedToBeFreeSpace() {
        val result = StorageBudgetAdvisor.assess(StorageBudget("root", 1000, null, 300))
        assertEquals(StorageWarning.UNKNOWN, result.warning)
        assertNull(result.remainingWithinCapBytes)
        assertNull(result.estimatedHeadroomBytes)
        assertFalse(result.capEnforced)
    }

    @Test fun noUserCapMeansAdvisoryFreeBytesOnly() {
        val result = StorageBudgetAdvisor.assess(StorageBudget("root", null, 100, 90))
        assertEquals(StorageWarning.NONE, result.warning)
        assertNull(result.remainingWithinCapBytes)
        assertEquals(90L, result.estimatedHeadroomBytes)
    }

    @Test fun safeWithHugeValuesAndOSFreeSpace() {
        val result = StorageBudgetAdvisor.assess(StorageBudget("root", Long.MAX_VALUE, Long.MAX_VALUE - 1, 1))
        assertEquals(StorageWarning.NINETY_FIVE, result.warning)
        assertEquals(1L, result.estimatedHeadroomBytes)
    }

    @Test fun inputsMustBeValid() {
        assertThrows(IllegalArgumentException::class.java) { StorageBudget("", 100, 10, 10) }
        assertThrows(IllegalArgumentException::class.java) { StorageBudget("root", 0, 10, 10) }
        assertThrows(IllegalArgumentException::class.java) { StorageBudget("root", 10, -1, 10) }
    }
}
