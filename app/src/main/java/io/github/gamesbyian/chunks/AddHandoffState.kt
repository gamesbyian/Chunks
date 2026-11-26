package io.github.gamesbyian.chunks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The exact authenticated server session an asynchronous preparation or a
 * post-Add focus belongs to. Identity, not equality: a reconnect or a different
 * account creates a different live session object, so nothing prepared or
 * remembered for the previous one can match again.
 */
internal class RemoteSessionStamp(val session: Any, val aliasScope: String) {
    fun matches(currentSession: Any?, currentAliasScope: String): Boolean =
        currentSession != null && currentSession === session && currentAliasScope == aliasScope
}

/**
 * One in-RAM magnet travelling from a server job to the Local Add review
 * (A2611-02). The slot is the only authority for that handoff:
 * - a single value, so a newer handoff replaces only an earlier UNCONSUMED handoff;
 * - claimed exactly once by the Local review, never replayed;
 * - it never overwrites an existing source: the consumer reports it as refused;
 * - it dies when its person leaves the Local destination, when its server
 *   session ends, and with the owner.
 *
 * The slot never reaches SavedState. A magnet may carry tracker passkeys.
 */
internal class AddHandoffState {
    /** The unconsumed handoff, or null. Compare by identity. */
    var staged by mutableStateOf<LocalHandoff?>(null)
        private set

    /** Bumped by every stage, drop and new preparation; an older completion is then stale. */
    private var epoch = 0L
    private var preparation: Job? = null
    private var preparationStamp: RemoteSessionStamp? = null

    /** [origin] is the server session that produced the magnet; null for an Android share. */
    class LocalHandoff internal constructor(val magnet: String, val origin: RemoteSessionStamp?) {
        /** The Local review has been visible with this handoff; leaving it later retires it. */
        var arrived = false
            internal set
    }

    /** Stage an already-available magnet. Replaces only an earlier unconsumed handoff. */
    fun stage(magnet: String, origin: RemoteSessionStamp? = null): LocalHandoff {
        cancelPreparation()
        epoch++
        return LocalHandoff(magnet, origin).also { staged = it }
    }

    /**
     * Prepare a handoff off the main thread, in [scope] (the app-owned scope, so a
     * configuration change does not discard the work), for exactly [stamp]'s session.
     * [lookup] returns the magnet, or null to give up. The result is staged only if
     * nothing was dropped, replaced or re-requested meanwhile AND the live session
     * is still the stamped one. A cancelled lookup rethrows and stages nothing.
     */
    fun prepare(
        scope: CoroutineScope,
        stamp: RemoteSessionStamp,
        currentSession: () -> Pair<Any?, String>,
        mayAct: (String) -> Boolean = { true },
        lookup: suspend () -> String?
    ): Job {
        cancelPreparation()
        val mine = ++epoch
        preparationStamp = stamp
        val job = scope.launch {
            val magnet = try {
                lookup()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            val (session, aliasScope) = currentSession()
            if (magnet != null && epoch == mine && stamp.matches(session, aliasScope) && mayAct(aliasScope)) {
                staged = LocalHandoff(magnet, stamp)
            }
            if (epoch == mine) {
                preparation = null
                preparationStamp = null
            }
        }
        preparation = job
        return job
    }

    /** Consume exactly this handoff. A stale, null or already-claimed one returns false. */
    fun claim(expected: LocalHandoff?): Boolean {
        if (expected == null || staged !== expected) return false
        staged = null
        return true
    }

    /** Retire the handoff and any preparation in flight. */
    fun drop() {
        cancelPreparation()
        epoch++
        staged = null
    }

    /** The server session ended or changed: retire only work that belongs to another session. */
    fun dropIfSessionChanged(currentSession: Any?, currentAliasScope: String) {
        val preparing = preparationStamp
        val waiting = staged?.origin
        if ((preparing != null && !preparing.matches(currentSession, currentAliasScope)) ||
            (waiting != null && !waiting.matches(currentSession, currentAliasScope))) drop()
    }

    /**
     * Revoke what a server session produced while that session's privacy scope no longer
     * authorizes interaction (lock, expiry, scope change). [mayAct] is asked for the exact
     * session's alias scope. A rotation that stays inside an unlocked window revokes nothing,
     * so nothing here counts foreground/background transitions.
     */
    fun revokeIfLocked(mayAct: (String) -> Boolean) {
        val preparing = preparationStamp
        val waiting = staged?.origin
        if ((preparing != null && !mayAct(preparing.aliasScope)) ||
            (waiting != null && !mayAct(waiting.aliasScope))) drop()
    }

    /**
     * Called by the live composition for the current destination.
     * Returns what to do with an unconsumed handoff.
     */
    fun arbitrate(localVisible: Boolean): Arbitration {
        val handoff = staged ?: return Arbitration.NONE
        if (localVisible) {
            handoff.arrived = true
            return Arbitration.NONE
        }
        if (handoff.arrived) {
            drop()
            return Arbitration.DROPPED
        }
        return Arbitration.NAVIGATE_TO_LOCAL
    }

    enum class Arbitration { NONE, NAVIGATE_TO_LOCAL, DROPPED }

    private fun cancelPreparation() {
        preparation?.cancel()
        preparation = null
        preparationStamp = null
    }
}

/**
 * Which Add the person last verified, on which exact server session. A server
 * record is bound to that live session object: disconnect, forget, privacy lock,
 * profile switch and reconnect all replace it, so the record can never be shown
 * to another account or to a later session, whichever path ended the first.
 * The Local key carries no account. In RAM only.
 */
internal class PostAddFocus {
    var remote by mutableStateOf<RemoteAddedRecord?>(null)
        private set
    private var remoteSession: Any? = null
    var latest by mutableStateOf<UnifiedTransferKey?>(null)
        private set

    fun rememberLocal(key: UnifiedTransferKey?) {
        latest = key
    }

    fun rememberRemote(session: Any, record: RemoteAddedRecord) {
        remote = record
        remoteSession = session
        latest = record.combinedKey(record.aliasScope)
    }

    /** The server record, only for its own live session and account. */
    fun remoteFor(currentSession: Any?, currentAliasScope: String): RemoteAddedRecord? {
        val record = remote ?: return null
        return record.takeIf {
            currentSession != null && currentSession === remoteSession &&
                it.aliasScope == currentAliasScope
        }
    }

    /** The focused key, only if it is Local or belongs to the exact live server session. */
    fun latestFor(currentSession: Any?, currentAliasScope: String): UnifiedTransferKey? {
        val key = latest ?: return null
        if (key.environment == io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL) return key
        return key.takeIf { remoteFor(currentSession, currentAliasScope)?.combinedKey(currentAliasScope) == key }
    }

    /** Same contract as [AddHandoffState.revokeIfLocked]. */
    fun revokeIfLocked(mayAct: (String) -> Boolean) {
        val record = remote ?: return
        if (!mayAct(record.aliasScope)) clearRemote()
    }

    /** Disconnect, forget, privacy lock, profile switch or session replacement. */
    fun clearRemote() {
        remote = null
        remoteSession = null
        if (latest?.environment != io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL) latest = null
    }
}
