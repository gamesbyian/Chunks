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
 * A single temporarily granted content:// Uri with a torrent or generic-binary
 * MIME type can enter Add review. Providers often use opaque document IDs,
 * so a filename-shaped URI is not required. The bytes, not MIME/filename,
 * must pass the bounded strict metainfo parser before any Add/Start. No engine
 * mutation or network request is performed by intent admission.
 */
internal object SharedTorrentDocument {
    private val acceptedTypes = setOf(
        "application/x-bittorrent", "application/x-torrent", "application/vnd.bittorrent"
    )

    fun fromIntent(intent: Intent?): Uri? {
        if (intent == null) return null
        val clip = when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_SEND -> try {
                intent.clipData
            } catch (_: RuntimeException) {
                return null
            }
            else -> return null
        }
        if (clip != null && clip.itemCount != 1) return null
        val clipUri = clip?.getItemAt(0)?.uri
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> {
                val viewed = intent.data ?: return null
                if (clipUri != null && clipUri != viewed) return null
                viewed
            }
            Intent.ACTION_SEND -> {
                // Android may supply EXTRA_STREAM and ClipData together to grant
                // access to the same document. Never silently choose one of
                // multiple or conflicting URIs: this screen reviews one torrent.
                // An external sender may put a String, integer or a broken
                // Parcelable into EXTRA_STREAM. Android's typed retrieval can
                // throw while unparcelling/casting it; do not crash Add or
                // treat incidental EXTRA_TEXT as the chosen replacement.
                @Suppress("DEPRECATION")
                val streamUri = try {
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                } catch (_: RuntimeException) {
                    return null
                }
                // A present-but-invalid EXTRA_STREAM must not quietly fall
                // back to a different ClipData URI, even when Android's
                // getParcelableExtra returns null instead of throwing.
                if (intent.hasExtra(Intent.EXTRA_STREAM) && streamUri == null) return null
                if (streamUri != null && clipUri != null && streamUri != clipUri) return null
                streamUri ?: clipUri
            }
            else -> null
        } ?: return null
        // No file:// or HTTP(S) source, even when a caller lies about its type.
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val mime = intent.type?.trim()?.lowercase(java.util.Locale.ROOT)
        // A provider's content URI may end in an opaque row ID. The Android
        // chooser cannot inspect DISPLAY_NAME, and synchronous provider queries
        // here could block Activity startup. Generic-binary MIME therefore
        // only provisionally admits a source. The review's strict 2 MiB parser
        // still rejects arbitrary files before any authorized Add/Start.
        // Android can resolve VIEW against the sending provider's MIME type
        // without filling in Intent.type. Admit that handoff provisionally,
        // as for generic binary, and require the same bounded metainfo read.
        val androidResolvedViewType = mime == null && intent.action == Intent.ACTION_VIEW
        if (!androidResolvedViewType && mime !in acceptedTypes && mime != "application/octet-stream") return null
        // Neither a filename, declared type nor URI is proof of metainfo.
        return uri
    }

    data class Reviewed(val preview: TorrentMetainfoPreview, val bytes: ByteArray)

    /** Bounded document read, with a defensive no-progress limit for providers. */
    fun read(resolver: ContentResolver, uri: Uri): Reviewed {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Only Android content documents are accepted" }
        val input = resolver.openInputStream(uri)
            ?: throw IOException("The source app did not grant access to the torrent document")
        return readStream(input)
    }

    /** Shared bounded parser for Android VIEW, SEND and the remote file picker. */
    internal fun readStream(input: java.io.InputStream): Reviewed {
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
