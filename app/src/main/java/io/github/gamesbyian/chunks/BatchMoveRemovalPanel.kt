package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
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
import io.github.gamesbyian.chunks.model.BatchMoveRemoval
import io.github.gamesbyian.chunks.model.TransferMove
import kotlinx.coroutines.launch

/**
 * Batch step 2 of Move Transfer to server: the phone jobs whose new server
 * job is already verified, with one separately confirmed removal of only
 * those original jobs. Files stay; each item re-checks the server first.
 */
@Composable
internal fun BatchMoveRemovalPanel(
    pending: List<TransferMove.Record>,
    /** Opaque exact endpoint+account; never a URL or host display label. */
    serverScopeDigest: String,
    serverLabel: String,
    busy: Boolean,
    observeTarget: suspend (String) -> TransferMove.Observation,
    removeSource: suspend (String) -> Unit,
    observeSource: suspend (String) -> TransferMove.Observation,
    save: (TransferMove.Record) -> Boolean,
    forget: (TransferMove.Record) -> Boolean,
    onFinished: (String) -> Unit,
    /** Where the original jobs live, for the confirmation text. */
    sourceLabel: String = "phone",
    /** Remote mutation gate for server-side removals; false means try later. */
    onStarted: () -> Boolean = { true },
    onSettled: () -> Unit = {}
) {
    if (pending.size < 2) return   // a single move is finished from its own card
    val scope = rememberCoroutineScope()
    // A new account or a different pending set cannot inherit a prior
    // confirmation. The job list is independently keyed by server scope.
    var confirming by remember(serverScopeDigest, pending) { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val exactScope = BatchMoveRemoval.matchesServerScope(pending, serverScopeDigest)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${pending.size} transfers are now on $serverLabel. Their original $sourceLabel jobs are still here.",
            style = MaterialTheme.typography.bodyMedium)
        if (!confirming) {
            Button(onClick = { confirming = true }, enabled = !busy && !working && exactScope,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Remove ${pending.size} original $sourceLabel jobs…")
            }
        } else {
            Text("Remove these ${pending.size} original torrent jobs from the $sourceLabel? Downloaded files stay " +
                "on the $sourceLabel and still use its storage; it stops sharing them. Each new job on " +
                "$serverLabel is checked again first, and Chunks stops at the first removal it cannot confirm.",
                style = MaterialTheme.typography.bodySmall)
            pending.take(10).forEach { Text("• " + it.name.take(80), style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    confirming = false
                    if (!BatchMoveRemoval.matchesServerScope(pending, serverScopeDigest)) {
                        onFinished("The destination server account changed. No original jobs were removed.")
                        return@Button
                    }
                    working = true
                    // Acquire and release the mutation lease inside the same
                    // coroutine. An Activity/navigation disposal can cancel
                    // this scope while remote/native removal is suspended;
                    // cancellation must never strand the shared mutation gate.
                    // UNDISPATCHED starts the exact-account preflight and
                    // admission on the click stack, before navigation races.
                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        var acquired = false
                        val report = try {
                            if (!onStarted()) {
                                onFinished("Another change is still settling. Nothing was removed; try again shortly.")
                                return@launch
                            }
                            acquired = true
                            // Return control to the click dispatcher before any
                            // potentially blocking source/target observation.
                            // Cancellation at this boundary still runs finally
                            // and releases the acquired gate without a write.
                            kotlinx.coroutines.yield()
                            BatchMoveRemoval.run(pending, observeTarget, removeSource, observeSource, save, forget)
                        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                        catch (_: Exception) { null }
                        finally {
                            working = false
                            if (acquired) onSettled()
                        }
                        onFinished(if (report == null) "The batch removal stopped unexpectedly. Check each " +
                            "transfer's Move status; nothing is retried automatically."
                        else "${report.count(BatchMoveRemoval.Result.REMOVED) + report.count(BatchMoveRemoval.Result.REMOVED_JOURNAL_PENDING)} original $sourceLabel jobs verified absent " +
                            "(files kept)." + report.results.filter { it.second != BatchMoveRemoval.Result.REMOVED }
                                .take(10).joinToString("") { "\n• " + it.first.name.take(80) + ": " + it.second.label })
                    }
                }, enabled = !busy && !working && exactScope,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Remove original jobs")
                }
                OutlinedButton(onClick = { confirming = false },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Keep them") }
            }
        }
    }
}
