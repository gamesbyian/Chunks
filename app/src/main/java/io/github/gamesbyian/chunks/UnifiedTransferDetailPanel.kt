package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.EnvironmentOperation
import io.github.gamesbyian.chunks.model.EnvironmentHue
import io.github.gamesbyian.chunks.rpc.RemoteStallEvidence
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
    capabilityCheckError: String? = null,
    onInspectStall: (() -> Unit)? = null,
    stallCheckInProgress: Boolean = false,
    stallReport: RemoteStallEvidence? = null,
    stallCheckError: String? = null,
    /** Exact owning environment's existing palette choice; its name remains the identity. */
    environmentHue: EnvironmentHue? = null
) {
    val hidden = detail.readState == TransferDetailReadState.LOCKED
    // Presentation state only. Never retain a mutation authority or stale command review.
    val technicalDetailsOpen = rememberSaveable(detail.key.environment.value, detail.key.infoHash) {
        mutableStateOf(false)
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Back to transfers")
        }
        Text("Transfer details", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        if (!hidden) detail.observation?.let { transfer ->
            Text(transfer.displayName, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Text((if (detail.readState == TransferDetailReadState.CURRENT) "" else "Last known: ") +
                transferAtAGlance(transfer), style = MaterialTheme.typography.titleSmall)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EnvironmentDot(environmentHue)
            // Running text, not a heading: the readable sans per ChunksTypography.
            Text("Environment: " + environmentLabel, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f))
        }
        if (!hidden) detail.observation?.let { TransferDetailFacts(it) }
        if (!hidden) detail.observation?.let { transfer ->
            TransferMediaDetailSection(transfer)
        }
        Text("Observation: " + detail.readState.explanation,
            style = MaterialTheme.typography.bodyMedium)
        if (!hidden && onOpenOwningList != null) {
            Text("To manage this download, open its own environment's transfer list.",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onOpenOwningList,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Open " + environmentLabel + " transfers")
            }
        }
        if (!hidden && onOpenOwningList == null && detail.observation != null) {
            Text("This transfer is listed here for reference. Sign in to its own environment " +
                "to change it.", style = MaterialTheme.typography.bodyMedium)
        }
        if (!hidden) {
            detail.observation?.let { transfer ->
                if (detail.readState != TransferDetailReadState.CURRENT) {
                    Text(
                        "Last-known transfer facts only. Status, progress and rates may have changed. " +
                            "Refresh the owning environment before reviewing an action.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
        if (!hidden && onInspectStall != null) {
            Text("Why is this transfer inactive?", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Text("Read-only check of this exact transfer on its signed-in server. " +
                "No Start, retry, file changes or cleanup will occur.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = onInspectStall,
                enabled = !stallCheckInProgress,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = if (stallCheckInProgress)
                        "Inspecting this transfer on $environmentLabel without changing it"
                        else "Inspect this transfer on $environmentLabel without changing it"
                    stateDescription = if (stallCheckInProgress) "In progress" else "Ready, read only"
                }
            ) {
                Text(if (stallCheckInProgress) "Inspecting exact transfer…" else "Inspect transfer state")
            }
            Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                stallCheckError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
                stallReport?.let { report ->
                    Text(report.explanation(), style = MaterialTheme.typography.bodyMedium)
                    Text("Server requested Start: ${report.started}; engine active: ${report.active}; " +
                        "torrent open: ${report.open}; metadata placeholder: " +
                        "${report.awaitingMetadata?.toString() ?: "unknown"}; " +
                        "hash checking: ${report.hashChecking?.toString() ?: "unknown"}; " +
                        "hash-check failure: ${report.hashCheckFailed?.toString() ?: "unknown"}; " +
                        "hashing phase: ${report.hashingPhase?.toString() ?: "unknown"}.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Server message classification: " + report.messageClass.name +
                        ". Raw messages and tracker details are withheld.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("This is a momentary engine observation, not proof of peer bytes or storage writes.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!hidden) {
            OutlinedButton(
                onClick = { technicalDetailsOpen.value = !technicalDetailsOpen.value },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = if (technicalDetailsOpen.value)
                            "Hide technical details for this transfer"
                        else "Show technical details for this transfer"
                        stateDescription = if (technicalDetailsOpen.value) "Expanded" else "Collapsed"
                    }
            ) {
                Text(if (technicalDetailsOpen.value) "Hide technical details" else "Show technical details")
            }
        }
        if (!hidden && technicalDetailsOpen.value) {
            Text("Exact infohash: " + detail.key.infoHash,
                style = MaterialTheme.typography.bodySmall)
            detail.observedAtEpochMillis?.let { whenObserved ->
                Text("Last observed: " + DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()
                ).format(Date(whenObserved)), style = MaterialTheme.typography.bodySmall)
            }
            Text("Capabilities in this environment", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Text("Read-only evidence. An advertised or built-in capability does not prove a command succeeded.",
                style = MaterialTheme.typography.bodySmall)
            if (onCheckCapabilities != null) {
                OutlinedButton(
                    onClick = onCheckCapabilities,
                    enabled = !capabilityCheckInProgress,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        contentDescription = if (capabilityCheckInProgress)
                            "Checking capabilities for $environmentLabel"
                        else "Check capabilities for $environmentLabel"
                        stateDescription = if (capabilityCheckInProgress) "In progress" else "Ready, read only"
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
        Text("Save folder reported by engine: " +
            (transfer.reportedSaveFolder ?: "not reported"),
            style = MaterialTheme.typography.bodyMedium)
        Text("A reported folder does not confirm those files exist, are accessible, or may be deleted.",
            style = MaterialTheme.typography.bodySmall)
        Text("Download rate: " + (transfer.downloadBytesPerSecond?.let {
            io.github.gamesbyian.chunks.model.Units.bytes(it) + "/s"
        } ?: "not reported"), style = MaterialTheme.typography.bodyMedium)
        Text("Upload rate: " + (transfer.uploadBytesPerSecond?.let {
            io.github.gamesbyian.chunks.model.Units.bytes(it) + "/s"
        } ?: "not reported"), style = MaterialTheme.typography.bodyMedium)
        Text("Peers: " + (transfer.peers?.toString() ?: "not reported"),
            style = MaterialTheme.typography.bodyMedium)
        Text("Share ratio: " + (transfer.ratioThousandths?.let {
            String.format(Locale.ROOT, "%.2f", it / 1_000.0)
        } ?: "not reported"), style = MaterialTheme.typography.bodyMedium)
        if (transfer.hasError) {
            Text("An error was reported. Inspect the owning engine for its cause.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * User-facing state is derived only from the same exact-environment observation
 * as the technical facts. Rates are reports, not verified peer-byte movement;
 * a started flag never establishes seeding or successful download.
 */
internal fun transferAtAGlance(transfer: UnifiedTransferObservation): String = when {
    transfer.hasError -> "Needs attention: an error was reported."
    transfer.metadataPending == true && transfer.started == false -> "Stopped; magnet metadata pending."
    transfer.metadataPending == true -> "Fetching magnet metadata; payload progress unknown."
    transfer.progressPpm == 1_000_000 && transfer.started == false ->
        "Download complete; stopped in the last report."
    transfer.started == false -> "Stopped in the last report."
    transfer.progressPpm == 1_000_000 && (transfer.uploadBytesPerSecond ?: 0L) > 0L ->
        "Download complete; upload activity reported."
    transfer.progressPpm == 1_000_000 ->
        "Download complete; sharing activity not confirmed."
    (transfer.downloadBytesPerSecond ?: 0L) > 0L -> "Download activity reported."
    transfer.started == true -> "Started; download activity not confirmed."
    else -> "Current activity unknown."
}
