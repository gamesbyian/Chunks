package io.github.gamesbyian.chunks.privacy

/**
 * In-memory session authorisation for local transfers and separate rTorrent
 * profiles. This class contains no Android UI, credentials or persisted unlock
 * tokens. A new process MUST construct a new controller, initially locked.
 *
 * BiometricPrompt/Keyguard auth, settings storage, notification/recents redaction
 * and guarded Compose routes remain separate mandatory integration gates.
 */
internal enum class TransferLockMode {
    NEVER,
    ONCE_PER_PROCESS,
    EACH_FOREGROUND_RETURN,
    AFTER_FIVE_MINUTES_BACKGROUND,
    EVERY_ENTRY
}

internal class TransferPrivacySession(
    private val elapsedRealtimeMillis: () -> Long
) {
    companion object { private const val FIVE_MINUTES_MS = 300_000L }

    data class Ticket internal constructor(
        val scope: String,
        val generation: Long,
        val nonce: Long
    )

    private data class Gate(
        var mode: TransferLockMode,
        var generation: Long = 0,
        var authorized: Boolean = false,
        var challengeNonce: Long? = null,
        var leftBackgroundAt: Long? = null
    )

    private val gates = LinkedHashMap<String, Gate>()
    private var nextNonce = 1L

    /** Initial policy is locked unless explicitly NEVER. Changing any enabled
     * policy requires current device auth (outside this class). */
    @Synchronized
    fun configure(scope: String, mode: TransferLockMode, authenticatedForChange: Boolean): Boolean {
        require(scope.isNotBlank() && scope.length <= 512) { "Invalid privacy scope" }
        val existing = gates[scope]
        if (existing != null && existing.mode == mode) return true
        if (existing != null && existing.mode != TransferLockMode.NEVER &&
            !authenticatedForChange) return false
        gates[scope] = Gate(mode, generation = (existing?.generation ?: 0) + 1)
        return true
    }

    /**
     * No state is shared between scopes. A null/unconfigured scope defaults
     * to visible for opt-in usability, never to global auth reuse.
     */
    @Synchronized
    fun mayView(scope: String): Boolean {
        val gate = gates[scope] ?: return true
        expireBackgroundIfNeeded(gate)
        return gate.mode == TransferLockMode.NEVER || gate.authorized
    }

    /** Authorization for interaction follows the same gate as disclosure. */
    @Synchronized
    fun mayAct(scope: String) = mayView(scope)

    /**
     * Exactly one pending UI prompt per scope. A second concurrent prompt is
     * refused. A stale callback cannot unlock a policy after Lock now, scope
     * exit or a policy change.
     */
    @Synchronized
    fun beginUnlock(scope: String): Ticket? {
        val gate = gates[scope] ?: return null
        if (gate.mode == TransferLockMode.NEVER || mayView(scope) ||
            gate.challengeNonce != null) return null
        val ticket = Ticket(scope, gate.generation, nextNonce++)
        gate.challengeNonce = ticket.nonce
        return ticket
    }

    @Synchronized
    fun finishUnlock(ticket: Ticket, deviceAuthenticated: Boolean): Boolean {
        val gate = gates[ticket.scope] ?: return false
        if (gate.generation != ticket.generation ||
            gate.challengeNonce != ticket.nonce) return false
        gate.challengeNonce = null
        if (!deviceAuthenticated || gate.mode == TransferLockMode.NEVER) return false
        gate.authorized = true
        gate.leftBackgroundAt = null
        return true
    }

    /** Manual lock affects only its specific engine/profile. */
    @Synchronized
    fun lockNow(scope: String) {
        val gate = gates[scope] ?: return
        gate.generation++
        gate.authorized = false
        gate.challengeNonce = null
        gate.leftBackgroundAt = null
    }

    /** App-wide background transition; does not pause torrent engines. */
    @Synchronized
    fun onBackground() {
        val now = elapsedRealtimeMillis()
        for (gate in gates.values) {
            // A platform prompt that leaves the foreground must not deliver a
            // delayed success to this session. Keep an existing once-per-process
            // grant intact, but revoke the pending prompt generation everywhere.
            if (gate.challengeNonce != null) {
                gate.generation++
                gate.challengeNonce = null
            }
            when (gate.mode) {
                TransferLockMode.EACH_FOREGROUND_RETURN -> {
                    gate.generation++
                    gate.authorized = false
                    gate.challengeNonce = null
                }
                TransferLockMode.AFTER_FIVE_MINUTES_BACKGROUND -> {
                    if (gate.leftBackgroundAt == null) gate.leftBackgroundAt = now
                }
                else -> Unit
            }
        }
    }

    /** A bad/reversed monotonic clock fails closed when expiry matters. */
    @Synchronized
    fun onForeground() {
        for (gate in gates.values) {
            expireBackgroundIfNeeded(gate)
            gate.leftBackgroundAt = null
        }
    }

    /** Called when leaving a protected transfer surface, not during rotation. */
    @Synchronized
    fun onExit(scope: String) {
        val gate = gates[scope] ?: return
        if (gate.mode == TransferLockMode.EVERY_ENTRY) lockNow(scope)
    }

    @Synchronized
    fun onCancelUnlock(ticket: Ticket) {
        val gate = gates[ticket.scope] ?: return
        if (gate.generation == ticket.generation && gate.challengeNonce == ticket.nonce)
            gate.challengeNonce = null
    }

    private fun expireBackgroundIfNeeded(gate: Gate) {
        if (gate.mode != TransferLockMode.AFTER_FIVE_MINUTES_BACKGROUND) return
        val away = gate.leftBackgroundAt ?: return
        val now = elapsedRealtimeMillis()
        if (now < away || now - away >= FIVE_MINUTES_MS) {
            gate.generation++
            gate.authorized = false
            gate.challengeNonce = null
            gate.leftBackgroundAt = null
        }
    }
}
