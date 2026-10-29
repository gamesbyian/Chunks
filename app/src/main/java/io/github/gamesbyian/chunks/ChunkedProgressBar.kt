package io.github.gamesbyian.chunks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
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
 *
 * Styled after the UI design sheet: chunky rounded segments in a dark
 * trough, coloured by the transfer's [tone]. The tone never replaces the
 * transfer's text status.
 */
@Composable
internal fun ChunkedProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    segments: Int = 10,
    tone: ChunksTone = ChunksTone.ACTIVE,
    activeColor: Color = tone.color,
    trackColor: Color = ChunksColors.Muted
) {
    require(segments in 1..64)
    val value = safeProgressFraction(progress)
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
            }
            .clip(RoundedCornerShape(10.dp))
            .background(ChunksColors.Trough)
            .border(2.dp, ChunksColors.Muted, RoundedCornerShape(10.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(segments) { index ->
            Box(
                Modifier.weight(1f).height(14.dp).clip(shape).background(trackColor)
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
