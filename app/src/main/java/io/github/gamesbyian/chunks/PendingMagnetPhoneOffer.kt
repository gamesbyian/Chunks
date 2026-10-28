package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A magnet shared into a disconnected Chunks app is still useful to the local
 * BitTorrent engine. Offer destination choice without displaying sensitive
 * magnet/tracker/passkey text or silently connecting to a server.
 */
@Composable
internal fun PendingMagnetPhoneOffer(
    hasPendingMagnet: Boolean,
    busyConnecting: Boolean,
    onReviewPhone: () -> Unit,
    onDiscard: () -> Unit
) {
    if (!hasPendingMagnet) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()) {
        Text("Magnet link received", style = MaterialTheme.typography.titleMedium)
        Text("Review it in the phone's torrent client without an rTorrent login, " +
            "or Connect to review it on your remote server. Chunks will never " +
            "start downloading just because another app shared a link.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = onReviewPhone,
            enabled = !busyConnecting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) { Text("Review magnet on this phone") }
        OutlinedButton(
            onClick = onDiscard,
            enabled = !busyConnecting,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text("Discard pending magnet") }
    }
}
