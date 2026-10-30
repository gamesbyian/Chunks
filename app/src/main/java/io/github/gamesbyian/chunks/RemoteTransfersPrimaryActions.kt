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
        val stacked = maxWidth < 380.dp || LocalDensity.current.fontScale >= 1.5f
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
