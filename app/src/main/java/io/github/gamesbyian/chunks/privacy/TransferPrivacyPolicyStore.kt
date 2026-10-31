package io.github.gamesbyian.chunks.privacy

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import java.security.MessageDigest

/**
 * Device-local privacy POLICY only. Never persists authorisation, successful
 * biometric callbacks, challenge tickets, credentials or transfer metadata.
 * An enabled policy is read into a freshly locked TransferPrivacySession on
 * every new process.
 */
internal class TransferPrivacyPolicyStore(context: Context) {
    companion object {
        // One shared revision for all store instances in this process. The
        // combined view observes it to invalidate old off-main-thread reads.
        private val policyRevision = mutableIntStateOf(0)
        // Coordinate migration and policy edits across independently-created stores.
        private val policyWriteLock = Any()
        val revision: Int get() = policyRevision.intValue
        private fun changed() {
            synchronized(policyRevision) {
                policyRevision.intValue++
            }
        }
    }

    private val prefs = context.applicationContext.getSharedPreferences(
        "chunks_transfer_privacy_policy_v1", Context.MODE_PRIVATE
    )

    private fun key(scope: String): String {
        require(scope.isNotBlank() && scope.length <= 512) { "Invalid privacy scope" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scope.toByteArray(Charsets.UTF_8))
        return "policy_" + digest.joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun storedMode(scope: String): TransferLockMode {
        val entry = key(scope)
        if (!prefs.contains(entry)) return TransferLockMode.NEVER
        val wire = try { prefs.getString(entry, null) } catch (_: ClassCastException) { null }
        return TransferLockMode.entries.firstOrNull { it.name == wire }
            ?: TransferLockMode.ONCE_PER_PROCESS
    }

    /**
     * Legacy releases trimmed HTTP usernames. A previously enabled lock must
     * remain enabled for its exact principal even after switching identity
     * digests. The scope factory associates this exact hashed scope with the
     * old hashed scope, without persisting raw credentials.
     * Never reuse authentication tickets or copy disabled policies.
     * If persistence fails, return the restrictive old policy for this read.
     */
    fun mode(scope: String): TransferLockMode = synchronized(policyWriteLock) {
        val exactKey = key(scope)
        if (prefs.contains(exactKey)) return@synchronized storedMode(scope)
        val legacy = RemoteTransferPrivacyScope.legacyForScope(scope)
            ?: return@synchronized TransferLockMode.NEVER
        val oldKey = key(legacy)
        if (!prefs.contains(oldKey)) return@synchronized TransferLockMode.NEVER
        val previous = storedMode(legacy)
        if (previous == TransferLockMode.NEVER) return@synchronized previous
        if (prefs.edit().putString(exactKey, previous.name).commit()) changed()
        previous
    }

    /**
     * Changing an already-enabled policy must follow a fresh platform auth,
     * including for disabling it. A failing durable commit is never success.
     */
    fun setMode(scope: String, mode: TransferLockMode,
                authenticatedForChange: Boolean): Boolean = synchronized(policyWriteLock) {
        val current = mode(scope)
        if (current == mode) return@synchronized true
        if (current != TransferLockMode.NEVER && !authenticatedForChange)
            return@synchronized false
        try {
            val editor = prefs.edit()
            val legacy = RemoteTransferPrivacyScope.legacyForScope(scope)
            // Disabling a migrated lock leaves a marker: otherwise the old
            // legacy key would re-enable protection at the next process start.
            if (mode == TransferLockMode.NEVER &&
                legacy != null && prefs.contains(key(legacy)))
                editor.putString(key(scope), TransferLockMode.NEVER.name)
            else if (mode == TransferLockMode.NEVER) editor.remove(key(scope))
            else editor.putString(key(scope), mode.name)
            val committed = editor.commit()
            if (committed) changed()
            committed
        } catch (_: RuntimeException) {
            false
        }
    }

    /** A new controller always starts enabled scopes locked. */
    fun loadInto(session: TransferPrivacySession, scope: String): Boolean =
        session.configure(scope, mode(scope), authenticatedForChange = false)
}
