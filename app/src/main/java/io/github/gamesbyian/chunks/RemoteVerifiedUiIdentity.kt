package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import io.github.gamesbyian.chunks.rpc.RemoteEndpointHints

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

    /** Full reviewed connection identity, without rendering invalid or secret-bearing URLs. */
    fun removalScopeLabel(scope: String): String? {
        val separator = scope.indexOf('\u0000')
        if (separator <= 0) return null
        val endpoint = scope.substring(0, separator)
        // This is the same validation used by RTorrentRemote: it excludes
        // userinfo, query and fragment, all of which can carry credentials.
        try { RemoteEndpointHints.rpcAddress(endpoint) } catch (_: Exception) { return null }
        val account = buildString {
            for (char in scope.substring(separator + 1)) {
                when {
                    char == '\\' -> append("\\\\")
                    char == '"' -> append("\\\"")
                    char.isWhitespace() || char.isISOControl() ||
                        Character.getType(char) == Character.FORMAT.toInt() ->
                        append("\\u%04X".format(char.code))
                    else -> append(char)
                }
            }
        }
        return "Server: $endpoint\nAccount: \"$account\""
    }

    /**
     * Only a stored login for the exact same authenticated principal can be
     * cleared while forgetting a saved profile. Never clear another account's
     * one-profile legacy vault merely because its trimmed username matches.
     */
    fun samePrincipal(a: RemoteCredentials?, b: RemoteCredentials): Boolean =
        a != null && a.endpoint.trim() == b.endpoint.trim() && a.username == b.username
}
