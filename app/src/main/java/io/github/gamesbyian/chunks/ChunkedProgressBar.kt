package io.github.gamesbyian.chunks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.ProgressChunkHistory
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Progress consists only of observed chronological growth bands. No imaginary
 * empty slots are rendered. Progress is a normalized counter, NOT torrent
 * piece placement; the native status and exact percentage remain independent.
 *
 * [historyKey] is a stable exact-environment + infohash identity. A null key
 * renders one honest baseline band, e.g. in previews or synthetic tests.
 * Unknown/offline/stale reads must pass [observeFresh] = false: they can display
 * the last known percentage but cannot write history or invent increments.
 */
@Composable
internal fun ChunkedProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    segments: Int = ProgressChunkHistory.MAX_BANDS,
    tone: ChunksTone = ChunksTone.ACTIVE,
    activeColor: Color = tone.color,
    trackColor: Color = ChunksColors.Muted,
    historyKey: String? = null,
    observeFresh: Boolean = true,
    observationEpochMillis: Long? = null
) {
    require(segments in 1..ProgressChunkHistory.MAX_BANDS)
    val value = safeProgressFraction(progress)
    val ppm = (value.toDouble() * 1_000_000).roundToInt().coerceIn(0, 1_000_000)
    val context = LocalContext.current
    val density = LocalDensity.current
    var widthPx by remember(historyKey) { mutableIntStateOf(0) }
    // New independent bands need room for a meaningful straight edge.
    val minimumPpm = if (widthPx == 0) 3_000 else
        ceil(3.0 * 1_000_000 / widthPx).toInt().coerceIn(1, 1_000_000)
    val history = historyKey?.let(ProgressChunkStore::current)
    val acceptedFresh = observeFresh && (historyKey == null ||
        !ProgressChunkStore.isOutOfOrder(historyKey, observationEpochMillis))
    val projection = if (acceptedFresh) (history ?: ProgressChunkHistory())
        .observe(ppm, minimumPpm)
    else history?.takeIf { it.totalPpm == ppm }
        ?: ProgressChunkHistory().observe(ppm, minimumPpm)

    LaunchedEffect(historyKey, ppm, minimumPpm, acceptedFresh, observationEpochMillis) {
        if (historyKey != null && acceptedFresh) {
            ProgressChunkStore.observe(context, historyKey, ppm, minimumPpm,
                observationEpochMillis)
        }
    }
    val minBandPx = with(density) { 4.dp.toPx() }
    val responsiveCap = if (widthPx == 0) segments
        else minOf(segments, (widthPx / minBandPx).toInt().coerceAtLeast(1))
    val visible = coalesceOldest(projection.paintedBands(), responsiveCap)
    val shape = RoundedCornerShape(3.dp)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp)
            .onSizeChanged { if (it.width != widthPx) widthPx = it.width }
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
            }
            .clip(shape)
            .border(1.dp, trackColor, shape)
    ) {
        // Fill the whole track first. Historical bands cover only known completion.
        drawRoundRect(color = ChunksColors.Trough, cornerRadius = CornerRadius(size.height * .22f))
        val completedPx = size.width * value
        if (completedPx > 0f) drawRoundRect(
            color = activeColor,
            size = Size(completedPx, size.height),
            cornerRadius = CornerRadius(minOf(size.height * .22f, completedPx * .22f))
        )
        var start = 0f
        visible.forEachIndexed { i, delta ->
            if (delta <= 0) return@forEachIndexed
            val remaining = (completedPx - start).coerceAtLeast(0f)
            val bandWidth = if (i == visible.lastIndex) remaining
                else (size.width * delta / 1_000_000f).coerceIn(0f, remaining)
            if (bandWidth > 0f) {
                val rounding = minOf(size.height * .22f, bandWidth * .22f)
                drawRoundRect(
                    color = if (i % 2 == 0) activeColor else lerp(activeColor, Color.White, .07f),
                    topLeft = Offset(start, 0f), size = Size(bandWidth, size.height),
                    cornerRadius = CornerRadius(rounding)
                )
                // Thin overprint rather than an empty gap: completed area stays true.
                if (i > 0) drawLine(
                    color = ChunksColors.Trough.copy(alpha = .55f),
                    start = Offset(start, size.height * .18f),
                    end = Offset(start, size.height * .82f),
                    strokeWidth = 1.dp.toPx()
                )
                start += bandWidth
            }
        }
    }
}

/** Re-render with fewer bands on narrow rows without changing persisted history. */
internal fun coalesceOldest(bands: List<Int>, maximum: Int): List<Int> {
    require(maximum in 1..ProgressChunkHistory.MAX_BANDS)
    if (bands.size <= maximum) return bands
    val result = bands.toMutableList()
    while (result.size > maximum) {
        val oldest = result.removeAt(0)
        result[0] += oldest
    }
    return result
}

internal fun safeProgressFraction(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
