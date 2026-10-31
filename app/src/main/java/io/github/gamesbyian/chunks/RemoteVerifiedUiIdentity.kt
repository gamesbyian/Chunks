package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.RemoteCredentials

/**
 * The view's account boundary is frozen from credentials that actually passed
 * the Connect probe. A mutable login field, saved-view selection or another
 * profile must never be substituted after a suspending network read.
 *
 * The endpoint has the established whitespace-normalization rule, but the HTTP
 * authenticated principal is opaque: even leading/trailing spaces distinguish
 * separate accounts. The alias never includes the password.
 */
internal object RemoteVerifiedUiIdentity {
    fun aliasScopeOf(verified: RemoteCredentials): String =
        verified.endpoint.trim() + "\u0000" + verified.username

    /**
     * Only a stored login for the exact same authenticated principal can be
     * cleared while forgetting a saved profile. Never clear another account's
     * one-profile legacy vault merely because its trimmed username matches.
     */
    fun samePrincipal(a: RemoteCredentials?, b: RemoteCredentials): Boolean =
        a != null && a.endpoint.trim() == b.endpoint.trim() && a.username == b.username
}
