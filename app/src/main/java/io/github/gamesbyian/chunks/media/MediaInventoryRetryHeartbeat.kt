package io.github.gamesbyian.chunks.media

/**
 * A sparse, foreground-only source-list recheck. The owner supplies Android's
 * monotonic elapsed realtime from its EXISTING inventory clock effect. This is
 * not a timer, service, alarm, WorkManager job or independent provider executor.
 *
 * The first observation never creates a duplicate lookup. The sole controller
 * applies actual per-provider/negative-cache TTL, network/power policy, consent,
 * and exact-job exclusions whenever this heartbeat is due. A 30-minute bound
 * is deliberately longer than a temporary failure cache (10m), and far less
 * frequent than the host's source refresh (10s).
 */
class MediaInventoryRetryHeartbeat(
    private val intervalMs: Long = 30L * 60_000
) {
    init { require(intervalMs > 0L) }

    private var lastRefreshAt: Long? = null

    @Synchronized fun shouldRefresh(nowElapsedMs: Long): Boolean {
        // elapsedRealtime should be nonnegative and monotonic. A reset/clock
        // anomaly is an anchor change, never a reason to flood providers.
        if (nowElapsedMs < 0L) return false
        val previous = lastRefreshAt
        if (previous == null || nowElapsedMs < previous) {
            lastRefreshAt = nowElapsedMs
            return false
        }
        if (nowElapsedMs - previous < intervalMs) return false
        lastRefreshAt = nowElapsedMs
        return true
    }
}
