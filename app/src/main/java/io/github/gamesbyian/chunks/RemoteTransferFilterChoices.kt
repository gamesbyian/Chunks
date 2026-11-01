package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.TransferFilter

/**
 * The primary rTorrent queue filters, using the same existing TransferCatalog
 * choice model as before. No secondary catalog or silent environment switch.
 *
 * All options are reachable by ordinary vertical scroll, touch, keyboard or
 * TalkBack at 320dp / 200% text, without knowing the horizontal swipe gesture.
 */
@Composable
internal fun RemoteTransferFilterChoices(
    selected: TransferFilter,
    onChoose: (TransferFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TransferFilter.entries.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { if (selected != option) onChoose(option) },
                label = { Text(option.label) },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
    }
}
