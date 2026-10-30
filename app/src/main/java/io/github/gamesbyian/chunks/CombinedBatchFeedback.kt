package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.UnifiedBatchLifecycle
import io.github.gamesbyian.chunks.model.describe
import kotlinx.coroutines.CancellationException

/**
 * A failure outside an individual adapter's guarded outcome path may happen
 * AFTER one or more commands were sent. Never call it a refusal or completed
 * success, and never retry the remaining targets from an uncertain boundary.
 */
internal suspend fun describeCombinedBatchSafely(
    label: (EnvironmentId) -> String,
    run: suspend () -> UnifiedBatchLifecycle.Report
): List<String> = try {
    run().describe(label)
} catch (cancel: CancellationException) {
    throw cancel
} catch (_: Exception) {
    listOf(
        "Batch outcome unknown. One or more changes may already have applied. " +
            "Refresh each owning environment and inspect its current transfer state " +
            "before considering another action. Nothing was retried automatically."
    )
}

/** One bounded polite TalkBack announcement, followed by readable per-source evidence. */
@Composable
internal fun CombinedBatchFeedback(running: Boolean, outcomes: List<String>) {
    if (running) {
        Text("Working… each environment's list will refresh afterwards.",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall)
    } else if (outcomes.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Batch result available. Review outcomes for each environment.",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall)
            outcomes.forEach { outcome ->
                Text(outcome, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
