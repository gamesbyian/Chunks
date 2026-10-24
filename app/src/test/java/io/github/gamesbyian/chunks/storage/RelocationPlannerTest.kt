package io.github.gamesbyian.chunks.storage

import org.junit.Assert.*
import org.junit.Test

class RelocationPlannerTest {
    private fun facts() = MoveFacts(
        sameServer = true,
        sourceExists = true,
        sourceWithinApprovedRoot = true,
        targetWithinApprovedRoot = true,
        targetExists = false,
        sourceChangedSinceInventory = false,
        sharedWithOtherTorrents = false,
        writesCanBeQuiescedForHandoff = true,
        engineCanRebind = true,
        durableJournalAvailable = true,
        sameVolume = true,
        atomicRenameAvailable = true,
        stagedCopyAvailable = true,
        bytesToCopy = 1024,
        freeBytesAtTarget = 2048
    )
    @Test fun choosesSameVolumeAtomicRenameWhenProven() {
        assertEquals(MoveStrategy.SAME_VOLUME_RENAME,
            (RelocationPlanner.plan(facts()) as MoveDecision.Proposed).strategy)
    }
    @Test fun choosesStagedCopyOnlyWhenSpaceAndCapabilityProven() {
        val choice = RelocationPlanner.plan(facts().copy(sameVolume = false))
        assertEquals(MoveStrategy.VERIFIED_STAGED_COPY,
            (choice as MoveDecision.Proposed).strategy)
    }
    @Test fun refusesStaleInventory() {
        assertTrue(RelocationPlanner.plan(facts().copy(sourceChangedSinceInventory = true))
            is MoveDecision.Refused)
    }
    @Test fun refusesCollisionAndRootEscape() {
        assertTrue(RelocationPlanner.plan(facts().copy(targetExists = true)) is MoveDecision.Refused)
        assertTrue(RelocationPlanner.plan(facts().copy(targetWithinApprovedRoot = false))
            is MoveDecision.Refused)
    }
    @Test fun refusesWithoutJournalOrEngineHandoff() {
        assertTrue(RelocationPlanner.plan(facts().copy(durableJournalAvailable = false))
            is MoveDecision.Refused)
        assertTrue(RelocationPlanner.plan(facts().copy(engineCanRebind = false))
            is MoveDecision.Refused)
    }
    @Test fun refusesSharedFileOrInsufficientSpace() {
        assertTrue(RelocationPlanner.plan(facts().copy(sharedWithOtherTorrents = true))
            is MoveDecision.Refused)
        assertTrue(RelocationPlanner.plan(facts().copy(
            sameVolume = false, freeBytesAtTarget = 500)) is MoveDecision.Refused)
    }
    @Test fun cannotTreatUnknownFreeSpaceAsSafe() {
        assertTrue(RelocationPlanner.plan(facts().copy(
            sameVolume = false, freeBytesAtTarget = null)) is MoveDecision.Refused)
    }
}
