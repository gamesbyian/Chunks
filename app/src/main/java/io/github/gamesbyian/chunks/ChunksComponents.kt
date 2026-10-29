package io.github.gamesbyian.chunks

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
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
    @DrawableRes icon: Int? = null
) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = ChunksColors.OnAccent),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) Image(painterResource(icon), contentDescription = null,
                modifier = Modifier.size(28.dp).alpha(if (enabled) 1f else 0.38f))
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center)
        }
    }
}
