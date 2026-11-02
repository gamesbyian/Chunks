package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.EnvironmentId

/**
 * Compact read-only header for a single authenticated server. The first
 * content below it can be a real transfer rather than an oversized welcome
 * panel. Identity is still explicit, with the persisted palette swatch and
 * name together, but raw credentials/RPC paths stay in connection settings.
 */
@Composable
internal fun CompactRemoteInventoryHeader(
    environmentLabel: String,
    environment: EnvironmentId?,
    loadedCount: Int,
    view: String,
    stale: Boolean,
    loading: Boolean,
    onAllEnvironments: () -> Unit
) {
    val countText = "$loadedCount " + if (loadedCount == 1) "transfer" else "transfers"
    val viewText = if (view == "main") "" else " · $view view"
    val freshness = when {
        stale -> " · last known"
        loading -> " · refreshing"
        else -> ""
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 380.dp || LocalDensity.current.fontScale >= 1.5f
        val label = "$environmentLabel · $countText$viewText$freshness"
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EnvironmentBadge(label, server = true, environment = environment,
                    modifier = Modifier.semantics { heading() })
                OutlinedButton(onClick = onAllEnvironments,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("All environments")
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EnvironmentBadge(label, server = true, environment = environment,
                    modifier = Modifier.weight(1f).semantics { heading() })
                OutlinedButton(onClick = onAllEnvironments,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("All")
                }
            }
        }
    }
}

/** Small in-app identity; the full illustrated branding belongs to onboarding. */
@Composable
internal fun CompactChunksHeader() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Chunks", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.semantics { heading() })
        Text(".", style = MaterialTheme.typography.titleLarge, color = ChunksColors.Pink)
    }
}
