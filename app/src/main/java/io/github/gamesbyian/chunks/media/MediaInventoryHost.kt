package io.github.gamesbyian.chunks.media

/**
 * The one activity-lifetime owner of the full-inventory media witness.
 *
 * Before this host existed the [MediaInventoryEnrichmentSession] was opened and closed by the
 * composed Transfers screen, so navigating to Details, Options, Add or Connections cancelled
 * every offscreen lookup ([#1453]). The host outlives any one screen: the canonical Transfers
 * source keeps feeding it fresh authorized inventories, and the last verified inventory keeps
 * being worked on while Transfers is unmounted.
 *
 * It is not a second lookup engine, poller or executor. All matching, provider calls, rate
 * limits, exclusions and persistence still belong to the single controller behind the session.
 *
 * Authorization is re-verified per key against the LATEST fed snapshot at every commit, exactly
 * as the screen-owned session did. A privacy lifecycle/policy generation change observed while
 * no fresh inventory has arrived revokes the whole retained inventory until the next feed:
 * reopening the same account later cannot resurrect pre-lock authority.
 *
 * [#1453]: https://github.com/gamesbyian/Chunks/issues/1453
 */
class MediaInventoryHost internal constructor(
    private val session: MediaInventoryEnrichmentSession
) : AutoCloseable {
    private val lock = Any()
    @Volatile private var latest: AuthorizedMediaInventorySnapshot? = null
    private var currentEpoch: String? = null
    private var closed = false

    /** The inventory currently authorized for background work; null when revoked or never fed. */
    val retained: AuthorizedMediaInventorySnapshot? get() = latest

    /**
     * Replace the retained inventory with a fresh, complete, authorized observation. A snapshot
     * built under an older privacy generation than the one already observed here is refused:
     * it is a stale read that arrived after a lock/unlock or policy change.
     */
    fun feed(snapshot: AuthorizedMediaInventorySnapshot) {
        synchronized(lock) {
            if (closed) return
            val epoch = currentEpoch
            if (epoch != null && epoch != snapshot.privacyEpoch) return
            currentEpoch = snapshot.privacyEpoch
            latest = snapshot
            session.replace(snapshot.entries) { key ->
                // Never accept the historical lookup's cached authorization: compare against
                // the current privacy/profile scope, fresh source and exact job membership.
                val current = latest ?: return@replace false
                snapshot.stillAuthorizes(key, current)
            }
        }
    }

    /**
     * A new privacy lifecycle/policy generation. Old in-flight provider callbacks are revoked
     * immediately, even though the same account/title may be unlocked again moments later; the
     * next [feed] built under this generation resumes work.
     */
    fun privacyEpoch(epoch: String) {
        require(epoch.isNotBlank()) { "Privacy generation witness must be explicit" }
        synchronized(lock) {
            if (closed || currentEpoch == epoch) return
            currentEpoch = epoch
            if (latest != null) {
                latest = null
                session.replace(emptyList()) { false }
            }
        }
    }

    /** Routine heartbeat or connectivity transition: the controller rechecks every gate itself. */
    fun refresh() = session.refresh()

    /** Consent, provider or credential transitions revoke old in-flight work immediately. */
    fun refreshRevoking() = session.refreshRevoking()

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            latest = null
        }
        session.close()
    }
}
