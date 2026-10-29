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
 * only generic public announce paths without queries, fragments, encoding
 * or userinfo are eligible. A credential can hide under an arbitrary query key
 * or a short path component; a passkey denylist/length threshold is insufficient.
 * Unknown tracker URL shapes are dropped rather than copied automatically.
 * Provider-specific secrets in a hostname are not inferable from URL syntax.
 */
internal object PublicTrackerHandoff {
    const val MAX_TRACKERS = 20
    private val schemes = setOf("http", "https", "udp")
    // Generic public endpoints; unknown paths might embed short passkeys.
    private val publicAnnouncePaths = setOf(
        "", "/announce", "/announce.php", "/tracker/announce", "/tracker/announce.php"
    )

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
        // Path components can hold passkeys of *any* length. Only common
        // public endpoints are safe to add without manual review.
        return path.lowercase(Locale.ROOT).trimEnd('/') in publicAnnouncePaths
    }

    /** [magnet] must already be a validated identity magnet. */
    fun withTrackers(magnet: String, trackers: List<String>): String =
        trackers.asSequence().map { it.trim() }.filter(::usable).distinct().take(MAX_TRACKERS)
            .fold(magnet) { acc, url ->
                acc + "&tr=" + URLEncoder.encode(url, StandardCharsets.UTF_8.toString()).replace("+", "%20")
            }
}
