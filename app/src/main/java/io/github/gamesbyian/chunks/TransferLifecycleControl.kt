package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.Units
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteTransferActivity
import io.github.gamesbyian.chunks.rpc.RemoteTrackerHealth
import io.github.gamesbyian.chunks.rpc.RemoteTrafficWindow
import io.github.gamesbyian.chunks.rpc.RemoteTrafficSample
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * No optimistic mutation. One explicitly confirmed start/stop per presentation.
 * The caller refreshes the server view after attempts, even if the response
 * is uncertain. The user must inspect current remote state before another.
 */
@Composable
fun TransferLifecycleControl(
    remote: RTorrentRemote,
    transfer: RemoteTransfer,
    snapshotStale: Boolean,
    onMutationStarted: () -> Boolean,
    onSettled: () -> Unit,
    snapshotRevision: Long = 0L,
    // Test seam. Production always sends one exact-hash RPC lifecycle request.
    performLifecycle: suspend (String, Boolean) -> LifecycleOutcome = remote::setStarted,
    observeActivity: suspend (String) -> RemoteTransferActivity = remote::readActivity
) {
    val validV1Hash = remember(transfer.hash) { Regex("[0-9a-fA-F]{40}").matches(transfer.hash) }
    var confirming by rememberSaveable(transfer.hash, transfer.name, transfer.directory) { mutableStateOf(false) }
    var attempted by rememberSaveable(transfer.hash, transfer.name, transfer.directory) { mutableStateOf(false) }
    // An attempt stays locked until a NEW authoritative post-command queue read.
    // The revision is not a Compose recomposition or filter/search change.
    var attemptedAtRevision by rememberSaveable(transfer.hash, transfer.name, transfer.directory) {
        mutableStateOf(-1L)
    }
    var confirmingAtRevision by rememberSaveable(transfer.hash, transfer.name, transfer.directory) {
        mutableStateOf(-1L)
    }
    var resumeInactive by rememberSaveable(transfer.hash, transfer.name, transfer.directory) {
        mutableStateOf(false)
    }
    var pending by remember(transfer.hash, transfer.name, transfer.directory) { mutableStateOf(false) }
    var feedback by remember(transfer.hash) { mutableStateOf<String?>(null) }
    var verified by rememberSaveable(transfer.hash, transfer.name, transfer.directory) { mutableStateOf(false) }
    var activity by remember(transfer.hash) { mutableStateOf<RemoteTransferActivity?>(null) }
    var activityAtRevision by remember(transfer.hash) { mutableStateOf(-1L) }
    var checkingActivity by remember(transfer.hash) { mutableStateOf(false) }
    var trackerHealth by remember(remote, transfer.hash) { mutableStateOf<RemoteTrackerHealth?>(null) }
    var checkingTrackers by remember(remote, transfer.hash) { mutableStateOf(false) }
    var trackerUnavailable by remember(remote, transfer.hash) { mutableStateOf(false) }

    var trafficWindow by remember(remote, transfer.hash) { mutableStateOf<RemoteTrafficWindow?>(null) }
    var watchingTraffic by remember(remote, transfer.hash) { mutableStateOf(false) }
    var trafficError by remember(remote, transfer.hash) { mutableStateOf<String?>(null) }
    var watchJob by remember(remote, transfer.hash) { mutableStateOf<Job?>(null) }

    val scope = rememberCoroutineScope()
    val requestedStarted = resumeInactive || !transfer.started
    val verb = if (resumeInactive) "Resume" else if (requestedStarted) "Start" else "Stop"
    val confirmationFresh = confirming && confirmingAtRevision == snapshotRevision
    val freshAfterAttempt = attempted && !pending && !snapshotStale &&
        attemptedAtRevision != snapshotRevision
    val canOfferAction = !pending && (!attempted || freshAfterAttempt)

    if (!validV1Hash) {
        Text("This transfer hash format is not yet supported for lifecycle controls.",
            style = MaterialTheme.typography.bodySmall)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!confirmationFresh && canOfferAction) {
            OutlinedButton(
                onClick = {
                    resumeInactive = false
                    confirmingAtRevision = snapshotRevision
                    confirming = true
                },
                enabled = !snapshotStale,
                modifier = Modifier.height(48.dp)
            ) { Text((if (transfer.started) "Stop" else "Start") + " transfer…") }
            // A torrent can have d.state=1 while d.is_active=0. Give a
            // deliberate, exact-hash Open/Start/Resume review only after a
            // CURRENT read-only activity report proves it is inactive.
            if (transfer.started && activity?.active == false &&
                activityAtRevision == snapshotRevision) {
                OutlinedButton(
                    onClick = {
                        resumeInactive = true
                        confirmingAtRevision = snapshotRevision
                        confirming = true
                    },
                    enabled = !snapshotStale,
                    modifier = Modifier.height(48.dp)
                ) { Text("Resume inactive transfer…") }
            }
        }
        if (snapshotStale) {
            Text("Refresh this stale server snapshot before changing a transfer.",
                style = MaterialTheme.typography.bodySmall)
        } else if (attempted && !freshAfterAttempt && !pending) {
            Text("This operation needs a new confirmed server snapshot before another Start or Stop.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (confirmationFresh) {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Confirm " + verb.lowercase() + " for this torrent",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                    Text(transfer.name, style = MaterialTheme.typography.bodyMedium)
                    Text("Exact infohash: " + transfer.hash,
                        style = MaterialTheme.typography.bodySmall)
                    Text("The server will change this transfer's running state. Chunks will read it back and will not retry automatically.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (!snapshotStale && confirmingAtRevision == snapshotRevision &&
                                    onMutationStarted()) {
                                confirming = false
                                resumeInactive = false
                                attempted = true
                                attemptedAtRevision = snapshotRevision
                                pending = true
                                scope.launch {
                                    try {
                                        val outcome = performLifecycle(transfer.hash, requestedStarted)
                                        verified = outcome is LifecycleOutcome.Verified
                                        feedback = when (outcome) {
                                            is LifecycleOutcome.Verified ->
                                                "rTorrent reports " + (if (outcome.started) "Start state set" else "stopped") +
                                                    ". That does not prove a peer connection or downloaded bytes."
                                            is LifecycleOutcome.Pending ->
                                                if (requestedStarted && outcome.observedStarted)
                                                    "rTorrent accepted Start, but active transfer state is not confirmed. It may be hashing, paused or queued. Inspect activity and server errors before any retry."
                                                else "Server reports " + (if (outcome.observedStarted) "started" else "stopped") +
                                                    ", not the requested state. Refresh before considering another action."
                                            is LifecycleOutcome.Uncertain ->
                                                "RPC outcome uncertain. The operation may already have happened. Refresh before considering another action."
                                        }
                                    } catch (_: Exception) {
                                        verified = false
                                        feedback = "Could not verify the operation. Inspect the latest server state before retrying."
                                    } finally {
                                        pending = false
                                        onSettled()
                                    }
                                    if (requestedStarted) {
                                        checkingActivity = true
                                        try {
                                            val fresh = observeActivity(transfer.hash)
                                            if (!snapshotStale) {
                                                activity = fresh
                                                activityAtRevision = snapshotRevision
                                            }
                                        } catch (_: Exception) { activity = null }
                                        finally { checkingActivity = false }
                                    }
                                }
                                } else {
                                    confirming = false
                                    // No command was sent, so do not manufacture an
                                    // attempted operation or a permanent UI lockout.
                                    feedback = "The server snapshot changed or another command is pending. Refresh before trying again."
                                }
                            },
                            enabled = !pending && !snapshotStale,
                            modifier = Modifier.height(48.dp)
                        ) { Text("Confirm " + verb.lowercase()) }
                        OutlinedButton(
                            onClick = { confirming = false },
                            enabled = !pending,
                            modifier = Modifier.height(48.dp)
                        ) { Text("Cancel") }
                    }
                }
            }
        }
        if (transfer.started || attempted) {
            OutlinedButton(
                onClick = {
                    checkingActivity = true
                    val observedRevision = snapshotRevision
                    scope.launch {
                        try {
                            val observed = observeActivity(transfer.hash)
                            if (!snapshotStale && observedRevision == snapshotRevision) {
                                activity = observed
                                activityAtRevision = observedRevision
                            }
                        } catch (_: Exception) { activity = null }
                        finally { checkingActivity = false }
                    }
                },
                enabled = !checkingActivity && !pending,
                modifier = Modifier.height(48.dp)
            ) { Text(if (checkingActivity) "Checking engine activity…" else "Diagnose download activity") }
            OutlinedButton(
                onClick = {
                    watchingTraffic = true
                    trafficWindow = null
                    trafficError = null
                    watchJob = scope.launch {
                        try {
                            val observations = ArrayList<RemoteTrafficSample>(3)
                            repeat(3) { index ->
                                if (index > 0) delay(3_000L)
                                observations.add(remote.readTrafficSample(transfer.hash))
                            }
                            trafficWindow = RemoteTrafficWindow.evaluate(observations)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            trafficError = "Could not obtain three stable exact-hash readings. No byte progress is inferred. Check server connectivity and retry read-only."
                        } finally {
                            watchingTraffic = false
                            watchJob = null
                        }
                    }
                },
                enabled = !watchingTraffic && !checkingActivity && !pending,
                modifier = Modifier.height(48.dp)
            ) { Text(if (watchingTraffic) "Measuring bytes…" else "Measure download progress (3 samples)") }
            if (watchingTraffic) {
                OutlinedButton(
                    onClick = {
                        watchJob?.cancel()
                        trafficError = "Measurement cancelled. No progress conclusion recorded."
                    },
                    modifier = Modifier.height(48.dp)
                ) { Text("Cancel measurement") }
            }
            trafficError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            trafficWindow?.let { evidence ->
                Text(evidence.summary(), style = MaterialTheme.typography.bodySmall)
                Text(
                    "Measured interval: " + (evidence.elapsedMillis / 1000.0) + " s" +
                        " · Received traffic: " + (evidence.receivedDelta?.let(Units::bytes) ?: "unknown") +
                        " · New verified bytes: " + (evidence.verifiedDelta?.let(Units::bytes) ?: "unknown") +
                        " · Max connected peers: " + (evidence.peakPeers?.toString() ?: "unknown"),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            activity?.let { report ->
                Text(report.summary(), style = MaterialTheme.typography.bodySmall)
                val peers = report.connectedPeers?.toString() ?: "unknown"
                val speed = report.downloadBytesPerSecond?.toString() ?: "unknown"
                Text("Connected peers: $peers · download bytes/s: $speed",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (activity?.connectedPeers == 0L) {
                OutlinedButton(
                    onClick = {
                        checkingTrackers = true
                        trackerHealth = null
                        trackerUnavailable = false
                        scope.launch {
                            try {
                                trackerHealth = remote.readTrackerHealth(transfer.hash)
                                trackerUnavailable = trackerHealth == null
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                trackerUnavailable = true
                            } finally {
                                checkingTrackers = false
                            }
                        }
                    },
                    enabled = !checkingTrackers && !pending,
                    modifier = Modifier.height(48.dp)
                ) { Text(if (checkingTrackers) "Checking trackers…" else "Diagnose tracker availability") }
                trackerHealth?.let {
                    Text(it.summary(), style = MaterialTheme.typography.bodySmall)
                    Text("Trackers: ${it.enabled} enabled of ${it.total} · recorded announce successes: " +
                        "${it.successfulAnnounces} · failures: ${it.failedAnnounces}" +
                        " · maximum peers from one announce: " +
                        (it.maxAnnouncedPeers?.toString() ?: "unknown"),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (trackerUnavailable) {
                    Text("This rTorrent gateway did not provide trustworthy tracker counters. " +
                        "Inspect the server's tracker view for details.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (attempted) {
            Card(colors = CardDefaults.cardColors(
                containerColor = if (verified) MaterialTheme.colorScheme.secondaryContainer
                                 else MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Lifecycle result", fontWeight = FontWeight.Bold)
                    Text(if (pending) "Checking the server's state…" else
                        feedback ?: "Result unavailable; inspect server state.")
                }
            }
        }
    }
}
