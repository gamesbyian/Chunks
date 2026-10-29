package io.github.gamesbyian.chunks.model

/**
 * Shared ordering rules for every Transfers list (phone, server and the
 * combined catalog): each sort key reverses, unknown facts stay last in BOTH
 * directions, and ties fall back to a fixed identity order that does not
 * flip with the direction toggle.
 */
internal object TransferSortOrdering {
    fun reversed(direction: UnifiedSortDirection): UnifiedSortDirection =
        if (direction == UnifiedSortDirection.ASCENDING) UnifiedSortDirection.DESCENDING
        else UnifiedSortDirection.ASCENDING

    /** Null means UNKNOWN and sorts after every known value regardless of [direction]. */
    fun <T : Comparable<T>> compareKnown(a: T?, b: T?, direction: UnifiedSortDirection): Int {
        if (a == null) return if (b == null) 0 else 1
        if (b == null) return -1
        val result = a.compareTo(b)
        return if (direction == UnifiedSortDirection.ASCENDING) result else -result
    }

    fun compareNames(a: String, b: String, direction: UnifiedSortDirection): Int {
        val result = String.CASE_INSENSITIVE_ORDER.compare(a, b)
        return if (direction == UnifiedSortDirection.ASCENDING) result else -result
    }

    /**
     * Plain-language direction for a sort key, so the toggle never reads as a
     * bare arrow. Text keys read A–Z/Z–A; measured keys say which end is first.
     */
    fun label(kind: Kind, direction: UnifiedSortDirection): String {
        val ascending = direction == UnifiedSortDirection.ASCENDING
        return when (kind) {
            Kind.NAME -> if (ascending) "A–Z" else "Z–A"
            Kind.SIZE -> if (ascending) "Smallest first" else "Largest first"
            Kind.PROGRESS -> if (ascending) "Least complete first" else "Most complete first"
            Kind.SPEED -> if (ascending) "Slowest first" else "Fastest first"
            Kind.PEERS -> if (ascending) "Fewest peers first" else "Most peers first"
            Kind.ADDED -> if (ascending) "Oldest first" else "Newest first"
        }
    }

    enum class Kind { NAME, SIZE, PROGRESS, SPEED, PEERS, ADDED }

    /** Text keys start A–Z; measured keys start with the largest value. */
    fun naturalDirection(kind: Kind): UnifiedSortDirection =
        if (kind == Kind.NAME) UnifiedSortDirection.ASCENDING else UnifiedSortDirection.DESCENDING
}
