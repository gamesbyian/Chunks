package io.github.gamesbyian.chunks

import android.content.Intent

/**
 * Accept one Android magnet URI or single shared magnet text as an Add DRAFT.
 * No torrent engine or remote RPC is called here. The Add screen separately
 * verifies the selected magnet and asks for explicit user confirmation.
 */
internal object ExternalMagnetInput {
    fun fromIntent(intent: Intent?): String? {
        val candidate = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            // EXTRA_TEXT is contractually CharSequence; some share providers
            // send SpannableString rather than String. getStringExtra loses it.
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            else -> null
        }?.trim() ?: return null
        if (candidate.length > 8192 || !candidate.startsWith("magnet:?", ignoreCase = true))
            return null
        // Android SEND text can use an uppercase URI scheme. The receiving
        // phone Add entry gate is deliberately strict about "magnet:?".
        // Normalize *only* those first eight ASCII characters; preserve
        // xt, dn, private trackers and every query byte for normal review.
        return "magnet:?" + candidate.substring("magnet:?".length)
    }
}
