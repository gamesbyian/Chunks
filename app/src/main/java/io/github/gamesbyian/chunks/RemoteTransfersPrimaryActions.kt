package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Connected rTorrent primary navigation. At 320dp / 200% text, neither action
 * may be compressed to a fixed-height two-button row with clipped labels.
 * The callbacks preserve the owning server screen's existing Add/Refresh gates.
 */
@Composable
internal fun RemoteTransfersPrimaryActions(
    loading: Boolean,
    stale: Boolean,
    onAdd: () -> Unit,
    onRefresh: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // A 412dp phone has 376dp after the transfer list's 18dp side
        // gutters. That space fits two normal-text actions; the former 380dp
        // cutoff stacked them and spent an unnecessary extra 52dp row.
        // Keep genuinely narrow or >=1.5x text layouts stacked.
        val stacked = maxWidth < 340.dp || LocalDensity.current.fontScale >= 1.5f
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAdd, enabled = !loading && !stale,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("+ Add torrent")
                }
                OutlinedButton(onClick = onRefresh, enabled = !loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (loading) "Refreshing…" else "Refresh")
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAdd, enabled = !loading && !stale,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Text("+ Add torrent")
                }
                OutlinedButton(onClick = onRefresh, enabled = !loading,
                    modifier = Modifier.heightIn(min = 52.dp)) {
                    Text(if (loading) "Refreshing…" else "Refresh")
                }
            }
        }
    }
}

/**
 * Wide layouts show the transfer count beside the sort action; compact
 * layouts combine both into one labelled button to avoid another full-height
 * row at 320dp/200% text. Sorting is read-only UI state, not a server command.
 */
@Composable
internal fun RemoteTransfersSortSummary(
    count: Int,
    sortLabel: String,
    onCycleSort: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 380.dp || LocalDensity.current.fontScale >= 1.5f
        if (stacked) {
            // A separate count heading costs a whole row on the smallest screens.
            // Combine the read-only count and the existing sort action into one
            // full-width, wrapping, accessible control. No list state is changed.
            OutlinedButton(
                onClick = onCycleSort,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = "$count transfers shown. Sort by $sortLabel. Change sort"
                }
            ) {
                Text("$count shown · Sort: $sortLabel")
            }
        } else {
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("$count shown", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onCycleSort, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Sort: $sortLabel")
                }
            }
        }
    }
}
