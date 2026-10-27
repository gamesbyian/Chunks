package io.github.gamesbyian.chunks.model

import org.junit.Assert.*
import org.junit.Test

class LocalAddStoragePreflightTest {
    @Test fun flagsWorstCaseAdvisoryLimitWithoutEnforcement() {
        val decision = LocalAddStoragePlanner.assess(
            torrentBytes = 600, folder = LocalFolderInventory(700, 2, true),
            capBytes = 1_000, availableVolumeBytes = 2_000)
        assertEquals(1_300L, decision.projectedLogicalMaximum)
        assertEquals(true, decision.projectedCapExceeded)
        assertEquals(false, decision.additionalBytesExceedFree)
    }

    @Test fun freeSpaceWarningUsesActualSelectedVolumeObservation() {
        val plan = LocalAddStoragePlanner.assess(
            torrentBytes = 2_000, folder = LocalFolderInventory(0, 0, true),
            capBytes = null, availableVolumeBytes = 1_999)
        assertEquals(true, plan.additionalBytesExceedFree)
        assertNull(plan.projectedCapExceeded)
    }

    @Test fun incompleteFolderSurveyNeverClaimsWithinOrExceedingAdvisoryLimit() {
        val plan = LocalAddStoragePlanner.assess(
            torrentBytes = 9, folder = LocalFolderInventory(5, 1, false),
            capBytes = 1_000, availableVolumeBytes = null)
        assertNull(plan.projectedLogicalMaximum)
        assertNull(plan.projectedCapExceeded)
        assertNull(plan.additionalBytesExceedFree)
    }

    @Test fun overflowFailsClosedRatherThanInventingSmallProjection() {
        val plan = LocalAddStoragePlanner.assess(
            torrentBytes = 300L, folder = LocalFolderInventory(Long.MAX_VALUE - 50, 1, true),
            capBytes = 2_000L, availableVolumeBytes = 9_999L)
        assertNull(plan.projectedLogicalMaximum)
        assertNull(plan.projectedCapExceeded)
    }

    @Test fun exactThresholdIsNotOverAndZeroSizeDoesNotNeedMoreBytes() {
        val plan = LocalAddStoragePlanner.assess(
            torrentBytes = 0, folder = LocalFolderInventory(100, 1, true),
            capBytes = 100, availableVolumeBytes = 0)
        assertEquals(100L, plan.projectedLogicalMaximum)
        assertEquals(false, plan.projectedCapExceeded)
        assertEquals(false, plan.additionalBytesExceedFree)
    }

    @Test fun unknownFreeAndInvalidCapacityAreNotFalseSafeClaims() {
        val plan = LocalAddStoragePlanner.assess(
            torrentBytes = 600, folder = LocalFolderInventory(null, 0, false),
            capBytes = -50, availableVolumeBytes = -1)
        assertNull(plan.folderCapBytes)
        assertNull(plan.availableVolumeBytes)
        assertNull(plan.projectedCapExceeded)
        assertNull(plan.additionalBytesExceedFree)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeTorrentPayloadCannotBePlanned() {
        LocalAddStoragePlanner.assess(-1, null, null, null)
    }
}
