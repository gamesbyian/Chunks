package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.rpc.ClientJobTransfer
import io.github.gamesbyian.chunks.rpc.ClientOperationOutcome
import io.github.gamesbyian.chunks.rpc.RemoteClientOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Job-only Copy / Move of one exact job from **This device** to another
 * connected client, through the same shared port and [ClientJobTransfer]
 * used between remote clients: exported metainfo → ONE verified target Add,
 * then a separate, user-tapped, session-only retirement of the local job
 * (its files stay). An uncertain result locks this section until [inventoryWitness]
 * changes (a fresh local list).
 */
@Composable
internal fun LocalJobMoveSection(
    hash: String,
    /** The local list row is current and privacy-visible. */
    current: Boolean,
    observedStarted: Boolean?,
    inventoryWitness: Any?,
    createSource: suspend () -> RemoteClientOperations,
    targets: List<ProtocolMoveTarget>,
    onChanged: () -> Unit
) {
    if (targets.isEmpty()) return
    val scope = rememberCoroutineScope()
    var open by remember(hash) { mutableStateOf(false) }
    var busy by remember(hash) { mutableStateOf(false) }
    var notice by remember(hash) { mutableStateOf<String?>(null) }
    var uncertainAt by remember(hash) { mutableStateOf<Any?>(null) }
    val latched = uncertainAt != null && uncertainAt == inventoryWitness
    var copiedTo by remember(hash) { mutableStateOf<ProtocolMoveTarget?>(null) }
    var transfer by remember(hash) { mutableStateOf<ClientJobTransfer?>(null) }
    var receipt by remember(hash) { mutableStateOf<ClientJobTransfer.CopyResult.Copied?>(null) }
    val enabled = current && !busy && !latched

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open = !open }, enabled = current,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag(ChunksSemantics.PROTOCOL_MOVE)) {
            Text(if (open) "Hide copy or move" else "Copy or move job to another client…")
        }
        if (open) {
            Text("Copies only the torrent job. The other client downloads its files itself; " +
                "nothing on this device is changed unless you then finish the move.",
                style = MaterialTheme.typography.bodySmall)
            targets.forEach { target ->
                OutlinedButton(onClick = {
                    if (!enabled) return@OutlinedButton
                    busy = true
                    copiedTo = null; transfer = null; receipt = null
                    notice = "Copying job to ${target.label}…"
                    scope.launch {
                        val job = try {
                            val src = createSource()
                            val dst = target.createOperations()
                            ClientJobTransfer(src, src.client, dst, dst.client,
                                sourceClientId = EnvironmentSnapshotsLocalId, targetClientId = target.id)
                        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                        val result = try {
                            job?.copy(hash, targetDirectory = null, startOnTarget = observedStarted == true)
                        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                        when (result) {
                            is ClientJobTransfer.CopyResult.Copied -> {
                                copiedTo = target; transfer = job; receipt = result
                                notice = "Job copied to ${target.label} (confirmed). Its files download there separately."
                            }
                            is ClientJobTransfer.CopyResult.NotCopied -> {
                                notice = ProtocolOperationText.describe("Copy to ${target.label}", result.outcome, target.label)
                                if (result.outcome is ClientOperationOutcome.Uncertain) uncertainAt = inventoryWitness
                            }
                            is ClientJobTransfer.CopyResult.Refused ->
                                notice = "Copy to ${target.label}: not changed. ${result.reason}."
                            null -> notice = "Copy to ${target.label}: could not reach both clients. Nothing was changed."
                        }
                        busy = false
                        onChanged()
                    }
                }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag(ChunksSemantics.PROTOCOL_MOVE + "." + target.id)) {
                    Text("Copy job to ${target.label}")
                }
            }
            val done = copiedTo
            val job = transfer
            val proof = receipt
            if (done != null && job != null && proof != null) Button(onClick = {
                if (!enabled) return@Button
                busy = true
                copiedTo = null; transfer = null; receipt = null
                notice = "Removing the job from this device…"
                scope.launch {
                    val outcome = try { job.retireSource(hash, proof) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { ClientOperationOutcome.Uncertain(hash, "unexpected failure") }
                    notice = ProtocolOperationText.describe(
                        "Remove from this device after moving to ${done.label}", outcome, "this device")
                    if (outcome is ClientOperationOutcome.Uncertain) uncertainAt = inventoryWitness
                    busy = false
                    onChanged()
                }
            }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag(ChunksSemantics.PROTOCOL_MOVE_RETIRE)) {
                Text("Finish move: remove from this device (keep files)")
            }
        }
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag(ChunksSemantics.PROTOCOL_OPERATION_STATUS))
        }
        if (latched) Text("Copy and move are paused until the list refreshes.",
            style = MaterialTheme.typography.bodySmall, color = ChunksColors.Yellow)
    }
}

private val EnvironmentSnapshotsLocalId = io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL.value
