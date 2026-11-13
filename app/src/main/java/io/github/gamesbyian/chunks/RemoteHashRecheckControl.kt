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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteHashRecheckObservation
import io.github.gamesbyian.chunks.rpc.RemoteHashRecheckOutcome
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import io.github.gamesbyian.chunks.rpc.RpcHttpStatusException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** The existing exact-target scheduling command, shared by All and server Details.
 * A scheduling ACK or an idle sample never certifies data. Recovery only reads;
 * it cannot silently schedule another check or issue Start/Stop. */
@Composable
internal fun RemoteHashRecheckControl(
    remote: RTorrentRemote,
    transfer: RemoteTransfer,
    aliasScope: String,
    snapshotStale: Boolean,
    mainView: Boolean,
    onMutationStarted: () -> Boolean,
    onSettled: () -> Unit,
    request: suspend (String, String, String) -> RemoteHashRecheckOutcome = remote::requestHashRecheck,
    observe: suspend (String, String, String) -> RemoteHashRecheckObservation = remote::observeHashRecheck
) {
    val eligible = canonicalRemoteFilesEligible(remote, transfer, aliasScope, snapshotStale, mainView) &&
        transfer.name.isNotBlank() && transfer.name.length <= 2048 &&
        transfer.directory.startsWith('/') && transfer.directory.length <= 4096
    // Save only a digest of account/hash/name/Place, never private review text.
    val identity = remember(remote, aliasScope, transfer.hash, transfer.name, transfer.directory) {
        val exact = listOf(remote.addAccountScope(), aliasScope, transfer.hash.uppercase(),
            transfer.name, transfer.directory).joinToString("") { "${it.length}:$it" }
        MessageDigest.getInstance("SHA-256").digest(exact.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    val latestIdentity = rememberUpdatedState(identity)
    var attemptedIdentity by rememberSaveable { mutableStateOf<String?>(null) }
    // Result prose is fixed and contains no server-returned strings.
    var feedback by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingIdentity by remember { mutableStateOf<String?>(null) }
    var readingIdentity by remember { mutableStateOf<String?>(null) }
    var idleIdentity by remember { mutableStateOf<String?>(null) }
    var refusal by remember(identity) { mutableStateOf<String?>(null) }
    var nextGeneration by remember { mutableLongStateOf(0L) }
    var attemptGeneration by remember { mutableLongStateOf(0L) }
    var pendingGeneration by remember { mutableLongStateOf(0L) }
    var readingGeneration by remember { mutableLongStateOf(0L) }
    val pending = pendingIdentity == identity
    val reading = readingIdentity == identity
    val attempted = attemptedIdentity == identity
    val scope = rememberCoroutineScope()
    fun ownsResult(ticket: String, generation: Long) = latestIdentity.value == ticket &&
        attemptedIdentity == ticket && attemptGeneration == generation
    fun ownsRead(ticket: String, generation: Long, readGeneration: Long) =
        ownsResult(ticket, generation) && readingIdentity == ticket && readingGeneration == readGeneration

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!attempted) {
            OutlinedButton(
                onClick = {
                    if (eligible && pendingIdentity != identity && readingIdentity != identity &&
                        attemptedIdentity != identity && onMutationStarted()) {
                        val ticket = identity
                        val generation = ++nextGeneration
                        attemptGeneration = generation
                        pendingGeneration = generation
                        attemptedIdentity = ticket
                        idleIdentity = null
                        refusal = null
                        // Pending is deliberately not persisted as a current
                        // result. After process restoration only the attempt
                        // latch survives until a new read establishes status.
                        feedback = null
                        pendingIdentity = ticket
                        scope.launch {
                            try {
                                val result = withTimeout(30_000) {
                                    request(transfer.hash, transfer.name, transfer.directory)
                                }
                                if (ownsResult(ticket, generation)) feedback = when (result) {
                                    is RemoteHashRecheckOutcome.AlreadyChecking ->
                                        "The server reported this exact transfer already checking. No new Recheck was scheduled."
                                    is RemoteHashRecheckOutcome.AlreadyQueued ->
                                        "The server reported a pending hash check for this exact transfer. No new Recheck was scheduled."
                                    is RemoteHashRecheckOutcome.HashingObserved ->
                                        "Hash checking observed for this exact transfer. Completion and file integrity are not yet verified."
                                    is RemoteHashRecheckOutcome.SubmittedUnobserved ->
                                        "Recheck acknowledged; checking was not observed. It may be queued or already finished. File integrity is not verified."
                                    is RemoteHashRecheckOutcome.Uncertain ->
                                        "Recheck outcome uncertain. The server may have scheduled it. Check status; no request will be repeated."
                                }
                            } catch (cancel: CancellationException) {
                                if (ownsResult(ticket, generation))
                                    feedback = "Recheck was interrupted or timed out. Check status; no request will be repeated."
                                throw cancel
                            } catch (failure: Exception) {
                                if (ownsResult(ticket, generation)) feedback =
                                    if (failure is RpcHttpStatusException && failure.status in setOf(401, 403, 407))
                                        "This account refused Recheck preflight access. Reconnect and review its permissions. No Recheck command was sent."
                                    else "This exact transfer's preflight could not be verified. Refresh its name and location. No Recheck command was sent."
                            } finally {
                                if (pendingIdentity == ticket && pendingGeneration == generation) pendingIdentity = null
                                onSettled()
                            }
                        }
                    } else if (eligible && pendingIdentity != identity && readingIdentity != identity &&
                        attemptedIdentity != identity) {
                        refusal = "Recheck was not submitted. Refresh this server and wait for its current operation to finish."
                    }
                },
                enabled = eligible && !pending && !reading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag(ChunksSemantics.DETAILS_REMOTE_RECHECK)
            ) { Text("Recheck files") }
            Text("Ask this server to hash the transfer's existing files. rTorrent may pause activity while checking; this does not repair storage access.",
                style = MaterialTheme.typography.bodySmall)
            refusal?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (!eligible) Text(when {
                snapshotStale -> "Refresh this stale transfer before Recheck."
                !mainView || aliasScope != remote.addAccountScope() ->
                    "Recheck requires the exact authenticated account and its main server view."
                !transfer.hash.matches(Regex("[0-9a-fA-F]{40}")) ->
                    "Recheck is available only for an exact v1 torrent identity."
                !transfer.directory.startsWith('/') ->
                    "The server has not exposed an absolute location for this transfer. Inspect its folder in ruTorrent before Recheck."
                else -> "This transfer has no usable exact name or location for Recheck."
            }, style = MaterialTheme.typography.bodySmall)
        } else {
            Text(if (pending) "Recheck request in progress." else feedback ?:
                "An earlier Recheck may have reached the server. Inspect its current status.",
                modifier = Modifier.testTag(ChunksSemantics.REMOTE_RECHECK_STATUS).semantics {
                    liveRegion = LiveRegionMode.Polite
                })
            OutlinedButton(
                onClick = {
                    if (eligible && pendingIdentity != identity && readingIdentity != identity &&
                        attemptedIdentity == identity) {
                        val ticket = identity
                        val generation = attemptGeneration
                        val readGeneration = ++nextGeneration
                        readingGeneration = readGeneration
                        idleIdentity = null
                        readingIdentity = ticket
                        scope.launch {
                            try {
                                val result = withTimeout(15_000) {
                                    observe(transfer.hash, transfer.name, transfer.directory)
                                }
                                if (ownsRead(ticket, generation, readGeneration)) {
                                    idleIdentity = ticket.takeIf { result == RemoteHashRecheckObservation.NotChecking }
                                    feedback = when (result) {
                                        RemoteHashRecheckObservation.Checking ->
                                            "Last read: this exact transfer was hash checking. File integrity is not yet verified."
                                        RemoteHashRecheckObservation.Queued ->
                                            "Last read: rTorrent reported a pending hash check. Active checking was not observed."
                                        RemoteHashRecheckObservation.NotChecking ->
                                            "Last read: this exact transfer was neither checking nor queued. This does not prove the request completed or that files are intact."
                                        RemoteHashRecheckObservation.Changed ->
                                            "The transfer identity or location changed. Reopen its current Details before another action."
                                        RemoteHashRecheckObservation.Unavailable ->
                                            "The exact transfer status could not be read. Reconnect and refresh; no Recheck was repeated."
                                        RemoteHashRecheckObservation.AuthenticationRequired ->
                                            "This exact account requires authentication. Reconnect before checking status; no Recheck was repeated."
                                    }
                                }
                            } catch (_: TimeoutCancellationException) {
                                if (ownsRead(ticket, generation, readGeneration))
                                    feedback = "The status read timed out. The earlier result is not a current observation. No Recheck was repeated."
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                if (ownsRead(ticket, generation, readGeneration))
                                    feedback = "The exact transfer status could not be read. No Recheck was repeated."
                            } finally {
                                if (readingIdentity == ticket && readingGeneration == readGeneration) readingIdentity = null
                            }
                        }
                    }
                },
                enabled = eligible && !pending && !reading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text(if (reading) "Reading Recheck status…" else "Check Recheck status (read-only)") }
            if (idleIdentity == identity) {
                OutlinedButton(
                    onClick = {
                        if (eligible && pendingIdentity != identity && readingIdentity != identity &&
                            idleIdentity == identity && ownsResult(identity, attemptGeneration)) {
                            // A new explicit request still runs the RPC's fresh
                            // exact-identity and nonqueued preflight. Never resubmit here.
                            attemptedIdentity = null
                            feedback = null
                            idleIdentity = null
                        }
                    },
                    enabled = eligible && !pending && !reading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("Review a new Recheck") }
            }
        }
    }
}
