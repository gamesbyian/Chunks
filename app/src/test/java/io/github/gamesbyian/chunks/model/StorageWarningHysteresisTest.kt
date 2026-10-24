package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class StorageWarningHysteresisTest {
    private fun at(used: Long?, cap: Long? = 1000L) =
        StorageBudget("device-volume", cap, used, 500)

    @Test fun warnsOnlyWhenCrossingNewThreshold() {
        var latch = StorageWarning.NONE
        val expected = listOf(
            699L to null, 700L to StorageWarning.SEVENTY,
            701L to null, 849L to null,
            850L to StorageWarning.EIGHTY_FIVE, 900L to null,
            950L to StorageWarning.NINETY_FIVE,
            999L to null, 1000L to StorageWarning.OVER_CAP,
            1100L to null
        )
        expected.forEach { (used, alert) ->
            val next = StorageWarningHysteresis.transition(at(used), latch)
            assertEquals("At used=$used", alert, next.newNotice)
            latch = next.nextLatch
        }
        assertEquals(StorageWarning.OVER_CAP, latch)
    }

    @Test fun fivePercentHysteresisPreventsOscillatingWarningSpam() {
        var d = StorageWarningHysteresis.transition(at(950), StorageWarning.NONE)
        assertEquals(StorageWarning.NINETY_FIVE, d.newNotice)
        for (used in listOf(949L, 951L, 910L, 900L)) {
            d = StorageWarningHysteresis.transition(at(used), d.nextLatch)
            assertNull(d.newNotice)
            assertEquals(StorageWarning.NINETY_FIVE, d.nextLatch)
        }
        d = StorageWarningHysteresis.transition(at(899), d.nextLatch)
        assertEquals(StorageWarning.EIGHTY_FIVE, d.nextLatch)
        assertNull(d.newNotice)
        d = StorageWarningHysteresis.transition(at(950), d.nextLatch)
        assertEquals(StorageWarning.NINETY_FIVE, d.newNotice)
    }

    @Test fun unknownMeasurementPreservesPriorWarningState() {
        val d = StorageWarningHysteresis.transition(at(null), StorageWarning.EIGHTY_FIVE)
        assertNull(d.newNotice)
        assertEquals(StorageWarning.EIGHTY_FIVE, d.nextLatch)
        val restored = StorageWarningHysteresis.transition(at(860), d.nextLatch)
        assertNull(restored.newNotice)
    }

    @Test fun disablingVoluntaryCapResetsWithoutAlert() {
        val disabled = StorageWarningHysteresis.transition(
            at(800, cap = null), StorageWarning.OVER_CAP)
        assertEquals(StorageWarning.NONE, disabled.nextLatch)
        assertNull(disabled.newNotice)
    }

    @Test fun tinyAndHugeQuotasDoNotOverflow() {
        val tiny = StorageWarningHysteresis.transition(
            StorageBudget("tiny", 3L, 3L, 0), StorageWarning.NONE)
        assertEquals(StorageWarning.OVER_CAP, tiny.newNotice)
        val huge = StorageWarningHysteresis.transition(
            StorageBudget("huge", Long.MAX_VALUE, Long.MAX_VALUE, 0),
            StorageWarning.NONE)
        assertEquals(StorageWarning.OVER_CAP, huge.newNotice)
    }

    @Test fun capDecreaseCanRaiseWarningButNeverDeletesAnything() {
        val result = StorageWarningHysteresis.transition(
            StorageBudget("device", 500, 600, 20), StorageWarning.SEVENTY)
        assertEquals(StorageWarning.OVER_CAP, result.newNotice)
        assertEquals(StorageWarning.OVER_CAP, result.nextLatch)
    }
}
