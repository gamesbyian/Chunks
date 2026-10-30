package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.privacy.TransferLockMode
import io.github.gamesbyian.chunks.privacy.TransferPrivacySession
import io.github.gamesbyian.chunks.privacy.TransferPrivacyVisibility

/**
 * Immutable disclosure evidence produced by an off-UI-thread policy read.
 * A new process, policy edit, profile roster, or foreground transition must
 * first reload this snapshot. Until then every requested environment is locked.
 *
 * Only policy modes are stored here: never unlock tickets or authenticated data.
 */
internal data class CombinedPrivacyEvidence(
    val requestedScopes: Set<String>,
    val policyRevision: Int,
    val lifecycleRevision: Int,
    val modes: Map<String, TransferLockMode>
) {
    fun current(scopes: Set<String>, policyEpoch: Int, lifecycleEpoch: Int): Boolean =
        requestedScopes == scopes && policyRevision == policyEpoch &&
            lifecycleRevision == lifecycleEpoch &&
            modes.keys.containsAll(scopes)

    fun decision(
        scope: String?,
        session: TransferPrivacySession,
        deviceSecure: Boolean
    ): TransferPrivacyVisibility.Decision {
        if (scope == null || scope !in requestedScopes) return LOCKED
        val mode = modes[scope] ?: return LOCKED
        // This mutates only the ephemeral process-local gate, never persistence.
        // A conflicting previously authorized policy MUST NOT expose content.
        if (!session.configure(scope, mode, authenticatedForChange = false)) return LOCKED
        return TransferPrivacyVisibility.decide(mode, synchronized = true,
            deviceSecure = deviceSecure, sessionMayView = session.mayView(scope))
    }

    companion object {
        val LOCKED = TransferPrivacyVisibility.Decision(
            mayShow = false, needsSecureWindow = true)
    }
}
