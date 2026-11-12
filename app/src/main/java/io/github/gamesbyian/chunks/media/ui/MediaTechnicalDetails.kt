package io.github.gamesbyian.chunks.media.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.ChunksColors
import io.github.gamesbyian.chunks.media.inspection.FactSource
import io.github.gamesbyian.chunks.media.inspection.MediaByteSource
import io.github.gamesbyian.chunks.media.inspection.MediaTechnicalInfo
import io.github.gamesbyian.chunks.media.inspection.MediaTrack
import io.github.gamesbyian.chunks.media.inspection.MediaTrackInspector
import io.github.gamesbyian.chunks.media.inspection.TrackKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of one deliberate inspection; null info means "Technical details unavailable". */
data class MediaInspectionState(val running: Boolean = false, val info: MediaTechnicalInfo? = null, val attempted: Boolean = false)

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun trackLine(t: MediaTrack): String {
    val lang = when (t.languageSource) {
        FactSource.DETECTED -> t.language
        FactSource.CONTAINER_DEFAULT -> "eng (container default, not verified)"
        FactSource.MISSING -> "language unknown"
    }
    val channels = when (t.channels) { null -> null; 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${t.channels} channels" }
    return listOfNotNull(t.codec ?: "unknown codec", if (t.width != null && t.height != null) "${t.width}×${t.height}" else null, channels,
        t.sampleRate?.let { "${it / 1000.0} kHz".replace(".0 ", " ") }, lang, t.name?.let { "“$it”" }, if (t.isDefault == true) "default" else null)
        .joinToString(" · ")
}

/**
 * On-demand "Media details" for one completed *local* file (MEDIA-INSPECTION): nothing is read until
 * the user taps Inspect; reading is bounded and header-only; closing the screen cancels it.
 */
@Composable
fun MediaTechnicalDetails(fileName: String, open: () -> MediaByteSource?, modifier: Modifier = Modifier) {
    var state by remember(fileName) { mutableStateOf(MediaInspectionState()) }
    var expanded by rememberSaveable(fileName) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Media details", style = MaterialTheme.typography.titleSmall, color = ChunksColors.Text, modifier = Modifier.semantics { heading() })
        val info = state.info
        when {
            state.running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = "Inspecting" }, strokeWidth = 2.dp)
                Text("Reading file headers…", style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary)
            }
            info != null -> {
                Text(listOfNotNull(info.container, info.durationMs?.let(::formatDuration)).joinToString(" · ") +
                    "\n" + MediaTrackInspector.summary(info), style = MaterialTheme.typography.bodyMedium, color = ChunksColors.Text)
                if (info.partial) Text("Partial: only the readable headers were inspected.", style = MaterialTheme.typography.bodySmall,
                    color = ChunksColors.TextSecondary)
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (expanded) "Hide tracks" else "Show ${info.tracks.size} tracks")
                }
                if (expanded) Column(Modifier.fillMaxWidth().background(ChunksColors.Surface, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    info.tracks.forEachIndexed { i, t ->
                        val kind = when (t.kind) { TrackKind.VIDEO -> "Video"; TrackKind.AUDIO -> "Audio"; TrackKind.SUBTITLE -> "Subtitles"; TrackKind.OTHER -> "Other" }
                        Text("${i + 1}. $kind — ${trackLine(t)}", style = MaterialTheme.typography.bodySmall, color = ChunksColors.Text)
                    }
                    Text("How determined: ${info.container} headers, parser v${info.parserVersion}. Detected facts only; no frames decoded.",
                        style = MaterialTheme.typography.labelSmall, color = ChunksColors.TextSecondary)
                }
            }
            state.attempted -> Text("Technical details unavailable for this file.", style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary)
            else -> Text("Reads only this file's container headers, on this device.", style = MaterialTheme.typography.bodySmall, color = ChunksColors.TextSecondary)
        }
        if (!state.running && state.info == null) OutlinedButton(onClick = {
            state = MediaInspectionState(running = true)
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { open()?.let { MediaTrackInspector.inspect(it) } }.getOrNull() }
                state = MediaInspectionState(info = result, attempted = true)
            }
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.attempted) "Try again" else "Inspect media") }
    }
}
