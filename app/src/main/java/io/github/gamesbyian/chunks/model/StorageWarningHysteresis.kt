package io.github.gamesbyian.chunks.model

/**
 * Pure, state-driven alert deduplication for an OPTIONAL device-volume quota.
 * Never generates a notification, runs a timer, writes files or pauses torrents.
 *
 * Persist the latch by stable volume identity in the eventual settings layer.
 * Unknown/stale measurements preserve it to avoid repeated alerts on reconnect.
 */
data class StorageWarningDecision(
    val nextLatch: StorageWarning,
    val newNotice: StorageWarning?
)

object StorageWarningHysteresis {
    fun transition(budget: StorageBudget, previousLatch: StorageWarning): StorageWarningDecision {
        val cap = budget.capBytes
        if (cap == null) return StorageWarningDecision(StorageWarning.NONE, null)
        val used = budget.allocatedBytes
            ?: return StorageWarningDecision(previousLatch, null)

        val current = StorageBudgetAdvisor.assess(budget).warning
        val oldRank = rank(previousLatch)
        val currentRank = rank(current)
        if (currentRank > oldRank) {
            return StorageWarningDecision(current, current)
        }
        if (currentRank < oldRank) {
            // A 5 percentage-point gap below the previously warned band prevents
            // warning storms on oscillating allocations or external writes.
            val lowerThreshold = when (previousLatch) {
                StorageWarning.SEVENTY -> 65
                StorageWarning.EIGHTY_FIVE -> 80
                StorageWarning.NINETY_FIVE -> 90
                StorageWarning.OVER_CAP -> 95
                else -> 0
            }
            if (used < percentageCeiling(cap, lowerThreshold)) {
                return StorageWarningDecision(current, null)
            }
        }
        return StorageWarningDecision(previousLatch, null)
    }

    private fun rank(warning: StorageWarning): Int = when (warning) {
        StorageWarning.NONE, StorageWarning.UNKNOWN -> 0
        StorageWarning.SEVENTY -> 1
        StorageWarning.EIGHTY_FIVE -> 2
        StorageWarning.NINETY_FIVE -> 3
        StorageWarning.OVER_CAP -> 4
    }

    // ceil(cap * p / 100), without overflowing cap*p.
    private fun percentageCeiling(cap: Long, p: Int): Long =
        (cap / 100) * p + ((cap % 100) * p + 99) / 100
}
