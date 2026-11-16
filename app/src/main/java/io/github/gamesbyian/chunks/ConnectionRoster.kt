package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Saved configuration and a verified UI session are distinct facts; inventory reads grant no session. */
internal data class ConnectionRosterEntry(
    val id: String,
    val name: String,
    val connected: Boolean,
    val profile: RememberedRemoteProfileRoster.Profile?,
    val needsAttention: Boolean = false
)

internal fun unifiedConnectionRoster(
    profiles: List<RememberedRemoteProfileRoster.Profile>,
    verifiedProfileId: String?,
    verifiedName: String?,
    sessionVerified: Boolean = true
): List<ConnectionRosterEntry> {
    val saved = profiles.distinctBy { it.id }.map {
        ConnectionRosterEntry(it.id, it.label, verifiedName != null && sessionVerified && it.id == verifiedProfileId, it,
            needsAttention = verifiedName != null && !sessionVerified && it.id == verifiedProfileId)
    }
    val transient = if (verifiedName != null && saved.none { it.id == verifiedProfileId })
        listOf(ConnectionRosterEntry(verifiedProfileId ?: "transient-verified-session", verifiedName, sessionVerified, null, !sessionVerified))
        else emptyList()
    // Stable partition, preserving persisted order within each group; never sort by aliases/URLs.
    return (transient + saved).sortedByDescending { it.connected }
}

@Composable
internal fun ConnectionRosterCard(entry: ConnectionRosterEntry, loading: Boolean,
                                  onPrimary: () -> Unit, onManage: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("connection-profile-" + entry.id),
        colors = CardDefaults.cardColors(containerColor = ChunksColors.SurfaceRaised)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium)
            Text(if (entry.connected) "Connected" + (if (entry.profile == null) " · login not remembered" else "")
                else if (entry.needsAttention) "Needs attention · connection unverified"
                else "Disconnected · saved profile", style = MaterialTheme.typography.bodySmall)
            ResponsiveActionGroup(maxColumns = 2) { cell ->
                OutlinedButton(onClick = onPrimary, enabled = !loading, modifier = cell.semantics {
                    contentDescription = (if (entry.connected) "View transfers on " else "Connect to ") + entry.name
                }) { Text(if (entry.connected) "View transfers" else "Connect") }
                OutlinedButton(onClick = onManage, modifier = cell.semantics {
                    contentDescription = "Manage " + entry.name
                }) { Text("Manage") }
            }
        }
    }
}
