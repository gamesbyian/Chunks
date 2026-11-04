package io.github.gamesbyian.chunks.model

/**
 * A bounded chronological record of observed completion, measured in millionths.
 * Bands are proportional *increments*, not libtorrent pieces or files.
 *
 * [pendingPpm] is already part of [totalPpm]. It extends the last painted band
 * until it is large enough to be a separate visible increment.
 */
internal data class ProgressChunkHistory(
    val totalPpm: Int? = null,
    val bands: List<Int> = emptyList(),
    val pendingPpm: Int = 0
) {
    init {
        require(totalPpm == null || totalPpm in 0..1_000_000)
        require(bands.size <= MAX_BANDS && bands.all { it > 0 })
        require(pendingPpm >= 0 && pendingPpm <= 1_000_000)
        require(bands.sum() + pendingPpm == (totalPpm ?: 0))
    }

    /** Does not invent a band for an unchanged sample. */
    fun observe(completionPpm: Int, minimumVisiblePpm: Int): ProgressChunkHistory {
        require(completionPpm in 0..1_000_000)
        require(minimumVisiblePpm in 1..1_000_000)
        val previous = totalPpm ?: return ProgressChunkHistory(
            completionPpm, if (completionPpm > 0) listOf(completionPpm) else emptyList()
        )
        if (completionPpm == previous) return this
        if (completionPpm < previous) {
            // An authoritative recheck trims the rightmost (newest) content.
            var remove = previous - completionPpm
            val retained = bands.toMutableList()
            val lessPending = minOf(remove, pendingPpm)
            remove -= lessPending
            var remainingPending = pendingPpm - lessPending
            while (remove > 0 && retained.isNotEmpty()) {
                val last = retained.removeAt(retained.lastIndex)
                val taken = minOf(last, remove)
                if (last > taken) retained.add(last - taken)
                remove -= taken
            }
            if (completionPpm == 0) remainingPending = 0
            return ProgressChunkHistory(completionPpm, retained, remainingPending)
        }

        val accumulated = pendingPpm + completionPpm - previous
        if (accumulated < minimumVisiblePpm && completionPpm != 1_000_000) {
            return ProgressChunkHistory(completionPpm, bands, accumulated)
        }
        val next = bands.toMutableList()
        if (completionPpm == 1_000_000 && accumulated < minimumVisiblePpm && next.isNotEmpty()) {
            // Finish precisely without creating a nearly invisible last sliver.
            next[next.lastIndex] += accumulated
        } else if (accumulated > 0) {
            next += accumulated
        }
        while (next.size > MAX_BANDS) {
            // Recency wins: never merge newest samples while older pairs remain.
            next[1] += next[0]
            next.removeAt(0)
        }
        return ProgressChunkHistory(completionPpm, next)
    }

    /** Widths always sum to the currently observed completion, even when buffering tiny deltas. */
    fun paintedBands(): List<Int> {
        if (pendingPpm == 0) return bands
        if (bands.isEmpty()) return listOf(pendingPpm)
        return bands.dropLast(1) + (bands.last() + pendingPpm)
    }

    fun encode(): String = listOf(
        "v1", (totalPpm ?: -1).toString(), pendingPpm.toString(), bands.joinToString(",")
    ).joinToString("|")

    companion object {
        const val MAX_BANDS = 20
        const val RECENT_BANDS = 8

        fun decode(value: String): ProgressChunkHistory? {
            val fields = value.split("|")
            if (fields.size != 4 || fields[0] != "v1") return null
            val total = fields[1].toIntOrNull() ?: return null
            val pending = fields[2].toIntOrNull() ?: return null
            val list = if (fields[3].isEmpty()) emptyList() else {
                fields[3].split(",").map { it.toIntOrNull() ?: return null }
            }
            return runCatching {
                ProgressChunkHistory(total.takeUnless { it == -1 }, list, pending)
            }.getOrNull()
        }
    }
}
