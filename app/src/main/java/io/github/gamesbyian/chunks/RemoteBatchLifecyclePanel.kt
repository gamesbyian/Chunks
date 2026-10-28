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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.RemoteBatchLifecycle
import io.github.gamesbyian.chunks.rpc.LifecycleOutcome
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import kotlinx.coroutines.launch

/**
 * Reviewed Start/Stop for the selected rTorrent transfers. One exact-hash
 * command at a time under a single mutation lease; the first uncertain reply
 * or failure halts the batch, and nothing is ever retried automatically.
 */
@Composable
internal fun RemoteBatchLifecyclePanel(
    selected: List<RemoteTransfer>,
    enabled: Boolean,
    onMutationStarted: () -> Boolean,
    onSettled: () -> Unit,
    performLifecycle: suspend (String, Boolean) -> LifecycleOutcome
) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf<Boolean?>(null) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val toStart = selected.count { !it.started }
    val toStop = selected.count { it.started }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Batch Start / Stop", fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirming = true; message = null },
                enabled = enabled && !running && toStart > 0,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Start $toStart") }
            OutlinedButton(onClick = { confirming = false; message = null },
                enabled = enabled && !running && toStop > 0,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Stop $toStop") }
        }
        val wanted = confirming
        if (wanted != null) {
            val count = if (wanted) toStart else toStop
            Text("${if (wanted) "Start" else "Stop"} $count selected transfers on this server, one " +
                "at a time. Already ${if (wanted) "started" else "stopped"} transfers are skipped. If " +
                "any reply is lost or refused, Chunks stops there without retrying and refreshes " +
                "so you can check. No files are moved or deleted.",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    confirming = null
                    if (!onMutationStarted()) {
                        message = "Another server change or refresh is in progress. Nothing was sent."
                        return@Button
                    }
                    running = true
                    val targets = selected.map { RemoteBatchLifecycle.Target(it.hash, it.started) }
                    scope.launch {
                        message = try {
                            describe(RemoteBatchLifecycle.run(targets, wanted, performLifecycle))
                        } catch (_: IllegalArgumentException) {
                            "This selection cannot be changed as a batch. Nothing was sent."
                        } finally {
                            running = false
                            onSettled()
                        }
                    }
                }, enabled = enabled && !running && count > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(if (wanted) "Confirm Start" else "Confirm Stop")
                }
                OutlinedButton(onClick = { confirming = null },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
            }
        }
        if (running) Text("Sending commands one at a time…")
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun describe(report: RemoteBatchLifecycle.Report): String {
    val verb = if (report.requestedStarted) "started" else "stopped"
    val parts = mutableListOf("${report.verified.size} confirmed $verb")
    if (report.pending.isNotEmpty())
        parts += "${report.pending.size} accepted but not yet confirmed"
    if (report.skipped.isNotEmpty()) parts += "${report.skipped.size} already $verb"
    var text = parts.joinToString(", ") + "."
    if (report.halted) {
        text += if (report.haltedUncertain)
            " Halted: one reply was lost, so that transfer may or may not have changed."
        else " Halted: the server refused one command."
        text += " ${report.notAttempted.size} not attempted. Check the refreshed list before trying again."
    }
    return text
}
