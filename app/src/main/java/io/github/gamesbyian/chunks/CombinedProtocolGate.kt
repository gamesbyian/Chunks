package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentViewScope

/**
 * A typed client's own view is combined Transfers narrowed to exactly that one client. That view
 * (and only that view) is wrapped in the client's privacy gate, which is where its lock is
 * unlocked and configured. Pure: returns the exact privacy scope and client kind, or null.
 * A client without a verifiable privacy scope is never gated by a borrowed scope: it is not
 * shown by combined Transfers at all (see [CombinedReadOnlyProtocolAdmission.accept]).
 */
internal object CombinedProtocolGate {
    data class Gate(val privacyScope: String, val clientKind: String)

    fun forView(
        scope: EnvironmentViewScope,
        protocolProfiles: List<CombinedReadOnlyProtocolProfile>,
        privacyScopes: Map<EnvironmentId, String?>
    ): Gate? {
        val only = (scope as? EnvironmentViewScope.Only)?.ids?.singleOrNull() ?: return null
        val profile = protocolProfiles.firstOrNull { it.environment == only } ?: return null
        val privacy = privacyScopes[profile.environment] ?: return null
        return Gate(privacy, RemoteProtocolLabels.kind(profile.backend))
    }
}
