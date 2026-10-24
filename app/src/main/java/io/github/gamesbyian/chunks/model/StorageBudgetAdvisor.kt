package io.github.gamesbyian.chunks.model

/**
 * Advisory quota computation only. No filesystem writes, pause commands or deletion rights.
 * Null measurements are unknown and must never be interpreted as zero.
 */
enum class StorageWarning { NONE, SEVENTY, EIGHTY_FIVE, NINETY_FIVE, OVER_CAP, UNKNOWN }

data class StorageBudget(
    val volumeId: String,
    val capBytes: Long?,
    val allocatedBytes: Long?,
    val freeBytes: Long?
) {
    init {
        require(volumeId.isNotBlank())
        require(capBytes == null || capBytes > 0)
        require(allocatedBytes == null || allocatedBytes >= 0)
        require(freeBytes == null || freeBytes >= 0)
    }
}

data class StorageAssessment(
    val warning: StorageWarning,
    val remainingWithinCapBytes: Long?,
    val estimatedHeadroomBytes: Long?,
    val capEnforced: Boolean = false
)

object StorageBudgetAdvisor {
    fun assess(budget: StorageBudget): StorageAssessment {
        val cap = budget.capBytes
        val used = budget.allocatedBytes
        val free = budget.freeBytes
        val remaining = if (cap != null && used != null) (cap - used).coerceAtLeast(0) else null
        val headroom = when {
            remaining != null && free != null -> minOf(remaining, free)
            cap == null -> free
            else -> null
        }
        val warning = when {
            cap == null -> StorageWarning.NONE
            used == null -> StorageWarning.UNKNOWN
            used >= cap -> StorageWarning.OVER_CAP
            used >= threshold(cap, 95) -> StorageWarning.NINETY_FIVE
            used >= threshold(cap, 85) -> StorageWarning.EIGHTY_FIVE
            used >= threshold(cap, 70) -> StorageWarning.SEVENTY
            else -> StorageWarning.NONE
        }
        return StorageAssessment(warning, remaining, headroom)
    }

    // Exact comparison without overflowing used * 100 or cap * percentage.
    private fun threshold(cap: Long, percentage: Long): Long =
        (cap / 100) * percentage + ((cap % 100) * percentage + 99) / 100
}
