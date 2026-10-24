package io.github.gamesbyian.chunks.storage

/**
 * Server-observed, short-lived preflight facts. Root/symlink/capability facts
 * must come from trusted server-side validation, NOT from client path parsing.
 * This is only an offline planner; it performs no mutation or authorization.
 */
data class MoveFacts(
    val sameServer: Boolean,
    val sourceExists: Boolean,
    val sourceWithinApprovedRoot: Boolean,
    val targetWithinApprovedRoot: Boolean,
    val targetExists: Boolean,
    val sourceChangedSinceInventory: Boolean,
    val sharedWithOtherTorrents: Boolean,
    val writesCanBeQuiescedForHandoff: Boolean,
    val engineCanRebind: Boolean,
    val durableJournalAvailable: Boolean,
    val sameVolume: Boolean,
    val atomicRenameAvailable: Boolean,
    val stagedCopyAvailable: Boolean,
    val bytesToCopy: Long,
    val freeBytesAtTarget: Long?
)

enum class MoveStrategy { SAME_VOLUME_RENAME, VERIFIED_STAGED_COPY }

sealed interface MoveDecision {
    data class Proposed(
        val strategy: MoveStrategy,
        val requiresFinalRevalidation: Boolean = true,
        val requiresCoordinatedIoHandoff: Boolean = true
    ) : MoveDecision
    data class Refused(val reason: String) : MoveDecision
}

/**
 * Conservative decision ordering: no fall-through to a risky copy on missing
 * facts. Physical execution revalidates every fact immediately before commit.
 */
object RelocationPlanner {
    fun plan(facts: MoveFacts): MoveDecision {
        if (!facts.sameServer) return MoveDecision.Refused("Cross-server moves are not supported")
        if (!facts.sourceWithinApprovedRoot || !facts.targetWithinApprovedRoot)
            return MoveDecision.Refused("Source or destination is outside allowed roots")
        if (!facts.sourceExists) return MoveDecision.Refused("Source is missing")
        if (facts.sourceChangedSinceInventory)
            return MoveDecision.Refused("Source changed; refresh the inventory")
        if (facts.targetExists) return MoveDecision.Refused("Destination already exists")
        if (facts.sharedWithOtherTorrents)
            return MoveDecision.Refused("Data is shared with another torrent")
        if (!facts.durableJournalAvailable)
            return MoveDecision.Refused("No crash-recoverable server operation journal")
        if (!facts.engineCanRebind || !facts.writesCanBeQuiescedForHandoff)
            return MoveDecision.Refused("Safe engine I/O handoff is unavailable")
        if (facts.sameVolume && facts.atomicRenameAvailable)
            return MoveDecision.Proposed(MoveStrategy.SAME_VOLUME_RENAME)
        if (!facts.stagedCopyAvailable)
            return MoveDecision.Refused("No verified staging-copy capability")
        if (facts.bytesToCopy < 0 || facts.freeBytesAtTarget == null ||
            facts.freeBytesAtTarget < facts.bytesToCopy)
            return MoveDecision.Refused("Insufficient verified destination space")
        return MoveDecision.Proposed(MoveStrategy.VERIFIED_STAGED_COPY)
    }
}
