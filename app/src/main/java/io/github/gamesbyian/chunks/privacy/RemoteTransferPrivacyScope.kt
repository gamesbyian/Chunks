package io.github.gamesbyian.chunks.privacy

import java.security.MessageDigest

/**
 * A privacy scope is one authenticated rTorrent endpoint + username.
 * It intentionally omits the password, password changes do not automatically
 * revoke the user's privacy policy, and raw server/account identifiers are
 * never written into the policy-store preference keys.
 */
internal object RemoteTransferPrivacyScope {
    private const val PREFIX = "chunks-rtorrent-account-v1:"
    fun fromAccount(endpoint: String, username: String): String? {
        val normalizedEndpoint = endpoint.trim()
        val normalizedUser = username.trim()
        if (normalizedEndpoint.isBlank() || normalizedEndpoint.length > 2048 ||
            normalizedUser.length > 512) return null
        val identity = normalizedEndpoint + "\u0000" + normalizedUser
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return PREFIX + hash
    }

    /** The connection's established authenticated scope, never a draft. */
    fun fromAuthenticatedAliasScope(value: String): String? {
        val separator = value.indexOf('\u0000')
        if (separator <= 0 || separator != value.lastIndexOf('\u0000')) return null
        return fromAccount(value.substring(0, separator), value.substring(separator + 1))
    }
}
