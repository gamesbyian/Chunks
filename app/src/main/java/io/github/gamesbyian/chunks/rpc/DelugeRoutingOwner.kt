package io.github.gamesbyian.chunks.rpc

import java.net.URI
import java.security.MessageDigest
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
 * replayed, and a slot that was moved by anyone but Chunks is refused, not repaired ([mayRebind]).
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
     */
    suspend fun mayRebind(host: String, boundStatus: suspend (String) -> Boolean): Boolean {
        val last = lastOwnBind ?: return false
        if (last == host) return false
        return boundStatus(last)
    }

    internal companion object {
        const val MAX_OVERTAKES = 8
        private const val MAX_OWNERS = 256

        private val owners = object : LinkedHashMap<String, DelugeRoutingOwner>(16, 0.75f, true) {}

        /**
         * One owner per Web UI endpoint and credential: scheme, host, effective port and path, plus a
         * one-way digest of the web password. Different credentials or hosts never share an owner (the
         * conservative reading of "tenant"); the digest is never logged or exposed.
         */
        fun forEndpoint(endpoint: URI, credentials: RemoteCredentials): DelugeRoutingOwner {
            val scheme = endpoint.scheme.lowercase()
            val port = if (endpoint.port >= 0) endpoint.port else if (scheme == "https") 443 else 80
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(("chunks-deluge-route\u0000" + credentials.username + "\u0000" + credentials.password)
                    .toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it) }
            val key = "$scheme://${endpoint.host.lowercase()}:$port${endpoint.rawPath}#$digest"
            synchronized(owners) {
                owners[key]?.let { return it }
                if (owners.size >= MAX_OWNERS) {
                    val idle = owners.entries.firstOrNull { it.value.isIdle }
                    if (idle != null) owners.remove(idle.key)
                }
                return DelugeRoutingOwner().also { owners[key] = it }
            }
        }

        fun registrySize() = synchronized(owners) { owners.size }

        fun clearForTest() = synchronized(owners) { owners.clear() }
    }
}
