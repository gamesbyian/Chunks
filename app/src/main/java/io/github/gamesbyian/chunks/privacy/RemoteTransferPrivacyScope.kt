package io.github.gamesbyian.chunks.privacy

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * A privacy scope is one authenticated rTorrent endpoint + username.
 * It intentionally omits the password, password changes do not automatically
 * revoke the user's privacy policy, and raw server/account identifiers are
 * never written into the policy-store preference keys.
 */
internal object RemoteTransferPrivacyScope {
    private const val PREFIX = "chunks-rtorrent-account-v1:"
    // A process-local, secret-free migration hint. Callers construct a scope
    // from the authenticated account before loading its policy (including the
    // combined saved-profile view). Never use the legacy scope for tickets.
    private val legacyByExactScope = ConcurrentHashMap<String, String>()

    internal fun legacyForScope(exactScope: String): String? = legacyByExactScope[exactScope]

    private fun digest(endpoint: String, username: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest((endpoint + "\u0000" + username).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return PREFIX + hash
    }
    fun fromAccount(endpoint: String, username: String): String? {
        val normalizedEndpoint = endpoint.trim()
        if (normalizedEndpoint.isBlank() || normalizedEndpoint.length > 2048 ||
            username.length > 512) return null
        // A web server can authenticate " alice " and "alice" as two accounts.
        // Only the URL input is normalized; the authenticated user is opaque.
        val exact = digest(normalizedEndpoint, username)
        val legacy = digest(normalizedEndpoint, username.trim())
        if (legacy != exact) legacyByExactScope[exact] = legacy
        return exact
    }

    /** The connection's established authenticated scope, never a draft. */
    fun fromAuthenticatedAliasScope(value: String): String? {
        val separator = value.indexOf('\u0000')
        if (separator <= 0 || separator != value.lastIndexOf('\u0000')) return null
        return fromAccount(value.substring(0, separator), value.substring(separator + 1))
    }
}
