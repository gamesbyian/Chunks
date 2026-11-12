package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Actual entry points to the existing Add flows. The current view subset,
 * last browsed server and remembered-only credentials confer no Add authority.
 * The local route opens its live Add/list surface; the remote route exists
 * only while MainActivity owns an authenticated server session.
 */
@Composable
internal fun CombinedAddActions(
    serverLabel: String?,
    onOpenLocal: () -> Unit,
    onAddToServer: (() -> Unit)?,
    hasOtherSavedServers: Boolean = false,
    /** Saved palette colours, shown beside the text name; never colour alone. */
    localHue: io.github.gamesbyian.chunks.model.EnvironmentHue? = null,
    serverHue: io.github.gamesbyian.chunks.model.EnvironmentHue? = null,
    /** Never pass raw magnet, document URI or private tracker URLs into the selector. */
    pendingSharedMagnet: Boolean = false,
    /** The shared text source is an HTTPS .torrent URL, not a magnet. */
    pendingSharedLink: Boolean = false,
    pendingSharedTorrent: Boolean = false,
    readingSharedTorrent: Boolean = false,
    sharedTorrentError: String? = null
) {
    val sharedKind = when {
        pendingSharedTorrent -> ".torrent"
        pendingSharedLink -> "torrent link"
        pendingSharedMagnet -> "magnet"
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (sharedKind != null) "Choose a destination for the shared $sharedKind" else "Add a download",
            style = MaterialTheme.typography.titleMedium)
        if (sharedKind != null) Text(
            "A shared $sharedKind is ready. Choose where to review it. Nothing is added until you confirm.",
            style = MaterialTheme.typography.bodySmall
        )
        if (readingSharedTorrent) Text("Checking the shared .torrent on this device…",
            style = MaterialTheme.typography.bodySmall)
        if (sharedTorrentError != null) Text(sharedTorrentError,
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        // Always stack exact destinations: full server names (username, host,
        // numbered instance) must never be squeezed into a 412dp two-button
        // row or truncated into a misleading destination identity.
        OutlinedButton(onClick = onOpenLocal, enabled = !readingSharedTorrent,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = if (sharedKind != null) "Review shared $sharedKind on this device"
                    else "Add download on this device"
            }) { DestinationLabel(localHue,
                if (sharedKind != null) "Review on this device" else "Add on this device") }
        if (!serverLabel.isNullOrBlank() && onAddToServer != null) {
            OutlinedButton(onClick = onAddToServer, enabled = !readingSharedTorrent,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = if (sharedKind != null) "Review shared $sharedKind on $serverLabel"
                        else "Add download on $serverLabel"
                }) { DestinationLabel(serverHue,
                    if (sharedKind != null) "Review on $serverLabel" else "Add on $serverLabel") }
        }
        if (hasOtherSavedServers) Text(
            "Other saved servers need their own connection before adding.",
            style = MaterialTheme.typography.bodySmall
        )
        if (onAddToServer != null || hasOtherSavedServers) Text(
            "Choosing which downloads to view never changes the Add destination.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun DestinationLabel(hue: io.github.gamesbyian.chunks.model.EnvironmentHue?, text: String) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (hue != null) EnvironmentDot(hue)
        Text(text)
    }
}
