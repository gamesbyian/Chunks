package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * One shared direction toggle for phone and server Transfers lists. The
 * visible text names the direction in words ("Largest first", "Z–A"), so it
 * does not depend on an arrow glyph or colour.
 */
@Composable
internal fun TransferSortDirectionButton(
    sortName: String,
    directionLabel: String,
    onReverse: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onReverse,
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "Sorted by $sortName, $directionLabel. Reverse order"
        }
    ) {
        Text("⇅ $directionLabel")
    }
}
