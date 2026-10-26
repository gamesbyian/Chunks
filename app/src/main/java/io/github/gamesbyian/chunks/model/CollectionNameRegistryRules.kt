package io.github.gamesbyian.chunks.model

import java.util.Locale

/**
 * Explicit semantic names, independent of loaded transfers and physical Places.
 * Adding a Collection name does not add members or contact any server.
 */
object CollectionNameRegistryRules {
    const val MAX_NAMES = 256

    fun add(existing: Collection<String>, proposed: String): List<String>? {
        val name = CollectionMembershipRules.cleanName(proposed) ?: return null
        val names = ordered(existing)
        if (names.any { it.equals(name, ignoreCase = true) }) return names
        if (names.size >= MAX_NAMES) return null
        return ordered(names + name)
    }

    fun ordered(names: Collection<String>): List<String> =
        names.mapNotNull(CollectionMembershipRules::cleanName)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
}
