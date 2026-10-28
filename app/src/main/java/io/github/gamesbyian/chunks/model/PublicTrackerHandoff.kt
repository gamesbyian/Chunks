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
 * any URL that still looks credential-bearing (user info, passkey-style
 * query keys or long opaque path/query tokens) is dropped rather than copied,
 * so a passkey never reaches the phone's editable Add field.
 */
internal object PublicTrackerHandoff {
    const val MAX_TRACKERS = 20
    private val schemes = setOf("http", "https", "udp")
    private val secretKeys = Regex("(?i)(?:^|[?&;])(?:passkey|pk|authkey|auth|key|token|torrent_pass|uid|secret)=")
    private val opaqueToken = Regex("[A-Za-z0-9]{16,}")

    fun usable(url: String): Boolean {
        if (url.length !in 8..512 || url.any { it.isISOControl() || it.isWhitespace() }) return false
        val uri = try { URI(url) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme !in schemes || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return false
        val rest = (uri.rawPath.orEmpty() + "?" + uri.rawQuery.orEmpty())
        if (secretKeys.containsMatchIn("?" + uri.rawQuery.orEmpty())) return false
        // "/announce" is fine; "/a1b2c3…/announce" (a per-user token) is not.
        return rest.split('/', '?', '&', '=', '.', '-', '_').none { opaqueToken.matches(it) }
    }

    /** [magnet] must already be a validated identity magnet. */
    fun withTrackers(magnet: String, trackers: List<String>): String =
        trackers.asSequence().map { it.trim() }.filter(::usable).distinct().take(MAX_TRACKERS)
            .fold(magnet) { acc, url ->
                acc + "&tr=" + URLEncoder.encode(url, StandardCharsets.UTF_8.toString()).replace("+", "%20")
            }
}
