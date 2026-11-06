package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A genuine empty inventory, with no connected or saved remote environment.
 * Filtering, privacy locking, stale remote inventory and saved disconnected
 * connections must never be mistaken for first launch.
 *
 * These are navigation links only. No engine, RPC, provider or mutation runs
 * until the user proceeds through the existing authorized Add/Connect flow.
 */
@Composable
internal fun NoTransfersWelcome(onAdd: () -> Unit, onConnections: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your transfers will appear here", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        Text("Start a download on this phone, or connect a remote BitTorrent client. " +
            "You can use Chunks with either, or both.",
            style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Add a torrent")
            }
            TextButton(onClick = onConnections, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Connections")
            }
        }
    }
}

/** An initial/stale/filtered empty list is not evidence of an unused setup. */
internal fun showPhoneFirstRunWelcome(
    inventoryConfirmed: Boolean,
    inventoryStale: Boolean,
    inventoryEmpty: Boolean,
    hasRemoteEnvironment: Boolean,
    search: String,
    addOpen: Boolean
): Boolean = inventoryConfirmed && !inventoryStale && inventoryEmpty &&
    !hasRemoteEnvironment && search.isBlank() && !addOpen
