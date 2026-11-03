package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Persistent, measured navigation beneath the authenticated server inventory.
 * The parent owns the actual Transfers/Add/Options callbacks; this UI cannot
 * choose another server or change the torrent. A two-row fallback preserves
 * readable labels at enlarged type instead of squeezing three actions into
 * equally narrow buttons. All touch targets stay at least 48dp.
 */
@Composable
internal fun AuthenticatedTransfersFooter(
    optionsOpen: Boolean,
    onTransfers: () -> Unit,
    onAdd: () -> Unit,
    onOptions: () -> Unit
) {
    Surface(color = ChunksColors.Surface) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 380.dp || LocalDensity.current.fontScale >= 1.5f
            if (stacked) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FooterTransfers(onTransfers, Modifier.weight(1f))
                        FooterAdd(onAdd, Modifier.weight(1f))
                    }
                    FooterOptions(optionsOpen, onOptions, Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FooterTransfers(onTransfers, Modifier.weight(1f))
                    FooterAdd(onAdd, Modifier.weight(1f))
                    FooterOptions(optionsOpen, onOptions, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FooterTransfers(onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Text("Transfers", maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FooterAdd(onClick: () -> Unit, modifier: Modifier) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Text("+ Add", maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FooterOptions(open: Boolean, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp)
            .semantics {
                contentDescription = "Options"
                stateDescription = if (open) "Expanded" else "Collapsed"
            }
    ) {
        Text("Options", maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
