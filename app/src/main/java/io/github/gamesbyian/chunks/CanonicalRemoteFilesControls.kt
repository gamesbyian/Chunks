package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteFile
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * File reads in the canonical full Details must bind to the same authenticated
 * account and exact v1 job as its already-guarded Remove/Start/Stop. The UI's
 * source witness, privacy and complete main-inventory checks are additional
 * requirements supplied by the caller. This pure preflight grants no RPC.
 */
internal fun canonicalRemoteFilesEligible(
    remote: RTorrentRemote,
    transfer: RemoteTransfer,
    aliasScope: String,
    snapshotStale: Boolean,
    mainView: Boolean
): Boolean = !snapshotStale && canonicalRemoteFilesIdentityMatches(remote, transfer, aliasScope, mainView)

private val V1_HASH = Regex("[0-9a-fA-F]{40}")

/**
 * The exact account, complete main view and v1 job still match, independent of whether
 * the inventory is momentarily being re-read. A mismatch discards any displayed file list;
 * a merely stale inventory keeps it visible but read-only until the next accepted read.
 */
internal fun canonicalRemoteFilesIdentityMatches(
    remote: RTorrentRemote,
    transfer: RemoteTransfer,
    aliasScope: String,
    mainView: Boolean
): Boolean = mainView && remote.addAccountScope() == aliasScope &&
    transfer.hash.matches(V1_HASH)

/**
 * On-demand file/priorities and Finish in Order inside ONE canonical Details
 * screen. Calls the EXISTING guarded rTorrent file/policy components, never a
 * new Add coordinator or a legacy server-only list. A clicked disclosure alone
 * triggers only a bounded, read-only exact-hash file inventory. Nothing writes
 * without a separate explicit review and the parent's exact-session lease.
 */
@Composable
internal fun CanonicalRemoteFilesControls(
    remote: RTorrentRemote,
    transfer: RemoteTransfer,
    aliasScope: String,
    snapshotStale: Boolean,
    mainView: Boolean,
    snapshotRevision: Long,
    onMutationStarted: () -> Boolean,
    onSettled: () -> Unit,
    /**
     * The owner reports only a transient out-of-date read (an ordinary refresh). An offline,
     * locked, missing or no-longer-listed job never keeps a previously read file list.
     */
    retainDuringRefresh: Boolean = false
) {
    val identityMatches = canonicalRemoteFilesIdentityMatches(remote, transfer, aliasScope, mainView)
    val permitted = !snapshotStale && identityMatches
    var expanded by rememberSaveable(remote, aliasScope, transfer.hash) { mutableStateOf(false) }
    var refreshRevision by remember(remote, aliasScope, transfer.hash) { mutableIntStateOf(0) }
    // Keyed by exact identity: another account, job, name or Place never inherits this list.
    var files by remember(remote, aliasScope, transfer.hash, transfer.name, transfer.directory) {
        mutableStateOf<List<RemoteFile>?>(null)
    }
    var loading by remember(remote, aliasScope, transfer.hash) { mutableStateOf(false) }
    var problem by remember(remote, aliasScope, transfer.hash) { mutableStateOf<String?>(null) }

    // Each new accepted inventory revision re-reads this job's files (priority readback
    // after a change included). Like ruTorrent/Transdroid, the previous exact-job list stays
    // visible meanwhile, so page, search and selected file survive an ordinary refresh;
    // writes stay disabled until the fresh read lands. A canceled read may finish inside the
    // network transport, but it cannot publish under a new account, name, Place or gate.
    LaunchedEffect(remote, aliasScope, transfer.hash, transfer.name, transfer.directory,
        expanded, refreshRevision, snapshotRevision, permitted) {
        problem = null
        loading = false
        if (!expanded || !identityMatches || (!permitted && !retainDuringRefresh)) {
            files = null
            return@LaunchedEffect
        }
        if (!permitted) return@LaunchedEffect
        val previous = files
        loading = true
        try {
            val observed = withContext(Dispatchers.IO) {
                withTimeoutOrNull(15_000L) { remote.listFiles(transfer.hash) }
            }
            when {
                observed == null -> {
                    // A transient timeout keeps the previous exact-job list, read-only.
                    files = previous
                    problem = "Server file-list read timed out. No action was attempted." +
                        if (previous != null) " The list shown is the previous verified read." else ""
                }
                observed.size > 12_000 -> {
                    files = null
                    problem = "The server returned too many file records to present safely."
                }
                remote.addAccountScope() != aliasScope -> {
                    files = null
                    problem = "This connection changed while reading files. Reopen Details."
                }
                else -> files = observed
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            files = previous
            problem = "The authenticated server file list could not be read. No action was attempted." +
                if (previous != null) " The list shown is the previous verified read." else ""
        } finally {
            loading = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
        ) { Text(if (expanded) "Hide files & priorities" else "Files & priorities…") }
        if (expanded) {
            if (!identityMatches) {
                Text("Refresh the exact authenticated main server inventory before reading or changing file priorities.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                if (loading) Text("Reading this transfer's files from the verified server…",
                    style = MaterialTheme.typography.bodySmall)
                else if (!permitted) Text(if (files != null) "The server inventory is not current. The file " +
                    "list below is the last verified read; changes wait for a fresh read."
                    else "Waiting for a current server inventory before reading this transfer's files.",
                    style = MaterialTheme.typography.bodySmall)
                problem?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                files?.let { currentFiles ->
                    // Read-only while the inventory or this list is being re-read.
                    val readOnly = !permitted || loading || problem != null
                    TransferFilePager(
                        remote = remote, hash = transfer.hash, files = currentFiles,
                        snapshotStale = readOnly, mainView = mainView,
                        onMutationStarted = onMutationStarted, onSettled = onSettled)
                    RemoteFinishInOrderPanel(
                        remote = remote, transfer = transfer, scope = aliasScope,
                        files = currentFiles, stale = readOnly,
                        onMutationStarted = onMutationStarted, onSettled = onSettled)
                }
                if (!loading && permitted) OutlinedButton(
                    onClick = { refreshRevision++ },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("Refresh this transfer's file list") }
            }
        }
    }
}
