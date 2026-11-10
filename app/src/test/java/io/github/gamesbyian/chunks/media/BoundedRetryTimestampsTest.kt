package io.github.gamesbyian.chunks.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedRetryTimestampsTest {
    @Test fun leastRecentlyUsedThrottleIsEvictedWithoutChangingOtherExactKeys() {
        val stamps = BoundedRetryTimestamps<String>(3)
        stamps["rtorrent:one|same-hash"] = 10
        stamps["rtorrent:two|same-hash"] = 20
        stamps["phone|same-hash"] = 30
        assertEquals(10L, stamps["rtorrent:one|same-hash"])
        stamps["rtorrent:three|different"] = 40
        assertNull(stamps["rtorrent:two|same-hash"])
        assertEquals(10L, stamps["rtorrent:one|same-hash"])
        assertEquals(30L, stamps["phone|same-hash"])
        assertEquals(40L, stamps["rtorrent:three|different"])
        assertEquals(3, stamps.size())
    }

    @Test fun unboundedHistoricalInventoryChurnCannotGrowRetryHintsForever() {
        val stamps = BoundedRetryTimestamps<String>()
        for (i in 0 until 50_000) {
            val exactKey = "rtorrent:profile-${i % 7}|" + i.toString(16).padStart(40, '0')
            stamps[exactKey] = i.toLong()
        }
        assertEquals(10_000, stamps.size())
        assertNull(stamps["rtorrent:profile-0|" + 0.toString(16).padStart(40, '0')])
        val last = 49_999
        assertEquals(last.toLong(),
            stamps["rtorrent:profile-${last % 7}|" + last.toString(16).padStart(40, '0')])
    }

    @Test fun providerRevisionHintsAreBoundedAndKeepProfileHashIdentityDistinct() {
        fun key(profile: String, hash: String) = MediaTransferKey.of(
            io.github.gamesbyian.chunks.model.UnifiedTransferKey.of(
                io.github.gamesbyian.chunks.model.EnvironmentId(profile), hash))
        val sameHash = "a".repeat(40)
        val serverA = key("rtorrent:server-a", sameHash)
        val serverB = key("rtorrent:server-b", sameHash)
        val phone = key("phone", sameHash)
        val hints = BoundedRetryTimestamps<MediaTransferKey>(3)
        hints[serverA] = 1L
        hints[serverB] = 2L
        hints[phone] = 3L
        assertEquals(1L, hints[serverA]) // Touch A; exact account B remains independent.
        hints[key("rtorrent:other", "b".repeat(40))] = 4L
        assertNull("Evict only the least-recently-seen exact profile/hash hint", hints[serverB])
        assertEquals(1L, hints[serverA])
        assertEquals(3L, hints[phone])
        assertEquals(3, hints.size())
        // On eviction, no durable metadata or opt-out is touched. An evicted
        // hint simply behaves as a process-first provider revision attempt.
    }

    @Test fun invalidBoundsAreRejectedBeforeUse() {
        assertThrows(IllegalArgumentException::class.java) { BoundedRetryTimestamps<String>(0) }
    }
}
