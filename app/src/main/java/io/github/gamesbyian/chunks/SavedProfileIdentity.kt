package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentSnapshots

/**
 * A saved server listing may use only the exact identity originally verified
 * for its authenticated endpoint + account. A changed account or ruTorrent
 * instance must be reconnected and reverified, never silently relabelled.
 */
internal object SavedProfileIdentity {
    /**
     * Freeze the alias scope from credentials that actually passed the
     * read-only authentication/inventory probe. Never reread an editable
     * username field after a suspending Connect or trim the HTTP principal.
     */
    fun fromVerifiedCredentials(credentials: io.github.gamesbyian.chunks.rpc.RemoteCredentials): String =
        credentials.endpoint.trim() + "\u0000" + credentials.username

    /**
     * For Forget: never clear the legacy one-profile vault because another
     * account shares the same trimmed username. Endpoint input whitespace
     * may be normalized, but HTTP authentication identities remain verbatim.
     */
    fun sameAuthenticatedAccount(
        old: io.github.gamesbyian.chunks.rpc.RemoteCredentials?,
        selected: io.github.gamesbyian.chunks.rpc.RemoteCredentials
    ): Boolean = old != null && old.endpoint.trim() == selected.endpoint.trim() &&
        old.username == selected.username

    fun matches(savedId: String, aliasScope: String?): Boolean =
        aliasScope != null &&
            EnvironmentSnapshots.serverIdOfAliasScope(aliasScope)?.value == savedId
}
