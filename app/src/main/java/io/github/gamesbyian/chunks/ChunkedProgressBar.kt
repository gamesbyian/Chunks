package io.github.gamesbyian.chunks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Chunked visual progress with continuous accuracy.
 *
 * Each segment can be partially filled. No whole chunk is counted complete
 * until its actual range is complete, and the root exposes one continuous
 * accessibility progress value (not a misleading rounded chunk count).
 * This is display-only and deliberately has no animation.
 */
@Composable
internal fun ChunkedProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    segments: Int = 12,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    require(segments in 1..64)
    val value = safeProgressFraction(progress)
    val shape = RoundedCornerShape(3.dp)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
        },
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(segments) { index ->
            Box(
                Modifier.weight(1f).height(12.dp).clip(shape).background(trackColor)
            ) {
                Box(
                    Modifier.fillMaxWidth(chunkFillFraction(value, index, segments))
                        .fillMaxHeight().background(activeColor)
                )
            }
        }
    }
}

internal fun safeProgressFraction(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

/** Exact fractional fill for an individual chunk, including its partial remainder. */
internal fun chunkFillFraction(progress: Float, index: Int, segments: Int): Float {
    require(segments in 1..64 && index in 0 until segments)
    return (safeProgressFraction(progress) * segments - index).coerceIn(0f, 1f)
}
