package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * An already verified local .torrent draft may be redirected to an authenticated
 * server's separately privacy-gated Add REVIEW. This control never calls either
 * torrent engine, sends a network request, or touches downloaded payload files.
 * The parent must only enable it for a currently connected remote profile.
 */
@Composable
internal fun ReviewedTorrentTargetChooser(
    verifiedTorrentBytes: ByteArray,
    enabled: Boolean,
    onPrepareServerReview: (ByteArray) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Choose where to add this .torrent",
                style = MaterialTheme.typography.titleSmall)
            Text("You can use the phone destination below, or prepare this exact " +
                "verified .torrent for the connected rTorrent server. The server " +
                "will download its own payload: no downloaded files move. " +
                "Original tracker URLs may contain private passkeys. " +
                "Nothing is sent until you confirm the server Add.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = { onPrepareServerReview(verifiedTorrentBytes.copyOf()) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text("Review .torrent on connected server") }
        }
    }
}
