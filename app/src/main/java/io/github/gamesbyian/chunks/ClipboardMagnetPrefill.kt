package io.github.gamesbyian.chunks

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Foreground clipboard prefill for an empty Add source ([ADD-SOURCE-INTAKE]).
 *
 * Read once when Add opens, never polled or read in the background. Only a
 * valid magnet or a torrent-shaped HTTPS link is placed in the field: it is a
 * draft that still needs the normal Add confirmation, and nothing is fetched,
 * started or added. A typed draft, a restored draft, a pending .torrent or an
 * Android Share/Open With item always wins.
 */
internal object ClipboardMagnetPrefill {
    private const val PREFS = "add-clipboard-prefill"
    private const val KEY_ENABLED = "enabled"

    /** On by default; IO, call off the main thread. */
    fun enabled(context: Context): Boolean = try {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    } catch (_: Exception) {
        false
    }

    fun setEnabled(context: Context, enabled: Boolean): Boolean = try {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).commit()
    } catch (_: Exception) {
        false
    }

    /**
     * The normalized magnet, or a plausible HTTPS torrent link, to prefill; null
     * when nothing should change. A link is only text: it is fetched solely
     * when the person taps Get torrent from link.
     */
    fun decide(enabled: Boolean, currentDraft: String, otherSourcePresent: Boolean, clipText: String?): String? {
        if (!enabled || currentDraft.isNotBlank() || otherSourcePresent || clipText.isNullOrBlank()) return null
        val text = clipText.trim().take(MagnetDraftCheck.MAX_LENGTH + 1)
        (MagnetDraftCheck.inspect(text) as? MagnetDraftCheck.Valid)?.let { return it.normalized }
        return text.takeIf { TorrentLinkCheck.plausibleTorrentLink(it) }
    }

    /**
     * The clipboard's first item as text, only when it is described as text.
     * Checking the description first does not read the clip, so unrelated
     * images or files are never opened. Null on any refusal.
     */
    fun readText(context: Context): String? = try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val description = clipboard.primaryClipDescription
        if (description == null || !(description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) ||
                description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))) null
        else clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()
    } catch (_: Exception) {
        null
    }
}

/** Says the draft came from the clipboard, with a one-tap undo. */
@Composable
internal fun ClipboardPrefillNote(onClear: () -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Filled from your clipboard. Nothing is added until you confirm.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(onClick = onClear, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Clear")
        }
    }
}

/** The persistent opt-out, presented under central Options. */
@Composable
internal fun ClipboardPrefillToggle(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = null)
        Text("Fill in a copied magnet or torrent link when Add opens", modifier = Modifier.padding(start = 8.dp))
    }
}
