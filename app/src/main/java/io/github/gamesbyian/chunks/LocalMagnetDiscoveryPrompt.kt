package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A deliberate alternative to the DHT switch above the Add form, only when
 * a trackerless magnet needs discovery. Never silently changes network
 * privacy, starts a torrent, or equates DHT enablement with peer availability.
 */
@Composable
internal fun LocalMagnetDiscoveryPrompt(
    magnet: String,
    dhtEnabled: Boolean,
    applying: Boolean,
    blocked: Boolean,
    onEnable: () -> Unit
) {
    val peers = LocalMagnetDiscoveryAdvice.directPeers(magnet)
    if (peers.isNotEmpty()) {
        Text("This magnet names a direct peer: " + peers.joinToString(", ") + ". Chunks " +
            "connects to it to fetch the metadata and pieces, and verifies every piece " +
            "against the torrent's own hashes. It may also find other peers.",
            style = MaterialTheme.typography.bodySmall)
    }
    if (!LocalMagnetDiscoveryAdvice.warn(magnet, dhtEnabled)) return
    Text("This magnet has no usable tracker address and DHT is off. " +
        "Chunks may be unable to find peers and fetch its metadata. " +
        "Enable DHT or use a .torrent file with tracker information. " +
        "Without metadata or peers, downloading cannot begin.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error)
    OutlinedButton(
        onClick = onEnable,
        enabled = !applying && !blocked,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    ) {
        Text(if (applying) "Enabling DHT…" else
            "Enable DHT to help this magnet find peers")
    }
    Text("Opt-in: DHT shares this phone's IP address with public DHT nodes. " +
        "This does not guarantee peers, metadata or download progress.",
        style = MaterialTheme.typography.bodySmall)
}
