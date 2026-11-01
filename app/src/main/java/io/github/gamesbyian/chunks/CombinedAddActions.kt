package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
    onAddToServer: (() -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Add a download", style = MaterialTheme.typography.titleMedium)
        // Always stack exact destinations: full server names (username, host,
        // numbered instance) must never be squeezed into a 412dp two-button
        // row or truncated into a misleading destination identity.
        OutlinedButton(onClick = onOpenPhone,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = "Add download on this phone"
            }) { Text("Add on this phone") }
        if (!serverLabel.isNullOrBlank() && onAddToServer != null) {
            OutlinedButton(onClick = onAddToServer,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = "Add download on $serverLabel"
                }) { Text("Add on $serverLabel") }
        }
        Text("Other saved servers need their own connection before adding. " +
            "Choosing which downloads to view never changes the Add destination.",
            style = MaterialTheme.typography.bodySmall)
    }
}
