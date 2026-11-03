package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
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
        remoteFilterEvidenceText(selected)?.let { meaning ->
            Text(meaning, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** These describe observed server state, not inferred causes or phone-engine state. */
internal fun remoteFilterEvidenceText(filter: TransferFilter): String? = when (filter) {
    TransferFilter.ALL -> null
    TransferFilter.ACTIVE ->
        "Start set means rTorrent reports this transfer as started. It does not promise active traffic."
    TransferFilter.TRANSFERRING ->
        "Traffic now requires an observed positive upload or download rate. Missing rates are unknown."
    TransferFilter.ZERO_DOWNLOAD ->
        "0 B/s down means an incomplete, started transfer with an observed zero download rate; it does not diagnose why."
    TransferFilter.COMPLETE ->
        "Complete means rTorrent reports a known size with all bytes downloaded; the transfer may still seed."
    TransferFilter.STOPPED ->
        "Stopped means rTorrent reports Start disabled. The downloaded files are unchanged."
    TransferFilter.NEEDS_SORTING ->
        "Needs sorting is a Chunks filename suggestion, not a category reported by rTorrent."
}
