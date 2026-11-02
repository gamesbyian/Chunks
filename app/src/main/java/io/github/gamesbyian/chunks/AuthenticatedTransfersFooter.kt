package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Connected remote list: permanent three-action destination strip. Uses
 * the owning screen's existing callbacks and does not create a second Add,
 * navigation or command authority. Every control is 48dp minimum.
 */
@Composable
internal fun AuthenticatedTransfersFooter(
    optionsOpen: Boolean,
    onTransfers: () -> Unit,
    onAdd: () -> Unit,
    onOptions: () -> Unit
) {
    Surface(color = ChunksColors.Surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onTransfers,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) { Text("Transfers", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Button(
                onClick = onAdd,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            ) { Text("+ Add", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            OutlinedButton(
                onClick = onOptions,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = "Options"
                        stateDescription = if (optionsOpen) "Expanded" else "Collapsed"
                    }
            ) { Text("Options", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
