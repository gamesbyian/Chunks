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
 * The phone route opens its live Add/list surface; the remote route exists
 * only while MainActivity owns an authenticated server session.
 */
@Composable
internal fun CombinedAddActions(
    serverLabel: String?,
    onOpenPhone: () -> Unit,
    onAddToServer: (() -> Unit)?,
    hasOtherSavedServers: Boolean = false,
    /** Saved palette colours, shown beside the text name; never colour alone. */
    phoneHue: io.github.gamesbyian.chunks.model.EnvironmentHue? = null,
    serverHue: io.github.gamesbyian.chunks.model.EnvironmentHue? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Add a download", style = MaterialTheme.typography.titleMedium)
        // Always stack exact destinations: full server names (username, host,
        // numbered instance) must never be squeezed into a 412dp two-button
        // row or truncated into a misleading destination identity.
        OutlinedButton(onClick = onOpenPhone,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = "Add download on this phone"
            }) { DestinationLabel(phoneHue, "Add on this phone") }
        if (!serverLabel.isNullOrBlank() && onAddToServer != null) {
            OutlinedButton(onClick = onAddToServer,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = "Add download on $serverLabel"
                }) { DestinationLabel(serverHue, "Add on $serverLabel") }
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
