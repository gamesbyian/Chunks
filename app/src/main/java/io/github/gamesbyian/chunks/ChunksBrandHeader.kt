package io.github.gamesbyian.chunks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Native, scalable brand header inspired by the six owner-selected references.
 * No generated artwork is baked into the UI. Decorative blocks are silent to
 * TalkBack; the entire wordmark and tagline are one heading.
 */
@Composable
internal fun ChunksBrandHeader(modifier: Modifier = Modifier) {
    val ink = Color(0xFF262442)
    val paper = Color(0xFFFFF8EC)
    val lilac = Color(0xFFE6DAFF)
    val lime = Color(0xFFDDF1A7)
    val coral = Color(0xFFFFB69D)
    val blue = Color(0xFF90A7FF)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(ink)
            .semantics(mergeDescendants = true) {
                contentDescription = "chunks. Your downloads have places to be."
                heading()
            }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "chunks",
                    fontWeight = FontWeight.Black,
                    fontSize = 35.sp,
                    letterSpacing = (-2).sp,
                    lineHeight = 40.sp,
                    color = paper
                )
                Text(
                    ".",
                    fontWeight = FontWeight.Black,
                    fontSize = 35.sp,
                    lineHeight = 40.sp,
                    color = coral
                )
            }
            Text(
                "Your downloads have places to be.",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = lilac
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                BrandBlock(lime)
                BrandBlock(blue)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                BrandBlock(coral)
                BrandBlock(lilac)
            }
        }
    }
}

@Composable
private fun BrandBlock(color: Color) {
    Box(
        Modifier
            .size(18.dp)
            .background(color, RoundedCornerShape(5.dp))
    )
}
