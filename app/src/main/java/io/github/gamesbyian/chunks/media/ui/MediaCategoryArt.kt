package io.github.gamesbyian.chunks.media.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gamesbyian.chunks.ChunksColors
import io.github.gamesbyian.chunks.media.MediaKind

/** Visual identity per category: accent and short label. Deterministic, offline, never a fake cover. */
internal data class MediaCategoryStyle(val accent: Color, val glyph: Color, val label: String)

internal fun mediaCategoryStyle(kind: MediaKind): MediaCategoryStyle {
    val dark = ChunksColors.OnAccent
    return when (kind) {
        MediaKind.MOVIE -> MediaCategoryStyle(ChunksColors.Pink, dark, "MOVIE")
        MediaKind.TV_SERIES, MediaKind.TV_SEASON, MediaKind.TV_EPISODE -> MediaCategoryStyle(ChunksColors.Purple, dark, "TV")
        MediaKind.VIDEO -> MediaCategoryStyle(ChunksColors.Blue, dark, "VIDEO")
        MediaKind.MUSIC_ALBUM -> MediaCategoryStyle(ChunksColors.Cyan, dark, "MUSIC")
        MediaKind.AUDIO -> MediaCategoryStyle(ChunksColors.Cyan, dark, "AUDIO")
        MediaKind.AUDIOBOOK -> MediaCategoryStyle(ChunksColors.Orange, dark, "AUDIOBOOK")
        MediaKind.BOOK -> MediaCategoryStyle(ChunksColors.Orange, dark, "BOOK")
        MediaKind.COMIC -> MediaCategoryStyle(ChunksColors.Yellow, dark, "COMIC")
        MediaKind.MAGAZINE -> MediaCategoryStyle(ChunksColors.Yellow, dark, "MAGAZINE")
        MediaKind.GAME -> MediaCategoryStyle(ChunksColors.Green, dark, "GAME")
        MediaKind.SOFTWARE -> MediaCategoryStyle(ChunksColors.Blue, dark, "APP")
        MediaKind.DISC_IMAGE -> MediaCategoryStyle(ChunksColors.Cyan, dark, "DISC")
        MediaKind.ARCHIVE -> MediaCategoryStyle(ChunksColors.Orange, dark, "ARCHIVE")
        MediaKind.DOCUMENT -> MediaCategoryStyle(ChunksColors.Blue, dark, "DOC")
        MediaKind.IMAGE -> MediaCategoryStyle(ChunksColors.Green, dark, "IMAGES")
        MediaKind.ADULT -> MediaCategoryStyle(ChunksColors.Muted, ChunksColors.TextSecondary, "PRIVATE")
        MediaKind.UNKNOWN -> MediaCategoryStyle(ChunksColors.SurfaceRaised, ChunksColors.Text, "FILES")
    }
}

/**
 * Square bundled category art. Decorative: callers give the transfer its own text label, so this
 * clears semantics by default. A label appears at ≥56dp for redundant, non-colour identification.
 */
@Composable
fun MediaCategoryTile(kind: MediaKind, size: Dp, modifier: Modifier = Modifier, showLabel: Boolean = size >= 56.dp) {
    val style = mediaCategoryStyle(kind)
    val shape = RoundedCornerShape(size * 0.18f)
    val top = lerp(style.accent, Color.White, 0.12f)
    val bottom = lerp(style.accent, ChunksColors.Background, 0.38f)
    BoxWithConstraints(modifier.size(size).clip(shape).background(Brush.linearGradient(listOf(top, bottom),
        start = Offset.Zero, end = Offset.Infinite)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawChunkMotif(style.glyph.copy(alpha = 0.10f))
            val glyphSide = this.size.minDimension * if (showLabel) 0.50f else 0.60f
            val origin = Offset((this.size.width - glyphSide) / 2, (this.size.height - glyphSide) / 2 - if (showLabel) this.size.height * 0.07f else 0f)
            drawGlyph(kind, origin, glyphSide, style.glyph)
        }
        if (showLabel) {
            // Decorative, redundant label sized to the tile (dp, not sp) and fitted to its length, so
            // 200% system text cannot clip it; the transfer's own name carries the scalable text.
            val density = LocalDensity.current
            val labelDp = minOf(size.value * 0.12f, size.value * 0.80f / (style.label.length * 0.72f)).coerceAtLeast(6f)
            Text(style.label, Modifier.align(Alignment.BottomCenter).padding(bottom = size * 0.08f),
                style = TextStyle(fontSize = with(density) { labelDp.dp.toSp() }, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = with(density) { (labelDp * 0.05f).dp.toSp() }, color = style.glyph), maxLines = 1, softWrap = false)
        }
    }
}

/** Chunks brand motif: a few offset blocks in the corner, very low contrast. */
private fun DrawScope.drawChunkMotif(color: Color) {
    val u = size.minDimension / 8
    listOf(Offset(5.6f, 0.5f), Offset(6.7f, 0.5f), Offset(6.7f, 1.6f), Offset(0.5f, 6.7f)).forEach { (x, y) ->
        drawRoundRect(color, Offset(x * u, y * u), Size(u * 0.85f, u * 0.85f), CornerRadius(u * 0.2f))
    }
}

/** Glyphs on a 24-unit grid with consistent 2-unit round strokes. */
private fun DrawScope.drawGlyph(kind: MediaKind, o: Offset, side: Float, c: Color) {
    val u = side / 24f
    fun p(x: Float, y: Float) = Offset(o.x + x * u, o.y + y * u)
    val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, p(x1, y1), p(x2, y2), 2f * u, StrokeCap.Round)
    fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Boolean = false) =
        drawRoundRect(c, p(x, y), Size(w * u, h * u), CornerRadius(r * u), style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke)
    fun circle(x: Float, y: Float, r: Float, fill: Boolean = false) =
        drawCircle(c, r * u, p(x, y), style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke)
    fun path(fill: Boolean = false, build: Path.() -> Unit) =
        drawPath(Path().apply(build), c, style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else stroke)
    fun Path.m(x: Float, y: Float) = moveTo(o.x + x * u, o.y + y * u)
    fun Path.l(x: Float, y: Float) = lineTo(o.x + x * u, o.y + y * u)

    when (kind) {
        MediaKind.MOVIE -> {
            rrect(3f, 9f, 18f, 12f, 2f)
            path { m(3f, 9f); l(4f, 4.5f); l(21f, 4.5f); l(21f, 9f) }
            line(8f, 4.5f, 6.5f, 9f); line(13f, 4.5f, 11.5f, 9f); line(18f, 4.5f, 16.5f, 9f)
        }
        MediaKind.TV_SERIES, MediaKind.TV_SEASON, MediaKind.TV_EPISODE -> {
            rrect(2.5f, 7f, 19f, 13f, 2.5f); line(9f, 2.5f, 12f, 6.5f); line(15f, 2.5f, 12f, 6.5f); line(8f, 22f, 16f, 22f)
        }
        MediaKind.VIDEO -> { rrect(2.5f, 4.5f, 19f, 15f, 3f); path(fill = true) { m(10f, 8.5f); l(16f, 12f); l(10f, 15.5f); close() } }
        MediaKind.MUSIC_ALBUM -> { circle(12f, 12f, 9.5f); circle(12f, 12f, 3f); circle(12f, 12f, 0.8f, fill = true) }
        MediaKind.AUDIO -> listOf(4f to 4f, 8f to 8f, 12f to 11f, 16f to 7f, 20f to 3f).forEach { (x, h) -> line(x, 12f - h, x, 12f + h) }
        MediaKind.AUDIOBOOK -> {
            path { m(4f, 15f); l(4f, 12f); cubicTo(o.x + 4f * u, o.y + 3f * u, o.x + 20f * u, o.y + 3f * u, o.x + 20f * u, o.y + 12f * u); l(20f, 15f) }
            rrect(3f, 14f, 4f, 7f, 1.5f, fill = true); rrect(17f, 14f, 4f, 7f, 1.5f, fill = true)
        }
        MediaKind.BOOK -> {
            path { m(12f, 6.5f); l(12f, 20f) }
            path { m(12f, 6.5f); cubicTo(o.x + 9f * u, o.y + 4.5f * u, o.x + 5f * u, o.y + 4.5f * u, o.x + 2.5f * u, o.y + 5.5f * u); l(2.5f, 19f)
                cubicTo(o.x + 5f * u, o.y + 18f * u, o.x + 9f * u, o.y + 18f * u, o.x + 12f * u, o.y + 20f * u) }
            path { m(12f, 6.5f); cubicTo(o.x + 15f * u, o.y + 4.5f * u, o.x + 19f * u, o.y + 4.5f * u, o.x + 21.5f * u, o.y + 5.5f * u); l(21.5f, 19f)
                cubicTo(o.x + 19f * u, o.y + 18f * u, o.x + 15f * u, o.y + 18f * u, o.x + 12f * u, o.y + 20f * u) }
        }
        MediaKind.COMIC -> {
            path { m(4f, 4f); l(20f, 4f); l(20f, 15f); l(11f, 15f); l(6f, 20f); l(7f, 15f); l(4f, 15f); close() }
            circle(8.5f, 9.5f, 1.1f, fill = true); circle(12f, 9.5f, 1.1f, fill = true); circle(15.5f, 9.5f, 1.1f, fill = true)
        }
        MediaKind.MAGAZINE -> { rrect(5f, 3f, 14f, 18f, 2f); rrect(8f, 6f, 8f, 5f, 1f, fill = true); line(8f, 14f, 16f, 14f); line(8f, 17.5f, 13f, 17.5f) }
        MediaKind.GAME -> {
            rrect(2.5f, 7f, 19f, 11f, 5.5f)
            line(6.5f, 12.5f, 10.5f, 12.5f); line(8.5f, 10.5f, 8.5f, 14.5f)
            circle(15.5f, 11.2f, 1.1f, fill = true); circle(17.8f, 13.6f, 1.1f, fill = true)
        }
        MediaKind.SOFTWARE -> { rrect(3f, 3f, 7.5f, 7.5f, 2f); rrect(13.5f, 3f, 7.5f, 7.5f, 2f); rrect(3f, 13.5f, 7.5f, 7.5f, 2f); rrect(13.5f, 13.5f, 7.5f, 7.5f, 2f, fill = true) }
        MediaKind.DISC_IMAGE -> {
            circle(12f, 12f, 9.5f); circle(12f, 12f, 2.2f)
            drawArc(c, -100f, 60f, false, p(5.5f, 5.5f), Size(13f * u, 13f * u), style = stroke)
        }
        MediaKind.ARCHIVE -> {
            rrect(3f, 7f, 18f, 14f, 2f); path { m(2.5f, 7f); l(5f, 3f); l(19f, 3f); l(21.5f, 7f) }
            listOf(9f, 12f, 15f).forEach { y -> rrect(11f, y - 0.9f, 2f, 1.8f, 0.5f, fill = true) }
        }
        MediaKind.DOCUMENT -> {
            path { m(5f, 2.5f); l(14.5f, 2.5f); l(19f, 7f); l(19f, 21.5f); l(5f, 21.5f); close() }
            path { m(14.5f, 2.5f); l(14.5f, 7f); l(19f, 7f) }
            line(8.5f, 11.5f, 15.5f, 11.5f); line(8.5f, 15f, 15.5f, 15f); line(8.5f, 18.5f, 12.5f, 18.5f)
        }
        MediaKind.IMAGE -> { rrect(2.5f, 4f, 19f, 16f, 2.5f); circle(8f, 9.5f, 1.8f, fill = true); path { m(3.5f, 18f); l(9f, 13f); l(12.5f, 16f); l(16f, 11.5f); l(21f, 17f) } }
        MediaKind.ADULT -> {
            path { m(12f, 2.5f); l(20f, 5.5f); l(20f, 11.5f); cubicTo(o.x + 20f * u, o.y + 16.5f * u, o.x + 16.5f * u, o.y + 20f * u, o.x + 12f * u, o.y + 21.5f * u)
                cubicTo(o.x + 7.5f * u, o.y + 20f * u, o.x + 4f * u, o.y + 16.5f * u, o.x + 4f * u, o.y + 11.5f * u); l(4f, 5.5f); close() }
            rrect(9f, 11f, 6f, 5f, 1f, fill = true); path { m(10f, 11f); l(10f, 9.5f); cubicTo(o.x + 10f * u, o.y + 7f * u, o.x + 14f * u, o.y + 7f * u, o.x + 14f * u, o.y + 9.5f * u); l(14f, 11f) }
        }
        MediaKind.UNKNOWN -> {
            rrect(3f, 3f, 8f, 8f, 2f, fill = true); rrect(13f, 3f, 8f, 8f, 2f); rrect(3f, 13f, 8f, 8f, 2f); rrect(13f, 13f, 8f, 8f, 2f, fill = true)
        }
    }
}
