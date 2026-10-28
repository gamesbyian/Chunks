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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.RemoteSmartConnect

/** What the Connect screen knows about the current or last discovery run. */
internal data class ConnectionDiagnostics(
    /** e.g. "Checking 2 of 4: SeedHost ruTorrent (/rutorrent/…)"; null when idle. */
    val progress: String? = null,
    val attempts: List<RemoteSmartConnect.Attempt> = emptyList(),
    val advice: List<String> = emptyList()
) {
    companion object {
        fun progress(index: Int, total: Int, candidate: RemoteSmartConnect.Candidate): String {
            val path = try { java.net.URI(candidate.endpoint).rawPath.orEmpty().ifEmpty { "/" } }
                catch (_: Exception) { "/" }
            return "Checking $index of $total: ${candidate.description} ($path)"
        }
    }
}

/**
 * Read-only connection progress, Cancel, and what was tried with targeted
 * next steps. Paths only: never hostnames with credentials, passwords or
 * server replies. Cancelling is safe because discovery never mutates.
 */
@Composable
internal fun ConnectionDiagnosticsPanel(
    diagnostics: ConnectionDiagnostics,
    loading: Boolean,
    onCancel: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (loading) {
            diagnostics.progress?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Cancel connection check")
            }
        }
        if (!loading && diagnostics.attempts.isNotEmpty()) {
            Text("What Chunks tried", style = MaterialTheme.typography.titleSmall)
            diagnostics.attempts.forEach { attempt ->
                Text("• ${attempt.description} (${attempt.path}): ${attempt.outcome}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!loading && diagnostics.advice.isNotEmpty()) {
            Text("Try next", style = MaterialTheme.typography.titleSmall)
            diagnostics.advice.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
