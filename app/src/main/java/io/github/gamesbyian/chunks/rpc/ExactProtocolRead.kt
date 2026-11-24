package io.github.gamesbyian.chunks.rpc

import java.io.IOException

/**
 * A successful exact-filter reply can be empty or contain the requested
 * identity exactly once. Any other reply is inconclusive, never absence.
 * Validate the entire reply before selecting its row.
 */
internal fun <T> exactProtocolRow(rows: List<T>, expectedHash: String, hashOf: (T) -> String): T? {
    if (rows.isEmpty()) return null
    if (rows.size != 1 || !hashOf(rows[0]).equals(expectedHash, ignoreCase = true)) {
        throw IOException("Unexpected or ambiguous exact client identity")
    }
    return rows[0]
}
