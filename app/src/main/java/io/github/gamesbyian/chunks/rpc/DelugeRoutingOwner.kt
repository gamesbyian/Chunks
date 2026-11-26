package io.github.gamesbyian.chunks.rpc

import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext

/**
 * Process-wide owner of ONE Deluge Web UI's shared daemon slot, as far as Chunks' own callers go (#2072).
 *
 * Deluge's Web UI routes every `core.*` call through a single daemon connection and `web.connect`
 * selects which daemon it is. Two Chunks profiles on different daemons behind the same Web UI would
 * therefore rebind the slot underneath each other's bind/read/write brackets. This owner serializes
 * Chunks' OWN brackets (every [DelugeClient] operation and every [DelugeReadOnlyRpc.inspect]) per Web UI
 * endpoint, so one Chunks caller never runs between another Chunks caller's bind and post-check.
 *
 * What this is NOT: server-side atomicity. Another browser, script or app can still switch the slot at
 * any time. The existing refusals stay the safety net: a client never trusts a pre-call status, the
 * post-call status must still name its daemon, a write whose bracket broke is uncertain and never
 * replayed, and a slot that was moved by anyone but Chunks is refused, not repaired, within the known limit documented on [mayRebind].
 *
 * Reentrancy: client operations call other operations, so the lock is held by a coroutine-context
 * element rather than by a plain Mutex. A nested [bracket] on the same owner runs inline. The element
 * is deactivated when the outermost bracket ends, so a stray copy of the context can never bypass the
 * lock later. Brackets must not fan out into concurrent children that share the element; none do.
 *
 * Fairness and affinity: waiters are served FIFO, except that a waiter targeting the daemon the slot
 * was last left on may overtake up to [MAX_OVERTAKES] earlier waiters for other daemons, so a queue of
 * same-daemon work is not interleaved with needless slot switches. The head is always served after
 * that bound, so no waiter starves.
 */
internal class DelugeRoutingOwner {
    private class Waiter(val host: String) {
        val turn = CompletableDeferred<Unit>()
        var granted = false
    }

    private class Held(val owner: DelugeRoutingOwner, val parent: Held?) : AbstractCoroutineContextElement(Key) {
        @Volatile var active = true
        companion object Key : CoroutineContext.Key<Held>
    }

    private val state = Any()
    private var locked = false
    private val queue = ArrayDeque<Waiter>()
    private var overtakenHead = 0
    private var lastHost: String? = null

    /** The daemon this owner last left the slot on (guarded by the bracket lock). */
    private var lastOwnBind: String? = null

    private val acquisitionCount = AtomicLong()
    private val contendedCount = AtomicLong()
    private val overtakeCount = AtomicLong()

    /** Test and measurement observability only. */
    val acquisitions: Long get() = acquisitionCount.get()
    val contended: Long get() = contendedCount.get()
    val overtakes: Long get() = overtakeCount.get()
    /** Number of callers currently queued behind the holder (not counting the holder). */
    val queued: Int get() = synchronized(state) { queue.size }
    val isIdle: Boolean get() = synchronized(state) { !locked && queue.isEmpty() }

    suspend fun <T> bracket(host: String, block: suspend () -> T): T {
        // Walk the chain so a bracket on another endpoint nested between two of ours stays reentrant.
        val outer = coroutineContext[Held.Key]
        if (generateSequence(outer) { it.parent }.any { it.owner === this && it.active }) return block()
        acquire(host)
        val element = Held(this, outer)
        try {
            return withContext(element) { block() }
        } finally {
            element.active = false
            release(host)
        }
    }

    private suspend fun acquire(host: String) {
        val waiter: Waiter
        synchronized(state) {
            acquisitionCount.incrementAndGet()
            if (!locked) { locked = true; return }
            contendedCount.incrementAndGet()
            waiter = Waiter(host)
            queue.addLast(waiter)
        }
        try {
            waiter.turn.await()
        } catch (e: CancellationException) {
            // Cancelled while queued. If the turn was already handed to us, pass it on; otherwise
            // just leave the queue. Either way the lock stays usable for everyone else.
            var passOn = false
            synchronized(state) {
                if (waiter.granted) passOn = true else queue.remove(waiter)
            }
            if (passOn) release(null)
            throw e
        }
    }

    private fun release(host: String?) {
        val next: Waiter?
        synchronized(state) {
            if (host != null) lastHost = host
            next = pickNext()
            if (next == null) locked = false else next.granted = true
        }
        next?.turn?.complete(Unit)
    }

    private fun pickNext(): Waiter? {
        val head = queue.firstOrNull() ?: return null
        if (head.host != lastHost && overtakenHead < MAX_OVERTAKES) {
            val same = queue.firstOrNull { it.host == lastHost }
            if (same != null) {
                queue.remove(same)
                overtakenHead++
                overtakeCount.incrementAndGet()
                return same
            }
        }
        overtakenHead = 0
        return queue.removeFirst()
    }

    /**
     * Record that Chunks itself just executed `web.connect` to [host] and verified it. Never call this for
     * a slot that was merely observed on [host]: an outside actor may have put it there. Inside a bracket only.
     */
    fun noteBound(host: String) { lastOwnBind = host }

    /**
     * May the slot be rebound to [host] after finding it elsewhere? Only when the slot still sits on the
     * daemon this owner itself last left it on, i.e. a sibling Chunks bracket moved it. A slot that
     * is on any other daemon was moved by somebody else (or the last position was this host's own
     * and was then moved away): that is refused and never repaired. [boundStatus] reads the Web UI's
     * live status of the given host and reports whether it is Connected.
     *
     * KNOWN LIMIT (not closed): this cannot tell a sibling Chunks bracket from an outside actor's round
     * trip that ends back on the daemon Chunks last bound (last=B, outside B->C->B, then A rebinds). The
     * outcome is bounded, not prevented: the rebind only ever binds THIS client's own verified, registered
     * daemon, and the call is still bracketed by the post-call bound check, so a write goes to the
     * selected daemon or is reported uncertain, never to another daemon. What is lost is only the
     * refusal an outside round trip would otherwise have earned.
     */
    suspend fun mayRebind(host: String, boundStatus: suspend (String) -> Boolean): Boolean {
        val last = lastOwnBind ?: return false
        if (last == host) return false
        return boundStatus(last)
    }

    internal companion object {
        const val MAX_OVERTAKES = 8
        private val owners = HashMap<String, java.lang.ref.Reference<DelugeRoutingOwner>>()

        /** Test seam: lets a test clear a reference exactly as the garbage collector would. */
        internal var referenceFactory: (DelugeRoutingOwner) -> java.lang.ref.Reference<DelugeRoutingOwner> =
            { java.lang.ref.WeakReference(it) }

        /**
         * One owner per Web UI endpoint: scheme, host, effective port and path. Deliberately NOT keyed by
         * username or password: Deluge's Web UI authenticates by password only and its daemon slot is
         * process-global for the whole Web UI, so two profiles on one endpoint with different
         * credentials still fight over the same slot. Over-sharing this LOCAL lock is the safe direction.
         * The credential boundary stays where it lives: every client logs in and holds its own web
         * session; the owner stores and exposes no credential, cookie or secret.
         *
         * SURFACE, not closed: this is a spelling, not a server identity. `localhost` vs `127.0.0.1`, a DNS
         * alias or another name for the same Web UI get SEPARATE locks, which is the unsafe direction (the
         * pre-call and post-call bound checks remain the only protection between them). Repeated `/` in the
         * path are collapsed, over-sharing being the safe direction.
         *
         * Entries are weak: an owner stays registered while any client or in-flight bracket references
         * it, so an endpoint can never be split into two locks for live clients, and dead entries are
         * purged on insertion, so the registry is bounded by the owners actually in use.
         */
        fun forEndpoint(endpoint: URI): DelugeRoutingOwner {
            val scheme = endpoint.scheme.lowercase()
            val port = if (endpoint.port >= 0) endpoint.port else if (scheme == "https") 443 else 80
            val key = "$scheme://${endpoint.host.lowercase()}:$port${endpoint.rawPath.replace(Regex("/{2,}"), "/")}"
            synchronized(owners) {
                owners[key]?.get()?.let { return it }
                owners.values.removeAll { it.get() == null }
                return DelugeRoutingOwner().also { owners[key] = referenceFactory(it) }
            }
        }

        fun registrySize() = synchronized(owners) { owners.values.count { it.get() != null } }

        fun clearForTest() = synchronized(owners) {
            owners.clear()
            referenceFactory = { java.lang.ref.WeakReference(it) }
        }
    }
}
