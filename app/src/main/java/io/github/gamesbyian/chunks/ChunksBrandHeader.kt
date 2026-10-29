package io.github.gamesbyian.chunks

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Native, scalable brand header. The only artwork is the owner-approved
 * launcher illustration (generated VectorDrawable of
 * docs/design/approved/chunks-app-icon-master.svg). It is silent to TalkBack;
 * the wordmark and tagline are one heading.
 */
@Composable
internal fun ChunksBrandHeader(modifier: Modifier = Modifier) {
    // At 200% text scaling the headline owns the width; the illustration
    // yields space rather than clipping the actual navigational identity.
    val showArtwork = LocalDensity.current.fontScale < 1.5f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(ChunksColors.Surface)
            .semantics(mergeDescendants = true) {
                contentDescription = "chunks. Your downloads have places to be."
                heading()
            }
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "Chunks",
                    fontFamily = ChunksDisplayFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 38.sp,
                    // Measured optical preset in chunks-font-metrics.json (800/display).
                    letterSpacing = (-0.0067).em,
                    lineHeight = 44.sp,
                    color = ChunksColors.Text
                )
                Text(
                    ".",
                    fontFamily = ChunksDisplayFamily,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 38.sp,
                    lineHeight = 44.sp,
                    color = ChunksColors.Pink
                )
            }
            Text(
                "Your downloads have places to be.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = ChunksColors.PurpleLight
            )
        }
        if (showArtwork) {
            Image(
                painter = painterResource(R.drawable.chunks_launcher_artwork),
                contentDescription = null,
                modifier = Modifier.size(84.dp)
            )
        }
    }
}
