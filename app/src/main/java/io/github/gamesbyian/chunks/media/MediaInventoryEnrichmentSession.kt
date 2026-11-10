package io.github.gamesbyian.chunks.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A lifecycle-owned, revocable full-inventory witness. Not a second lookup engine or
 * persistent job catalog: all matching, provider calls, rate limits and persistence
 * still belong exclusively to [TransferMediaController.enrichInventory].
 *
 * An owner must replace the inventory on a fresh observation and close this lease
 * when its current privacy/account/source access ends. For each exact transfer key,
 * [isStillAuthorized] must dynamically confirm current profile identity, unlocked
 * privacy, freshness, and presence in the CURRENT inventory. Merely opening a
 * remembered server or caching an old list is never enough.
 */
class MediaInventoryEnrichmentSession internal constructor(
    private val ownerScope: CoroutineScope,
    private val enrich: suspend (Collection<Pair<MediaTransferKey, String>>,
        (MediaTransferKey) -> Boolean) -> Unit,
    private val onClosed: (MediaInventoryEnrichmentSession) -> Unit = {}
) : AutoCloseable {
    private class Snapshot(
        val rows: List<Pair<MediaTransferKey, String>>,
        val keys: Set<MediaTransferKey>,
        val isStillAuthorized: (MediaTransferKey) -> Boolean
    ) {
        @Volatile var revoked = false
    }

    private class Run(val snapshot: Snapshot) {
        @Volatile var revoked = false
        fun allowed(key: MediaTransferKey): Boolean =
            !revoked && !snapshot.revoked && key in snapshot.keys &&
                // A transient privacy-gate or account-error callback must fail closed.
                runCatching { snapshot.isStillAuthorized(key) }.getOrDefault(false) &&
                !revoked && !snapshot.revoked
    }

    private val lock = Any()
    // Cancellation of a blocking HTTP request is advisory until that request exits.
    // Never start a replacement pass while the controller still owns its per-key
    // pendingAutomatic latch; otherwise the only authorized retry gets dropped.
    private val executionMutex = Mutex()
    private var closed = false
    private var snapshot: Snapshot? = null
    private var active: Run? = null
    private var activeJob: Job? = null

    /**
     * Replace, never append to, the last verified inventory. A replaced snapshot
     * revokes even synchronous in-flight HTTP callbacks that ignore coroutine
     * interruption. The observer may supply an empty list when offline/locked.
     */
    fun replace(entries: Collection<Pair<MediaTransferKey, String>>,
                isStillAuthorized: (MediaTransferKey) -> Boolean) {
        val rows = entries.distinctBy { it.first }
        synchronized(lock) {
            if (closed) return
            snapshot?.revoked = true
            snapshot = Snapshot(rows, rows.mapTo(HashSet()) { it.first }, isStillAuthorized)
            scheduleLocked()
        }
    }

    /**
     * Consent, enabled providers, key changes or reconnect capability may unblock
     * offscreen rows even when the displayed inventory has not changed. Cache
     * receipts and exact-key automation exclusions remain enforced by the controller.
     */
    fun refresh() = synchronized(lock) {
        if (!closed && snapshot != null) scheduleLocked()
    }

    private fun scheduleLocked() {
        active?.revoked = true
        activeJob?.cancel()
        val next = snapshot ?: return
        if (next.rows.isEmpty()) {
            active = null
            activeJob = null
            return
        }
        val run = Run(next)
        active = run
        activeJob = ownerScope.launch {
            executionMutex.withLock {
                // A queued run may have been replaced while waiting for an older
                // uninterruptible provider request. Only the newest active witness
                // may now enter the sole controller, preserving exact-job isolation.
                if (!run.revoked && !next.revoked) enrich(next.rows, run::allowed)
            }
        }
    }

    override fun close() {
        val notify = synchronized(lock) {
            if (closed) false
            else {
                closed = true
                snapshot?.revoked = true
                active?.revoked = true
                activeJob?.cancel()
                activeJob = null
                active = null
                snapshot = null
                true
            }
        }
        if (notify) onClosed(this)
    }
}
