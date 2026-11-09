package io.github.gamesbyian.chunks

import android.content.Intent
import android.net.Uri

/**
 * One source chosen from an Android VIEW/SEND intent, with no I/O or engine
 * mutation. A genuine .torrent document has precedence over incidental
 * EXTRA_TEXT in the same share: there must never be two competing Add drafts.
 *
 * Source values and content URIs may contain private tokens. The caller holds
 * these only in process memory and routes to an explicit Add review.
 */
internal sealed interface ExternalAddSource {
    data class Magnet(val value: String) : ExternalAddSource
    data class TorrentDocument(val uri: Uri) : ExternalAddSource

    companion object {
        fun fromIntent(intent: Intent?): ExternalAddSource? {
            SharedTorrentDocument.fromIntent(intent)?.let { return TorrentDocument(it) }

            // A SEND containing an explicit document URI is one document
            // handoff, even if its grant, MIME or URI list is unusable.
            // Never silently substitute incidental EXTRA_TEXT (including a
            // magnet) after the document gate rejected an ambiguous source.
            if (intent?.action == Intent.ACTION_SEND &&
                (intent.hasExtra(Intent.EXTRA_STREAM) ||
                    intent.clipData?.let { clip ->
                        (0 until clip.itemCount).any { clip.getItemAt(it).uri != null }
                    } == true)) return null

            ExternalMagnetInput.fromIntent(intent)?.let { return Magnet(it) }
            return null
        }
    }
}
