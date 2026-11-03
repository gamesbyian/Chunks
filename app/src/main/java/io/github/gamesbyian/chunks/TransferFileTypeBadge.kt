package io.github.gamesbyian.chunks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/** An explicitly unverified name-derived format hint, not file inspection or metadata. */
internal data class TransferNameFormatHint(val badge: String, val explanation: String)

private val fileNameExtensions = setOf(
    "mkv", "mp4", "avi", "mov", "webm", "m4v", "mp3", "flac",
    "m4a", "opus", "ogg", "wav", "aac", "pdf", "epub", "mobi",
    "cbz", "cbr", "doc", "docx", "txt", "zip", "7z", "rar",
    "gz", "tar", "iso", "apk", "exe", "dmg", "deb", "jpg",
    "jpeg", "png", "webp", "gif", "csv", "json", "xml"
)

/**
 * Conservative offline fallback for filenames without verified cover art.
 * A multi-file torrent and unknown extension stay generic. No provider calls,
 * file-content access or categorization of potentially sensitive material.
 */
internal fun transferNameFormatHint(name: String): TransferNameFormatHint {
    val leaf = name.trim().substringAfterLast('/').substringAfterLast('\\')
    val extension = leaf.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
    if (extension !in fileNameExtensions) return TransferNameFormatHint(
        "BT", "Generic transfer; file type not verified"
    )
    return TransferNameFormatHint(
        extension.uppercase(Locale.ROOT).take(4),
        "Name suggests .$extension; actual file type not verified"
    )
}

/** Small no-network transfer art fallback with text for non-colour identification. */
@Composable
internal fun TransferFileTypeBadge(name: String, modifier: Modifier = Modifier) {
    val hint = transferNameFormatHint(name)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier.size(40.dp).clip(shape)
            .background(ChunksColors.Background)
            .border(1.dp, ChunksColors.Outline, shape)
            .semantics { contentDescription = hint.explanation },
        contentAlignment = Alignment.Center
    ) {
        Text(hint.badge, style = MaterialTheme.typography.labelSmall,
            color = ChunksColors.Text, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
