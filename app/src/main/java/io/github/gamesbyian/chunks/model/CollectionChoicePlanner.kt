package io.github.gamesbyian.chunks.model

import java.util.Locale

/**
 * Explicit, user-tappable suggestions from already approved Collection names.
 * No classifier can silently assign a transfer and no physical Place is implied.
 */
object CollectionChoicePlanner {
    const val MAX_QUICK_CHOICES = 12

    fun unassigned(existingNames: List<String>, membership: Collection<String>): List<String> {
        val memberKeys = membership.map { it.lowercase(Locale.ROOT) }.toSet()
        return existingNames.asSequence().mapNotNull(CollectionMembershipRules::cleanName)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .filterNot { it.lowercase(Locale.ROOT) in memberKeys }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .take(MAX_QUICK_CHOICES).toList()
    }
}
