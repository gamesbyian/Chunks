package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Read-only check of a typed or pasted magnet draft, shown before Add.
 *
 * It never adds anything. It tells the person, while they type, whether the
 * draft is a magnet Chunks can add, and which torrent it names (its display
 * name and infohash) so the right one is recognised before confirming.
 *
 * A base32 `xt=urn:btih:` identity (32 characters, RFC 4648) names exactly the
 * same v1 infohash as its 40-hex form; libtorrent and rTorrent accept both.
 * [Valid.normalized] rewrites only that one parameter to lowercase hex, so the
 * existing strict phone/server validators receive the standard form. Every
 * other parameter, including trackers, is kept byte for byte.
 */
internal sealed class MagnetDraftCheck {
    object Empty : MagnetDraftCheck()

    data class Problem(val message: String) : MagnetDraftCheck()

    data class Valid(
        val infoHash: String,
        /** Bounded, control-free `dn`; untrusted text from the link, or null. */
        val displayName: String?,
        val trackerCount: Int,
        val normalized: String,
        val convertedFromBase32: Boolean
    ) : MagnetDraftCheck()

    companion object {
        const val MAX_LENGTH = 8192
        private const val MAX_NAME = 160
        private val HEX = Regex("^[0-9a-fA-F]{40}$")
        private val BASE32 = Regex("^[A-Za-z2-7]{32}$")

        const val NOT_A_MAGNET =
            "Paste a magnet link (magnet:?xt=urn:btih:…) or an https:// link to a .torrent file."
        const val NO_HASH =
            "This magnet link has no BitTorrent v1 hash (xt=urn:btih:…), so it cannot be added."
        const val SEVERAL_HASHES =
            "This magnet link names more than one torrent. Use a link with exactly one v1 hash."
        const val BAD_HASH =
            "This magnet's hash is incomplete or mistyped. A v1 hash has 40 hexadecimal or 32 base32 characters."
        const val V2_ONLY =
            "This magnet names only a BitTorrent v2 hash (btmh). Chunks adds v1 or hybrid magnets."
        const val TOO_LONG = "This magnet link is too long to be added."
        const val CONTROL = "This magnet link contains a line break or control character. Paste it again as one line."

        fun inspect(raw: String): MagnetDraftCheck {
            val text = raw.trim()
            if (text.isEmpty()) return Empty
            if (!text.startsWith("magnet:?", ignoreCase = true)) return Problem(NOT_A_MAGNET)
            if (text.length > MAX_LENGTH) return Problem(TOO_LONG)
            if (text.any { it.isISOControl() }) return Problem(CONTROL)
            val parts = text.substring("magnet:?".length).split('&')
            var btih = 0
            var btmh = false
            var hashIndex = -1
            var hashValue = ""
            var name: String? = null
            parts.forEachIndexed { index, part ->
                val pair = part.split('=', limit = 2)
                if (pair.size != 2) return@forEachIndexed
                when {
                    pair[0].equals("xt", ignoreCase = true) -> {
                        val value = decode(pair[1]) ?: pair[1]
                        when {
                            value.startsWith("urn:btih:", ignoreCase = true) -> {
                                btih++
                                hashIndex = index
                                hashValue = value.substring(9)
                            }
                            value.startsWith("urn:btmh:", ignoreCase = true) -> btmh = true
                        }
                    }
                    pair[0].equals("dn", ignoreCase = true) && name == null ->
                        name = decode(pair[1].replace('+', ' '))?.let(::cleanName)
                }
            }
            if (btih > 1) return Problem(SEVERAL_HASHES)
            if (btih == 0) return Problem(if (btmh) V2_ONLY else NO_HASH)
            val hex = when {
                HEX.matches(hashValue) -> hashValue.lowercase(Locale.ROOT)
                BASE32.matches(hashValue) -> base32ToHex(hashValue)
                else -> null
            } ?: return Problem(BAD_HASH)
            val converted = !HEX.matches(hashValue)
            // Rebuild only the one identity parameter, so a base32, uppercase or
            // percent-encoded hash reaches the strict validators as plain hex.
            val rebuilt = parts.toMutableList()
            rebuilt[hashIndex] = "xt=urn:btih:" + hex
            val normalized = "magnet:?" + rebuilt.joinToString("&")
            return Valid(
                infoHash = hex,
                displayName = name,
                trackerCount = io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader.magnetTrackerCount(normalized),
                normalized = normalized,
                convertedFromBase32 = converted
            )
        }

        private fun decode(value: String): String? = try {
            java.net.URLDecoder.decode(value, "UTF-8")
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun cleanName(value: String): String? {
            val clean = value.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
                .trim()
            if (clean.isEmpty()) return null
            return if (clean.length <= MAX_NAME) clean else clean.take(MAX_NAME - 1).trimEnd() + "…"
        }

        /** RFC 4648 base32 (no padding) of exactly 20 bytes → 40 lowercase hex. */
        internal fun base32ToHex(value: String): String? {
            if (!BASE32.matches(value)) return null
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            val out = StringBuilder(40)
            var buffer = 0L
            var bits = 0
            for (c in value.uppercase(Locale.ROOT)) {
                buffer = (buffer shl 5) or alphabet.indexOf(c).toLong()
                bits += 5
                while (bits >= 8) {
                    bits -= 8
                    out.append(String.format(Locale.ROOT, "%02x", (buffer shr bits) and 0xFF))
                }
                buffer = buffer and ((1L shl bits) - 1)
            }
            return out.toString().takeIf { it.length == 40 }
        }

        /** Short, readable hash label: the first and last eight characters. */
        fun shortHash(hex: String): String =
            if (hex.length <= 20) hex else hex.take(8) + "…" + hex.takeLast(8)
    }
}

/**
 * The magnet check under a magnet field. A problem is announced politely and
 * shown in the error colour with its words, never colour alone.
 */
@Composable
internal fun MagnetDraftSummary(check: MagnetDraftCheck, modifier: Modifier = Modifier) {
    when (check) {
        MagnetDraftCheck.Empty -> Unit
        is MagnetDraftCheck.Problem -> Text(
            check.message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
        )
        is MagnetDraftCheck.Valid -> Column(modifier.fillMaxWidth()) {
            Text(
                "Magnet for: " + (check.displayName ?: "name arrives with the torrent's metadata"),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Hash " + MagnetDraftCheck.shortHash(check.infoHash) + " · " + when (check.trackerCount) {
                    0 -> "no trackers listed"
                    1 -> "1 tracker"
                    else -> "${check.trackerCount} trackers"
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (check.convertedFromBase32) Text(
                "This link's base32 hash is the same torrent as the standard hash shown; " +
                    "Chunks adds it in the standard form.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Paste the clipboard's text into a magnet draft, only when tapped. Chunks
 * never reads the clipboard on its own; the pasted draft is still checked and
 * needs the usual Add confirmation.
 */
@Composable
internal fun PasteMagnetButton(
    enabled: Boolean,
    onPaste: (String) -> Unit,
    onNothingToPaste: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            val text = try {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
            } catch (_: Exception) {
                null
            }
            if (text.isNullOrEmpty()) onNothingToPaste()
            else onPaste(text.take(MagnetDraftCheck.MAX_LENGTH + 1))
        },
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp)
    ) { Text("Paste link") }
}
