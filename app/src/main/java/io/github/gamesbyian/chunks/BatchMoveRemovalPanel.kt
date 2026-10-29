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
    serverLabel: String,
    busy: Boolean,
    observeTarget: suspend (String) -> TransferMove.Observation,
    removeSource: suspend (String) -> Unit,
    observeSource: suspend (String) -> TransferMove.Observation,
    save: (TransferMove.Record) -> Boolean,
    forget: (TransferMove.Record) -> Unit,
    onFinished: (String) -> Unit
) {
    if (pending.size < 2) return   // a single move is finished from its own card
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${pending.size} transfers are now on $serverLabel. Their original phone jobs are still here.",
            style = MaterialTheme.typography.bodyMedium)
        if (!confirming) {
            Button(onClick = { confirming = true }, enabled = !busy && !working,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Remove ${pending.size} original phone jobs…")
            }
        } else {
            Text("Remove these ${pending.size} original torrent jobs from the phone? Downloaded files stay on " +
                "this phone and still use its storage; the phone stops sharing them. Each new server job is " +
                "checked again first, and Chunks stops at the first removal it cannot confirm.",
                style = MaterialTheme.typography.bodySmall)
            pending.take(10).forEach { Text("• " + it.name.take(80), style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    confirming = false
                    working = true
                    scope.launch {
                        val report = try {
                            BatchMoveRemoval.run(pending, observeTarget, removeSource, observeSource, save, forget)
                        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                        catch (_: Exception) { null }
                        working = false
                        onFinished(if (report == null) "The batch removal stopped unexpectedly. Check each " +
                            "transfer's Move status; nothing is retried automatically."
                        else "${report.count(BatchMoveRemoval.Result.REMOVED)} original phone jobs removed " +
                            "(files kept)." + report.results.filter { it.second != BatchMoveRemoval.Result.REMOVED }
                                .take(10).joinToString("") { "\n• " + it.first.name.take(80) + ": " + it.second.label })
                    }
                }, enabled = !busy && !working, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text("Remove original jobs")
                }
                OutlinedButton(onClick = { confirming = false },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Keep them") }
            }
        }
    }
}
