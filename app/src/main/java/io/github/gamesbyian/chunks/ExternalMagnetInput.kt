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
        return candidate.takeIf { it.length <= 8192 && it.startsWith("magnet:?", ignoreCase = true) }
    }
}
