package io.github.gamesbyian.chunks.model

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Bounded transfer-ID-only handoff across the two independent engines.
 *
 * This magnet intentionally carries no source torrent bytes, trackers,
 * passkeys, server directories, labels or resume records. A target must
 * independently discover metadata and download/verify payload. It can fail
 * for private/tracker-only torrents, which is disclosed before confirmation.
 */
internal object TorrentIdentityHandoff {
    private val v1 = Regex("[0-9a-fA-F]{40}")
    fun prepare(infoHash: String, originalName: String): String? {
        if (!v1.matches(infoHash)) return null
        val cleaned = originalName.trim().take(140)
            .filterNot { it.code < 32 || it.code == 127 || it in '\u202A'..'\u202E' ||
                it in '\u2066'..'\u2069' }
        val base = "magnet:?xt=urn:btih:" + infoHash.lowercase(Locale.ROOT)
        if (cleaned.isEmpty()) return base
        val encoded = URLEncoder.encode(cleaned, StandardCharsets.UTF_8.toString())
            .replace("+", "%20")
        return base + "&dn=" + encoded
    }
}
