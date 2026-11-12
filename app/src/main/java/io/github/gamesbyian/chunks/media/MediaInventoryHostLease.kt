package io.github.gamesbyian.chunks.media

/**
 * App-level continuity for the ONE media inventory session (#1453). The canonical Transfers
 * host still builds, replaces and authorizes the inventory. When the person navigates to
 * Connections, Options or Add, the host *detaches* instead of closing, so an already
 * authorized scan is not cancelled by navigation alone. This is never a second executor.
 *
 * A detached session keeps only the last complete LIVE inventory the host supplied, and it
 * is closed (revoking every in-flight callback) as soon as any authority witness changes:
 * privacy lifecycle/policy epoch, the authenticated account, the saved-profile set, or the
 * bounded [maxDetachedMillis] window. Re-attaching hands the same session back to the
 * host, which immediately replaces its inventory with a fresh observation.
 */
class MediaInventoryHostLease(
    private val maxDetachedMillis: Long = 10 * 60_000L
) : AutoCloseable {
    private val lock = Any()
    private var session: MediaInventoryEnrichmentSession? = null
    private var attached = false
    private var detachedAt: Long? = null
    private var witness: String? = null

    /** Reuse the detached session when it is still lease-valid, otherwise open a new one. */
    fun attach(open: () -> MediaInventoryEnrichmentSession): MediaInventoryEnrichmentSession =
        synchronized(lock) {
            val current = session?.takeIf { !it.isClosed }
            val result = current ?: open().also { session = it }
            attached = true
            detachedAt = null
            result
        }

    /** The host leaves composition: keep this exact session alive, bounded by [revokeUnless]. */
    fun detach(owned: MediaInventoryEnrichmentSession, nowMillis: Long) = synchronized(lock) {
        if (session !== owned) { owned.close(); return@synchronized }
        attached = false
        detachedAt = nowMillis
    }

    /**
     * Called by the app owner whenever the authority witness may have changed, and on a
     * periodic tick. While attached the host owns revocation through its own snapshot; while
     * detached, any witness change or an expired window closes the session.
     */
    fun revokeUnless(currentWitness: String, nowMillis: Long) {
        val toClose = synchronized(lock) {
            val previous = witness
            witness = currentWitness
            val since = detachedAt
            val active = session
            if (attached || active == null || since == null) return
            if (previous == currentWitness && nowMillis - since < maxDetachedMillis) return
            session = null
            detachedAt = null
            active
        }
        toClose.close()
    }

    override fun close() {
        val toClose = synchronized(lock) {
            val active = session
            session = null
            attached = false
            detachedAt = null
            active
        }
        toClose?.close()
    }
}
