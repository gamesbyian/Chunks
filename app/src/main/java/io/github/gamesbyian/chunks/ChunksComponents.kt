package io.github.gamesbyian.chunks

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Visual state family from the UI design sheet (05): one colour per kind of
 * transfer state. Always paired with a text label; colour is never the only
 * signal.
 */
internal enum class ChunksTone(val color: Color) {
    /** Downloading, fetching metadata, checking. */
    ACTIVE(ChunksColors.Cyan),
    /** Stopped or paused by the user. */
    PAUSED(ChunksColors.Yellow),
    /** Complete and sharing. */
    COMPLETE(ChunksColors.Green),
    /** An engine or tracker error the user should look at. */
    ERROR(ChunksColors.Red),
    /** Waiting, unknown or not applicable. */
    INACTIVE(ChunksColors.Outline)
}

/** Compact rounded status label: accent fill with near-black text. */
@Composable
internal fun StatusPill(label: String, tone: ChunksTone, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier = modifier
            .background(tone.color, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = if (tone == ChunksTone.INACTIVE) ChunksColors.Text else ChunksColors.OnAccent,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        maxLines = 1
    )
}

/**
 * Coloured action tile from the design sheet: filled accent, near-black
 * label, rounded rectangle, at least 56dp tall. Destructive actions still
 * open their own confirmation; the colour only identifies the action.
 */
@Composable
internal fun ActionTile(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Library icon (generated VectorDrawable); decorative, the label carries meaning. */
    @DrawableRes icon: Int? = null,
    /** Icon above the label (tiles in a row) or beside it (full-width tiles). */
    stacked: Boolean = true
) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = ChunksColors.OnAccent),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    ) {
        if (stacked) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TileIcon(icon, 28.dp, enabled)
            TileLabel(label)
        } else Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TileIcon(icon, 24.dp, enabled)
            TileLabel(label)
        }
    }
}

@Composable
private fun TileIcon(@DrawableRes icon: Int?, size: androidx.compose.ui.unit.Dp, enabled: Boolean) {
    if (icon != null) Image(painterResource(icon), contentDescription = null,
        modifier = Modifier.size(size).alpha(if (enabled) 1f else 0.38f))
}

@Composable
private fun TileLabel(label: String) {
    Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold,
        textAlign = TextAlign.Center)
}

/** Decorative on-dark file-type icon chosen from the file extension only. */
@DrawableRes
internal fun fileTypeIcon(path: String): Int =
    when (path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
        "mkv", "mp4", "avi", "mov", "webm", "m4v", "ts", "wmv" -> R.drawable.chunks_ic_file_video_on_dark
        "mp3", "flac", "ogg", "opus", "m4a", "m4b", "wav", "aac" -> R.drawable.chunks_ic_file_audio_on_dark
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> R.drawable.chunks_ic_file_image_on_dark
        "zip", "rar", "7z", "tar", "gz", "xz", "bz2" -> R.drawable.chunks_ic_file_archive_on_dark
        else -> R.drawable.chunks_ic_file_document_on_dark
    }

/**
 * Slim per-file progress line for file rows. Decorative: the row's text
 * already states the percentage, so it adds no separate semantics.
 */
@Composable
internal fun FileProgressLine(fraction: Float, wanted: Boolean, modifier: Modifier = Modifier) {
    val value = safeProgressFraction(fraction)
    val fill = when {
        !wanted -> ChunksColors.Outline
        value >= 1f -> ChunksColors.Green
        else -> ChunksColors.Purple
    }
    androidx.compose.foundation.layout.Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp))
        .background(ChunksColors.Muted)) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(value).fillMaxHeight().background(fill))
    }
}

/**
 * Warning/error banner from the design sheet: dark card, tone-coloured
 * border and title, library status icon. Text carries the meaning; the
 * icon is decorative.
 */
@Composable
internal fun ChunksBanner(title: String, message: String, tone: ChunksTone, modifier: Modifier = Modifier) {
    val (icon, accent) = when (tone) {
        ChunksTone.ERROR -> R.drawable.chunks_ic_error to ChunksColors.Red
        // A good outcome must not wear the warning icon; the title carries it.
        ChunksTone.COMPLETE -> null to ChunksColors.Green
        else -> R.drawable.chunks_ic_warning to ChunksColors.Yellow
    }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ChunksColors.Surface)
            .border(2.dp, accent, RoundedCornerShape(16.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(32.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = accent, fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.titleSmall)
            Text(message, style = MaterialTheme.typography.bodySmall, color = ChunksColors.Text)
        }
    }
}

/**
 * Card for panels that hold interactive controls: dark surface with an
 * accent border. Default chip, button and error colours are tuned for dark
 * surfaces, so they stay readable here (on a filled accent card they fell
 * below 2:1).
 */
@Composable
internal fun AccentOutlineCard(accent: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.material3.Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = ChunksColors.Surface, contentColor = ChunksColors.Text),
        border = androidx.compose.foundation.BorderStroke(2.dp, accent)
    ) { content() }
}

/**
 * Which environment a list, review or confirmation belongs to (UNIFIED-ENVIRONMENTS):
 * "On this phone" or the exact server account and address. Text carries the
 * identity; the environment's persistent colour (name, border and icon) is a
 * second cue. With [editable], tapping it offers the palette and
 * "Restore automatic colour".
 */
@Composable
internal fun EnvironmentBadge(
    label: String, server: Boolean, modifier: Modifier = Modifier,
    environmentId: String? = null, editable: Boolean = false
) {
    val accent = environmentAccent(environmentId) ?: if (server) ChunksColors.Blue else ChunksColors.Cyan
    var picking by remember { mutableStateOf(false) }
    val clickable = if (editable && environmentId != null) Modifier.heightIn(min = 48.dp)
        .clickable(onClickLabel = "Change colour") { picking = true } else Modifier
    Row(modifier.clip(RoundedCornerShape(12.dp)).then(clickable).background(ChunksColors.SurfaceRaised)
            .border(2.dp, accent, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Library icons are drawn for light fills; tint to the badge accent on this dark chip.
        Image(painterResource(if (server) R.drawable.chunks_ic_server else R.drawable.chunks_ic_phone),
            contentDescription = null, modifier = Modifier.size(20.dp),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(accent))
        Text(label, style = MaterialTheme.typography.labelLarge, color = textSafe(accent, ChunksColors.SurfaceRaised))
    }
    if (picking && environmentId != null) EnvironmentColorPicker(label, environmentId) { picking = false }
}

/** The environment's saved (or newly auto-assigned) colour; null without an ID. */
@Composable
internal fun environmentAccent(environmentId: String?): Color? {
    if (environmentId == null) return null
    val context = LocalContext.current
    val revision = EnvironmentColorStore.revision
    return remember(environmentId, revision) {
        try { EnvironmentColorStore.of(context).colorFor(environmentId).color } catch (_: RuntimeException) { null }
    }
}

@Composable
internal fun EnvironmentColorPicker(label: String, environmentId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember { EnvironmentColorStore.of(context) }
    val current = remember(EnvironmentColorStore.revision) { store.saved(environmentId) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun apply(ok: Boolean) {
        if (ok) { EnvironmentColorStore.revision++; onDismiss() } else problem = "The colour could not be saved."
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Colour for $label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Shown wherever this environment is named. The name always stays visible.",
                    style = MaterialTheme.typography.bodySmall)
                EnvironmentColor.entries.forEach { option ->
                    val selected = current?.color == option
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable { apply(store.choose(environmentId, option)) }
                            .semantics { this.selected = selected; role = Role.RadioButton },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.compose.foundation.layout.Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))
                            .background(option.color))
                        Text(option.label + if (selected) " (current)" else "",
                            color = textSafe(option.color, MaterialTheme.colorScheme.surfaceContainerHigh))
                    }
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { apply(store.restoreAutomatic(environmentId)) },
                enabled = current?.manual == true) { Text("Restore automatic colour") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** "Server · user @ host/path" from an authenticated alias scope (endpoint + NUL + username); never a password. */
internal fun serverEnvironmentLabel(aliasScope: String): String {
    val endpoint = aliasScope.substringBefore('\u0000')
    val user = aliasScope.substringAfter('\u0000', "").trim()
    val address = RememberedRemoteProfileRoster.addressOf(endpoint)
    return "Server · " + (if (user.isEmpty()) address else "$user @ $address")
}

internal const val PHONE_ENVIRONMENT_LABEL = "On this phone"

/**
 * A slim leading rail in the environment's colour on a transfer card
 * (UNIFIED-ENVIRONMENTS). Decorative: the environment is always also named in
 * text nearby, and status keeps its own colours.
 */
internal fun Modifier.environmentRail(color: Color?): Modifier = if (color == null) this else drawBehind {
    drawRect(color, size = androidx.compose.ui.geometry.Size(6.dp.toPx(), size.height))
}
