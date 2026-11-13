package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared action layout (NEXT-PUSH-UX-RECONCILIATION "horizontal space economy"):
 * ordinary actions sit two across, short ones three across, instead of a tall
 * bank of full-width buttons. Columns drop as the width or the font scale makes
 * a cell narrower than [minCellWidth], down to one, so labels are never clipped
 * at 200% text. Order, focus order and every action are preserved; each action
 * receives the cell [Modifier] and must apply it. Cells in a row share height.
 */
@Composable
internal fun ChunksActionGrid(
    actions: List<@Composable (Modifier) -> Unit>,
    modifier: Modifier = Modifier,
    columns: Int = 2,
    minCellWidth: Dp = 136.dp,
    spacing: Dp = 8.dp
) {
    require(columns in 1..3)
    if (actions.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val fit = actionGridColumns(maxWidth, minCellWidth * fontScale, spacing, columns)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
            actions.chunked(fit).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    row.forEach { action -> action(Modifier.weight(1f).fillMaxHeight()) }
                    repeat(fit - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Largest column count up to [preferred] whose cells are at least [minCell] wide. */
internal fun actionGridColumns(available: Dp, minCell: Dp, spacing: Dp, preferred: Int): Int {
    var count = preferred.coerceIn(1, 3)
    while (count > 1 && (available - spacing * (count - 1)) / count < minCell) count--
    return count
}
