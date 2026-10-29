package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.RemoteCapabilities
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import kotlinx.coroutines.launch

/** Read-only "what does this server support" check; sends no torrent command. */
@Composable
internal fun RemoteCapabilityPanel(remote: RTorrentRemote) {
    val scope = rememberCoroutineScope()
    var checking by remember(remote) { mutableStateOf(false) }
    var lines by remember(remote) { mutableStateOf<List<String>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = {
            checking = true
            scope.launch {
                lines = try {
                    val probe = remote.probe()
                    val graded = RemoteCapabilities.grade(probe)
                    listOf(RemoteCapabilities.summary(probe, graded)) + graded.map { g ->
                        capabilityEvidenceLabel(g)
                    }
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { listOf("The server did not answer the read-only capability check.") }
                checking = false
            }
        }, enabled = !checking, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (checking) "Checking server…" else "Check server capabilities")
        }
        lines?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Introspection is read-only evidence, not proof that a command is authorized or works. */
internal fun capabilityEvidenceLabel(graded: RemoteCapabilities.Graded): String = when (graded.status) {
    RemoteCapabilities.Status.ADVERTISED ->
        "Advertised, not yet verified: ${graded.feature.label}"
    RemoteCapabilities.Status.MISSING ->
        "Not advertised: ${graded.feature.label} (missing ${graded.missing.joinToString()})"
    RemoteCapabilities.Status.UNKNOWN ->
        "Unknown: ${graded.feature.label} (server did not expose a method list)"
}
