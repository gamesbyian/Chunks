package io.github.gamesbyian.chunks

/**
 * Stable read-only search into an already observed per-transfer file inventory.
 * Returned indices always refer to ORIGINAL provider positions, so file
 * mutation adapters never receive a filtered-list position as authority.
 */
internal fun matchingFileIndices(paths: List<String>, rawQuery: String): List<Int> {
    val query = rawQuery.trim()
    if (query.isEmpty()) return paths.indices.toList()
    return paths.indices.filter { paths[it].contains(query, ignoreCase = true) }
}
