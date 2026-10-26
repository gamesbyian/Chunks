package io.github.gamesbyian.chunks.model

import java.util.Locale

/**
 * A Collection is a human-assigned semantic relationship, not a filesystem
 * directory, torrent category or server-side setting. Suggestions never write
 * memberships. All writes require a user-selected transfer and collection.
 */
object CollectionMembershipRules {
    const val MAX_PER_TRANSFER = 32
    const val MAX_CODE_POINTS = 80
    private const val MAX_UTF8_BYTES = 256

    fun cleanName(input: String): String? {
        val name = input.trim()
        if (name.isEmpty() || name.codePointCount(0, name.length) > MAX_CODE_POINTS ||
            name.toByteArray(Charsets.UTF_8).size > MAX_UTF8_BYTES ||
            name.any { ch ->
                ch.isISOControl() || ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069'
            }) return null
        return name
    }

    fun add(existing: Set<String>, proposed: String): Set<String>? {
        val name = cleanName(proposed) ?: return null
        val valid = existing.mapNotNull(::cleanName).toSet()
        if (valid.any { it.equals(name, ignoreCase = true) }) return valid
        if (valid.size >= MAX_PER_TRANSFER) return null
        return valid + name
    }

    fun remove(existing: Set<String>, name: String): Set<String> =
        existing.mapNotNull(::cleanName).filterNot { it.equals(name, ignoreCase = true) }.toSet()

    fun ordered(existing: Set<String>): List<String> =
        existing.mapNotNull(::cleanName).distinctBy { it.lowercase(Locale.ROOT) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
}
