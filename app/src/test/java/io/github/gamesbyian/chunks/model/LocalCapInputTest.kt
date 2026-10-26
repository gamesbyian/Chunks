package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class LocalCapInputTest {
    @Test fun parseAndFormatValidGiBLimitsWithoutFloatingPoint() {
        val gib = 1024L * 1024 * 1024
        assertEquals(gib, LocalCapInput.parseGiB("1"))
        assertEquals(gib + gib / 2, LocalCapInput.parseGiB("1.5"))
        assertEquals("1.5", LocalCapInput.formatGiB(gib + gib / 2))
        assertEquals(0L, LocalCapInput.parseGiB("0.001")!! / gib)
        assertEquals(2 * gib, LocalCapInput.parseGiB(" 2.000 "))
    }

    @Test fun badOrOverflowingUserInputNeverBecomesAnActiveLimit() {
        for (value in listOf("", "0", "0.000", "-1", "NaN", "Infinity",
            "1e9", "1,000", "1.2345", "999999999999", "1 GiB")) {
            assertNull("Unexpected input accepted: $value", LocalCapInput.parseGiB(value))
        }
    }

    @Test fun completeObservationWarnsButPartialAndMissingSurveysAreUnknown() {
        val complete = LocalFolderInventory(850, 2, true)
        val cap = 1000L
        assertEquals(StorageWarning.EIGHTY_FIVE, LocalCapInput.assessment(cap, complete, null).warning)
        assertEquals(150L, LocalCapInput.assessment(cap, complete, null).remainingWithinCapBytes)
        for (folder in listOf(null, LocalFolderInventory(950, 2, false),
            LocalFolderInventory(null, 0, false))) {
            val result = LocalCapInput.assessment(cap, folder, 5_000)
            assertEquals(StorageWarning.UNKNOWN, result.warning)
            assertNull(result.remainingWithinCapBytes)
            assertNull(result.estimatedHeadroomBytes)
            assertFalse(result.capEnforced)
        }
    }

    @Test fun noCapIsAdvisoryAndNeverEnforced() {
        val result = LocalCapInput.assessment(null, LocalFolderInventory(1_000, 3, true), null)
        assertEquals(StorageWarning.NONE, result.warning)
        assertFalse(result.capEnforced)
    }
}
