package io.github.gamesbyian.chunks.model

/**
 * Computes a planning total without signed-Long wraparound. Logical byte
 * lengths are not physical disk allocation and never authorize deletion.
 *
 * Null means at least one invalid size or an unrepresentable total; callers
 * must refuse publication rather than present a negative or small estimate.
 */
object LibraryCopyByteTotals {
    fun checked(files: List<LibraryCopyFile>): Long? {
        var total = 0L
        for (file in files) {
            val bytes = file.sizeBytes
            if (bytes < 0L || bytes > Long.MAX_VALUE - total) return null
            total += bytes
        }
        return total
    }
}
