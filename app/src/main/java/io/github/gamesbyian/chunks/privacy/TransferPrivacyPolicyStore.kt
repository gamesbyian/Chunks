package io.github.gamesbyian.chunks.privacy

import android.content.Context
import java.security.MessageDigest

/**
 * Device-local privacy POLICY only. Never persists authorisation, successful
 * biometric callbacks, challenge tickets, credentials or transfer metadata.
 * An enabled policy is read into a freshly locked TransferPrivacySession on
 * every new process.
 */
internal class TransferPrivacyPolicyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "chunks_transfer_privacy_policy_v1", Context.MODE_PRIVATE
    )

    private fun key(scope: String): String {
        require(scope.isNotBlank() && scope.length <= 512) { "Invalid privacy scope" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scope.toByteArray(Charsets.UTF_8))
        return "policy_" + digest.joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Unknown or damaged stored values fail CLOSED; missing is opt-in OFF. */
    fun mode(scope: String): TransferLockMode {
        val key = key(scope)
        if (!prefs.contains(key)) return TransferLockMode.NEVER
        val wire = try { prefs.getString(key, null) } catch (_: ClassCastException) { null }
        return TransferLockMode.entries.firstOrNull { it.name == wire }
            ?: TransferLockMode.ONCE_PER_PROCESS
    }

    /**
     * Changing an already-enabled policy must follow a fresh platform auth,
     * including for disabling it. A failing durable commit is never success.
     */
    @Synchronized
    fun setMode(scope: String, mode: TransferLockMode,
                authenticatedForChange: Boolean): Boolean {
        val current = mode(scope)
        if (current == mode) return true
        if (current != TransferLockMode.NEVER && !authenticatedForChange) return false
        return try {
            val editor = prefs.edit()
            if (mode == TransferLockMode.NEVER) editor.remove(key(scope))
            else editor.putString(key(scope), mode.name)
            editor.commit()
        } catch (_: RuntimeException) {
            false
        }
    }

    /** A new controller always starts enabled scopes locked. */
    fun loadInto(session: TransferPrivacySession, scope: String): Boolean =
        session.configure(scope, mode(scope), authenticatedForChange = false)
}
