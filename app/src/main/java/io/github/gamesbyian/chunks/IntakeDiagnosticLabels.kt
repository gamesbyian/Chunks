package io.github.gamesbyian.chunks

import java.util.Locale

/**
 * Android's untrusted intent type and URI scheme are not safe raw log fields.
 * Only a fixed vocabulary may enter ChunksIntake logcat diagnostics; URL
 * queries, document IDs, tracker passkeys and sender-invented MIME strings do
 * not belong in an exported owner/CI log.
 */
internal object IntakeDiagnosticLabels {
    fun mime(raw: String?): String = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        null -> "none"
        "application/x-bittorrent", "application/x-torrent", "application/vnd.bittorrent" -> "torrent"
        "application/octet-stream" -> "generic-binary"
        "text/plain" -> "text"
        "text/uri-list" -> "uri-list"
        else -> "other"
    }

    fun scheme(raw: String?): String = when (raw?.lowercase(Locale.ROOT)) {
        null -> "none"
        "content", "file", "magnet", "http", "https" -> raw.lowercase(Locale.ROOT)
        else -> "other"
    }
}
