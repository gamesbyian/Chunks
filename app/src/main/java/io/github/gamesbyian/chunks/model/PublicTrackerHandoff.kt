package io.github.gamesbyian.chunks.model

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Adds a *public* torrent's tracker addresses to an identity-only handoff
 * magnet, so a Copy Transfer to the phone can find peers even when the
 * server has not finished downloading (and DHT may be off).
 *
 * Only for transfers the server reports as non-private. Defence in depth:
 * only tracker URLs without any query, fragment, URL-encoded path or
 * userinfo are eligible. Credentials can hide under arbitrary query keys,
 * so a denylist of familiar passkey names is not sufficient. Long opaque
 * path tokens are also rejected. This is conservative, not a guarantee
 * that every unusual plain-text path is free of provider-specific secrets.
 */
internal object PublicTrackerHandoff {
    const val MAX_TRACKERS = 20
    private val schemes = setOf("http", "https", "udp")
    private val opaqueToken = Regex("[A-Za-z0-9]{16,}")

    fun usable(url: String): Boolean {
        if (url.length !in 8..512 || url.any { it.isISOControl() || it.isWhitespace() }) return false
        val uri = try { URI(url) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme !in schemes || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return false
        // Any query could be a provider-specific secret, even if its key is
        // not literally "passkey". Reject fragments and encoded paths too.
        if (uri.rawQuery != null || uri.rawFragment != null) return false
        val path = uri.rawPath.orEmpty()
        if ('%' in path) return false
        // "/announce" is fine; "/a1b2c3…/announce" may contain a token.
        return path.split('/', '.', '-', '_').none { opaqueToken.matches(it) }
    }

    /** [magnet] must already be a validated identity magnet. */
    fun withTrackers(magnet: String, trackers: List<String>): String =
        trackers.asSequence().map { it.trim() }.filter(::usable).distinct().take(MAX_TRACKERS)
            .fold(magnet) { acc, url ->
                acc + "&tr=" + URLEncoder.encode(url, StandardCharsets.UTF_8.toString()).replace("+", "%20")
            }
}
