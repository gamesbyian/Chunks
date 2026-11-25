package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import io.github.gamesbyian.chunks.rpc.ClientOperations
import kotlinx.coroutines.launch

/** One typed profile whose complete live inventory was just read. */
internal class ProtocolReceiptCheckSource(
    val profileId: String,
    val label: String,
    /** Exact hashes present in that complete, current inventory. */
    val listedHashes: Set<String>,
    val createOperations: () -> ClientOperations,
    val isOwnerCurrent: () -> Boolean
)

/**
 * Retained receipts that Details can no longer reach because their job is not
 * in the profile's complete live inventory (for example after a Remove whose
 * reply was lost, or after process death). Each offers the same explicit
 * read-only check as Details; nothing is retried and nothing is written.
 * Receipts for listed jobs stay in that job's Details, never duplicated here.
 */
internal fun absentJobReceipts(
    owner: ProtocolOperationReceiptOwner,
    profileId: String,
    listedHashes: Set<String>
): List<ProtocolOperationReceiptOwner.Receipt> =
    owner.checkable(profileId).filter { it.ticket.key.hash !in listedHashes }

/**
 * Whether the Transfers card must be present: while any such receipt remains,
 * AND after a check released one, so its outcome stays readable instead of
 * disappearing together with the last unresolved receipt.
 */
internal fun showAbsentReceiptsCard(
    owner: ProtocolOperationReceiptOwner,
    sources: List<ProtocolReceiptCheckSource>,
    results: Map<ProtocolOperationReceiptOwner.Key, String>
): Boolean = results.isNotEmpty() ||
    sources.any { absentJobReceipts(owner, it.profileId, it.listedHashes).isNotEmpty() }

internal fun listedHashes(transfers: List<UnifiedTransferObservation>): Set<String> =
    transfers.map { it.key.infoHash.lowercase(java.util.Locale.ROOT) }.toSet()

@Composable
internal fun ProtocolAbsentReceiptsCard(
    sources: List<ProtocolReceiptCheckSource>,
    owner: ProtocolOperationReceiptOwner = ProtocolOperationReceipts.owner,
    /** Hoisted by the caller so outcomes outlive the last unresolved receipt. */
    results: MutableMap<ProtocolOperationReceiptOwner.Key, String> =
        remember { mutableStateMapOf() },
    onChecked: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val busy = remember { mutableStateMapOf<ProtocolOperationReceiptOwner.Key, Boolean>() }
    val current by rememberUpdatedState(sources)
    val pending = sources.flatMap { source ->
        absentJobReceipts(owner, source.profileId, source.listedHashes).map { source to it }
    }
    if (pending.isEmpty() && results.isEmpty()) return
    Card(Modifier.fillMaxWidth().testTag(ChunksSemantics.PROTOCOL_ABSENT_RECEIPTS)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Earlier requests need checking", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Text("These transfers are no longer listed on their client. A read-only check confirms " +
                "what remains; nothing is retried.", style = MaterialTheme.typography.bodySmall)
            for ((source, receipt) in pending) {
                val key = receipt.ticket.key
                val shortHash = key.hash.take(8)
                Text(source.label + " · transfer " + shortHash + "…: " + receipt.description(),
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = {
                        if (busy[key] == true) return@OutlinedButton
                        busy[key] = true
                        scope.launch {
                            try {
                                val live = current.firstOrNull { it.profileId == source.profileId }
                                val outcome = if (live == null) ProtocolReceiptCheck.OWNER_CHANGED else
                                    checkRetainedProtocolReceipt(owner, receipt, live.createOperations()) {
                                        live.isOwnerCurrent() &&
                                            current.any { it.profileId == source.profileId }
                                    }
                                results[key] = when (outcome) {
                                    ProtocolReceiptCheck.RELEASED ->
                                        owner.receipt(key)?.description() ?: "Checked."
                                    ProtocolReceiptCheck.READ_FAILED ->
                                        "Could not read this transfer. The previous request remains unresolved."
                                    ProtocolReceiptCheck.OWNER_CHANGED, ProtocolReceiptCheck.NOT_CHECKABLE ->
                                        "The connection changed. Nothing was checked; try again."
                                }
                                if (outcome == ProtocolReceiptCheck.RELEASED) onChecked(source.profileId)
                            } finally {
                                busy.remove(key)
                            }
                        }
                    },
                    enabled = busy[key] != true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag(ChunksSemantics.PROTOCOL_ABSENT_RECEIPT_CHECK + "." + key.hash)
                        .semantics {
                            contentDescription = "Check transfer $shortHash on ${source.label} without changing it"
                        }
                ) { Text("Check without changing it") }
            }
            for ((key, text) in results) {
                Text(text, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(ChunksSemantics.PROTOCOL_ABSENT_RECEIPT_RESULT + "." + key.hash)
                        .semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}
