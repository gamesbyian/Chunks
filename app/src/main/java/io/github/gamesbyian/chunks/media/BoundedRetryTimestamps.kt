package io.github.gamesbyian.chunks.media

/**
 * An in-memory throttle for transient media retries, bounded independently of
 * how many exact transfer keys a long-lived remote client ever observes.
 *
 * LRU eviction can only remove a throttle hint. The persistent assignment,
 * provider negative cache, rate limits, consent and exact-job authorization
 * remain the authority for any eventual network request.
 */
internal class BoundedRetryTimestamps<K>(private val maxEntries: Int = 10_000) {
    init { require(maxEntries > 0) { "Retry bound must be positive" } }

    private val entries = object : java.util.LinkedHashMap<K, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Long>): Boolean =
            size > maxEntries
    }

    @Synchronized operator fun get(key: K): Long? = entries[key]

    @Synchronized operator fun set(key: K, atMs: Long) {
        entries[key] = atMs
    }

    @Synchronized fun size(): Int = entries.size
}
