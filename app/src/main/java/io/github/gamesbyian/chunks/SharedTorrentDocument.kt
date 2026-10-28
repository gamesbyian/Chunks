package io.github.gamesbyian.chunks

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoPreview
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * External Android .torrent entry point, separate from magnet URL handling.
 * Only a single temporarily granted content:// Uri with a supported torrent
 * MIME type is admitted. No network or native engine action is performed.
 */
internal object SharedTorrentDocument {
    private val acceptedTypes = setOf(
        "application/x-bittorrent", "application/x-torrent", "application/vnd.bittorrent"
    )

    fun fromIntent(intent: Intent?): Uri? {
        if (intent == null || intent.type?.lowercase() !in acceptedTypes) return null
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    ?: intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri)
            else -> null
        } ?: return null
        // file:// or http(s) from an external app must never become an arbitrary
        // local file or remote network read. ContentResolver grants handle access.
        return uri.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
    }

    data class Reviewed(val preview: TorrentMetainfoPreview, val bytes: ByteArray)

    /** Bounded document read, with a defensive no-progress limit for providers. */
    fun read(resolver: ContentResolver, uri: Uri): Reviewed {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Only Android content documents are accepted" }
        val input = resolver.openInputStream(uri)
            ?: throw IOException("The source app did not grant access to the torrent document")
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16_384)
            var emptyReads = 0
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) break
                if (count == 0) {
                    if (++emptyReads > 16) throw IOException("Document provider returned no torrent bytes")
                    continue
                }
                emptyReads = 0
                if (output.size() + count > TorrentMetainfoReader.MAX_BYTES) {
                    throw IOException("Torrent metadata exceeds the 2 MiB limit")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return Reviewed(TorrentMetainfoReader.preview(bytes), bytes)
    }
}
