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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
    onAllEnvironments: () -> Unit,
    /** Wall-clock time of the last successful list read, if any. */
    observedAtEpochMillis: Long? = null
) {
    val countText = "$loadedCount " + if (loadedCount == 1) "transfer" else "transfers"
    val viewText = if (view == "main") "" else " · $view view"
    // Only an out-of-date list needs its age; keep the clock ticking so
    // "2 min ago" does not silently stay "just now".
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (stale && observedAtEpochMillis != null) LaunchedEffect(observedAtEpochMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val freshness = remoteFreshnessText(stale, loading, observedAtEpochMillis, now)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The transfer-list gutters leave 376dp on the primary 412dp
        // local. It can hold a named environment badge and the All
        // environments button side by side at ordinary text sizes.
        // Preserve stacking at 320dp and when accessibility fonts enlarge.
        val stacked = maxWidth < 352.dp || LocalDensity.current.fontScale >= 1.5f
        val label = "$environmentLabel · $countText$viewText$freshness"
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EnvironmentBadge(label, server = true, environment = environment,
                    modifier = Modifier.semantics { heading() })
                OutlinedButton(onClick = onAllEnvironments,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("All environments…")
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
                    Text("All environments…")
                }
            }
        }
    }
}

/**
 * Honest list freshness. A stale list says how old its last successful read
 * is; a list that has never been read says so instead of implying an age.
 */
internal fun remoteFreshnessText(
    stale: Boolean, loading: Boolean, observedAtEpochMillis: Long?, nowEpochMillis: Long
): String = when {
    stale && observedAtEpochMillis == null -> " · last known, not yet read"
    stale -> " · last known, " + observedAgeText(nowEpochMillis - observedAtEpochMillis!!)
    loading -> " · refreshing"
    else -> ""
}

internal fun observedAgeText(ageMillis: Long): String {
    val minutes = (ageMillis.coerceAtLeast(0) / 60_000)
    return when {
        minutes < 1 -> "read just now"
        minutes < 60 -> "read $minutes min ago"
        minutes < 48 * 60 -> "read ${minutes / 60} h ago"
        else -> "read ${minutes / (24 * 60)} days ago"
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
