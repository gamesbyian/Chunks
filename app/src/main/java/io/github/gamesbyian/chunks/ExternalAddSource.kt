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

            if (intent?.action == Intent.ACTION_SEND) {
                // An explicit EXTRA_STREAM is a document handoff even when its
                // grant/MIME is malformed. Never reinterpret it as a magnet.
                if (intent.hasExtra(Intent.EXTRA_STREAM)) return null
                val clip = intent.clipData
                // Multiple sources are ambiguous even when their URI entries
                // are accompanied by a plausible EXTRA_TEXT magnet.
                if (clip != null && clip.itemCount != 1) return null
                val item = clip?.getItemAt(0)
                val sharedUri = item?.uri
                if (sharedUri != null) {
                    // Browsers may send a magnet as a URI-list ClipData item,
                    // not EXTRA_TEXT. Accept only that scheme, never a
                    // rejected content:// document or an HTTP(S) document URI.
                    val magnet = schemeNormalizedMagnet(sharedUri.toString()) ?: return null
                    // A single item may supply parallel text representations.
                    // They must identify the *same exact source*: comparing
                    // only btih would lose tracker/passkey and source intent.
                    val embeddedText = item.text?.toString()?.trim()
                    if (!embeddedText.isNullOrEmpty() &&
                        schemeNormalizedMagnet(embeddedText) != magnet) return null
                    if (intent.hasExtra(Intent.EXTRA_TEXT)) {
                        val explicitText = runCatching {
                            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                        }.getOrNull() ?: return null
                        if (schemeNormalizedMagnet(explicitText) != magnet) return null
                    }
                    return Magnet(magnet)
                }
            }

            ExternalMagnetInput.fromIntent(intent)?.let { return Magnet(it) }
            return null
        }

        /** Only the URI scheme is normalized; the complete private query is untouched. */
        private fun schemeNormalizedMagnet(value: String): String? {
            val candidate = value.trim()
            if (candidate.length > MagnetDraftCheck.MAX_LENGTH ||
                !candidate.startsWith("magnet:?", ignoreCase = true)) return null
            return "magnet:?" + candidate.substring("magnet:?".length)
        }
    }
}
