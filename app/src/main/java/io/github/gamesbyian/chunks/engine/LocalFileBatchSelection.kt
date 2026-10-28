package io.github.gamesbyian.chunks.engine

/**
 * Preflight for a batch of at most one visible 40-file page. A reviewed file
 * must still have the same index, canonical path, total bytes and selection
 * when the native engine re-reads it. Downloaded bytes may grow concurrently.
 *
 * Returning null means the entire reviewed set is invalid/stale and NO native
 * write may begin. An empty Plan means nothing needs changing.
 */
internal object LocalFileBatchPlanner {
    const val MAX_BATCH = 40

    data class Plan(val indicesToChange: List<Int>, val requestedCount: Int)

    fun prepare(
        current: List<LocalTransferFile>,
        reviewed: List<LocalTransferFile>,
        want: Boolean
    ): Plan? {
        if (reviewed.isEmpty() || reviewed.size > MAX_BATCH) return null
        if (reviewed.any { it.index < 0 || it.path.isBlank() || it.sizeBytes < 0L } ||
            reviewed.map { it.index }.toSet().size != reviewed.size) return null
        val live = current.associateBy { it.index }
        if (live.size != current.size) return null
        val changes = ArrayList<Int>()
        for (item in reviewed) {
            val actual = live[item.index] ?: return null
            if (actual.path != item.path ||
                actual.sizeBytes != item.sizeBytes ||
                actual.wanted != item.wanted ||
                actual.priority != item.priority) return null
            if (actual.wanted != want) changes += item.index
        }
        return Plan(changes, reviewed.size)
    }
}

internal enum class LocalFileBatchOutcome {
    VERIFIED, UNCHANGED, STALE_REVIEW, NOT_FOUND, UNCERTAIN
}
