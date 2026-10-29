package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Keeps review and navigation actions accessible at narrow widths and large
 * system text scales. The caller supplies buttons, while this component owns
 * only arrangement and minimum touch height. No action or selection state is
 * changed when it switches between row and column.
 */
@Composable
internal fun ResponsiveActionGroup(
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = maxWidth < 380.dp || fontScale >= 1.5f
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content(Modifier.fillMaxWidth().heightIn(min = 48.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                content(Modifier.heightIn(min = 48.dp))
            }
        }
    }
}
