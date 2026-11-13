package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Shared compact action layout. The caller still owns each action and its
 * enabled state. Keep two buttons across on narrow locals or enlarged text,
 * and three across where there is room. Further buttons wrap to another row
 * rather than becoming an unnecessarily tall one-button-per-row stack.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResponsiveActionGroup(
    modifier: Modifier = Modifier,
    /** 2 for actions whose labels are long (for example an environment name). */
    maxColumns: Int = 3,
    content: @Composable (Modifier) -> Unit
) {
    require(maxColumns in 2..3)
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = minOf(maxColumns, if (maxWidth < 380.dp || fontScale >= 1.5f) 2 else 3)
        val gap = 8.dp
        val buttonWidth = (maxWidth - gap * (columns - 1)) / columns
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            maxItemsInEachRow = columns
        ) {
            content(Modifier.width(buttonWidth).heightIn(min = 48.dp))
        }
    }
}
