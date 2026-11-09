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
    /** A source link only. Existing Add must explicitly fetch, validate and confirm its metainfo. */
    data class TorrentLink(val value: String) : ExternalAddSource
    data class TorrentDocument(val uri: Uri) : ExternalAddSource

    companion object {
        fun fromIntent(intent: Intent?): ExternalAddSource? {
            return try {
            // Intent extras and ClipData are untrusted Parcelable input. Even
            // after document admission refuses a malformed parcel, source
            // selection can encounter it again. Fail closed for the *whole*
            // source decision; never fall back to incidental magnet text.
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
                    // Browsers may send a single magnet or HTTPS .torrent link
                    // as a URI-list item, not EXTRA_TEXT. This admits only a
                    // draft: no remote URL, content URI or local file is read.
                    val source = sharedTextSource(sharedUri.toString()) ?: return null
                    // A single item may supply parallel text representations.
                    // They must identify the *same exact source*: comparing
                    // only btih would lose tracker/passkey and source intent.
                    val embeddedText = item.text?.toString()?.trim()
                    if (!embeddedText.isNullOrEmpty() &&
                        sharedTextSource(embeddedText) != source) return null
                    if (intent.hasExtra(Intent.EXTRA_TEXT)) {
                        val explicitText = runCatching {
                            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                        }.getOrNull() ?: return null
                        if (sharedTextSource(explicitText) != source) return null
                    }
                    return source
                }
                // ACTION_SEND may carry a single plain-text ClipData item as
                // well as EXTRA_TEXT. Both are independent source assertions,
                // not a priority order: a conflicting tracker/passkey or hash
                // must not be silently replaced by either representation.
                if (item?.text != null) {
                    val clipSource = sharedTextSource(item.text.toString()) ?: return null
                    if (intent.hasExtra(Intent.EXTRA_TEXT)) {
                        val explicitText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                            ?.toString() ?: return null
                        if (sharedTextSource(explicitText) != clipSource) return null
                    }
                    return clipSource
                }
            }

            ExternalMagnetInput.fromIntent(intent)?.let { return Magnet(it) }
            // Android ACTION_SEND with a pure HTTPS download link enters the
            // same editable Add source as a pasted link. Generic HTTPS VIEW
            // intents remain the browser's, not an app-wide Chunks handler.
            if (intent?.action == Intent.ACTION_SEND) {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                text?.let(::sharedTextSource)
            } else null
            } catch (_: RuntimeException) {
                null
            }
        }

        /** No URL fetch here. Preserve exact signed query and normalize only a URI scheme. */
        private fun sharedTextSource(text: String): ExternalAddSource? {
            val candidate = text.trim()
            schemeNormalizedMagnet(candidate)?.let { return Magnet(it) }
            if (TorrentLinkCheck.inspect(candidate) !is TorrentLinkCheck.Fetchable) return null
            return TorrentLink("https://" + candidate.substring("https://".length))
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
