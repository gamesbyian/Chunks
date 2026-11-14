package io.github.gamesbyian.chunks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * Search remains a real editable field; two 48dp tools replace the old stacked
 * full-width Filters and Batch controls. Only opens existing disclosure panels.
 * Neither toolbar action can run a batch operation or alter transfer authority.
 */
@Composable
internal fun TransfersInlineToolbar(
    search: String,
    onSearchChange: (String) -> Unit,
    filtersExpanded: Boolean,
    batchExpanded: Boolean,
    filtersActive: Boolean,
    onFilters: () -> Unit,
    onBatch: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ClearableTransferSearchField(
            value = search,
            onValueChange = onSearchChange,
            label = "Search names or hashes",
            modifier = Modifier.weight(1f)
        )
        OutlinedIconButton(
            onClick = onFilters,
            modifier = Modifier.size(48.dp)
                .testTag(ChunksSemantics.TRANSFERS_FILTER_SORT)
                .semantics {
                    contentDescription = "Filters and sort"
                    stateDescription = if (filtersExpanded) "Expanded"
                        else if (filtersActive) "Collapsed; filters or sorting active"
                        else "Collapsed"
                }
        ) { TransfersFilterSortGlyph(filtersActive || filtersExpanded) }
        OutlinedIconButton(
            onClick = onBatch,
            modifier = Modifier.size(48.dp)
                .testTag(ChunksSemantics.TRANSFERS_BATCH_TOOLS)
                .semantics {
                    contentDescription = "Batch tools"
                    stateDescription = if (batchExpanded) "Expanded" else "Collapsed"
                }
        ) { TransfersBatchGlyph(batchExpanded) }
    }
}

/** Small geometric glyphs drawn with theme ink. No text-scale or asset fallback needed. */
@Composable
private fun TransfersFilterSortGlyph(active: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        val width = 1.8f * unit
        for ((y, knob) in listOf(5f to 8f, 12f to 16f, 19f to 11f)) {
            drawLine(ink, Offset(3f * unit, y * unit),
                Offset(21f * unit, y * unit), strokeWidth = width, cap = StrokeCap.Round)
            drawCircle(ink, radius = 3f * unit, center = Offset(knob * unit, y * unit))
            drawCircle(surface, radius = 1.25f * unit,
                center = Offset(knob * unit, y * unit))
        }
        if (active) drawCircle(ink, radius = 2f * unit,
            center = Offset(21f * unit, 3f * unit))
    }
}

@Composable
private fun TransfersBatchGlyph(active: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        listOf(5f, 13f).forEach { y ->
            drawRoundRect(
                color = ink,
                topLeft = Offset(3f * unit, y * unit),
                size = Size(5f * unit, 5f * unit),
                cornerRadius = CornerRadius(unit),
                style = Stroke(width = 1.7f * unit)
            )
            drawLine(ink, Offset(11f * unit, (y + 2.5f) * unit),
                Offset(21f * unit, (y + 2.5f) * unit),
                strokeWidth = 1.8f * unit, cap = StrokeCap.Round)
        }
        if (active) {
            drawLine(ink, Offset(4f * unit, 7f * unit), Offset(5.5f * unit, 8.5f * unit),
                strokeWidth = 1.6f * unit, cap = StrokeCap.Round)
            drawLine(ink, Offset(5.5f * unit, 8.5f * unit), Offset(8f * unit, 5.5f * unit),
                strokeWidth = 1.6f * unit, cap = StrokeCap.Round)
        }
    }
}
