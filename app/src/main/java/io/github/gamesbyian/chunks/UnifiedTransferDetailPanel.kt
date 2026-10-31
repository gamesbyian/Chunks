package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.EnvironmentOperation
import io.github.gamesbyian.chunks.model.TransferDetailReadState
import io.github.gamesbyian.chunks.model.UnifiedTransferDetail
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared read-only detail surface, independent of engine navigation and command
 * executors. The caller must resolve the exact key in its owning environment;
 * this panel never switches a connection or dispatches a transfer mutation.
 *
 * A vertical scroll and stacked 48dp-class controls keep all evidence reachable
 * on 320dp widths and with large text or keyboard/screen-reader navigation.
 */
@Composable
internal fun UnifiedTransferDetailPanel(
    detail: UnifiedTransferDetail,
    environmentLabel: String,
    onBack: () -> Unit,
    onOpenOwningList: (() -> Unit)? = null,
    onRefreshOwningList: (() -> Unit)? = null,
    /** Only an authenticated, matching environment adapter may offer this read-only probe. */
    onCheckCapabilities: (() -> Unit)? = null,
    capabilityCheckInProgress: Boolean = false,
    capabilityCheckError: String? = null
) {
    val hidden = detail.readState == TransferDetailReadState.LOCKED
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Back to transfers")
        }
        Text("Transfer details", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        Text("Environment: " + environmentLabel, style = MaterialTheme.typography.titleMedium)
        Text("Observation: " + detail.readState.explanation,
            style = MaterialTheme.typography.bodyMedium)
        if (!hidden) {
            Text("Exact infohash: " + detail.key.infoHash,
                style = MaterialTheme.typography.bodySmall)
            detail.observedAtEpochMillis?.let { whenObserved ->
                Text("Last observed: " + DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()
                ).format(Date(whenObserved)), style = MaterialTheme.typography.bodySmall)
            }
            detail.observation?.let { transfer ->
                if (detail.readState != TransferDetailReadState.CURRENT) {
                    Text(
                        "Last-known transfer facts only. Status, progress and rates may have changed. " +
                            "Refresh the owning environment before reviewing an action.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TransferDetailFacts(transfer)
            }
            if (detail.observation == null) {
                Text("No currently displayable details from this exact environment.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (onRefreshOwningList != null && !hidden) {
            OutlinedButton(onClick = onRefreshOwningList,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Refresh " + environmentLabel)
            }
        }
        if (onOpenOwningList != null && !hidden) {
            OutlinedButton(onClick = onOpenOwningList,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Open " + environmentLabel + " transfers")
            }
        }
        if (!hidden) {
            Text("Capabilities in this environment", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Text("Read-only evidence. An advertised or built-in capability does not prove a command succeeded.",
                style = MaterialTheme.typography.bodySmall)
            if (onCheckCapabilities != null) {
                OutlinedButton(
                    onClick = onCheckCapabilities,
                    enabled = !capabilityCheckInProgress,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        contentDescription = "Check capabilities for $environmentLabel"
                    }
                ) {
                    Text(if (capabilityCheckInProgress) "Checking capabilities…"
                        else "Check server capabilities")
                }
            }
            // The container stays mounted while a probe is in flight. TalkBack
            // receives the replacement evidence/failure; stale prior probe
            // results cannot be silently represented as this attempt's result.
            Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                capabilityCheckError?.let {
                    Text("Capability check could not be completed: $it",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
                detail.operations.filter { it.operation != EnvironmentOperation.LIST }.forEach { operation ->
                    Text(operation.operation.label + ": " + operation.explanation,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun TransferDetailFacts(transfer: UnifiedTransferObservation) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(transfer.displayName, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        if (transfer.originalName != transfer.displayName) {
            Text("Original torrent: " + transfer.originalName,
                style = MaterialTheme.typography.bodyMedium)
        }
        val state = when (transfer.started) {
            true -> "Start state set; peer activity has not been verified."
            false -> "Stopped in the last observed list."
            null -> "Start/Stop state unknown."
        }
        Text(state, style = MaterialTheme.typography.bodyMedium)
        val progress = transfer.progressPpm?.let {
            String.format(Locale.ROOT, "%.1f%%", it / 10_000.0)
        } ?: "unknown"
        Text("Progress: " + progress, style = MaterialTheme.typography.bodyMedium)
        Text("Size: " + (transfer.sizeBytes?.let { io.github.gamesbyian.chunks.model.Units.bytes(it) }
            ?: "unknown"), style = MaterialTheme.typography.bodyMedium)
        Text("Download rate: " + (transfer.downloadBytesPerSecond?.let {
            io.github.gamesbyian.chunks.model.Units.bytes(it) + "/s"
        } ?: "not reported"), style = MaterialTheme.typography.bodyMedium)
        Text("Upload rate: " + (transfer.uploadBytesPerSecond?.let {
            io.github.gamesbyian.chunks.model.Units.bytes(it) + "/s"
        } ?: "not reported"), style = MaterialTheme.typography.bodyMedium)
        if (transfer.hasError) {
            Text("An error was reported. Inspect the owning engine for its cause.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
        }
    }
}
