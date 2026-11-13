package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.ForeignPartialAddPlan
import io.github.gamesbyian.chunks.model.Units
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.liveRegion

/**
 * Optional "Review recovery" step inside the existing local Add review
 * (FOREIGN-PARTIAL-TORRENT-RECOVERY P1). It only describes what a read-only scan saw;
 * reuse is off until the person turns it on, and turning it on renames files only at Add.
 */
@Composable
internal fun ForeignPartialRecoveryPanel(
    offer: ForeignPartialAddPlan.Offer,
    useRecovery: Boolean,
    enabled: Boolean,
    onUseRecovery: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth().testTag(ChunksSemantics.ADD_FOREIGN_PARTIAL),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Partial files from another app", style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() })
        ForeignPartialOfferFacts(offer)
        if (offer.reusable.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = useRecovery, enabled = enabled, role = Role.Switch,
                        onValueChange = onUseRecovery)
                    .testTag(ChunksSemantics.ADD_FOREIGN_PARTIAL_REUSE),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Reuse these partial files", Modifier.weight(1f))
                Switch(checked = useRecovery, onCheckedChange = null, enabled = enabled)
            }
            Text("When you add, Chunks renames them to the torrent's file names and then checks every " +
                "piece. Only pieces that pass are kept and the rest download again. Nothing is deleted, " +
                "and the original names come back if the Add is refused.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Start-time recovery inside canonical local Details for a STOPPED job with known files,
 * including a magnet whose metadata has arrived. Reading is read-only; the single button
 * renames only the reviewed set and queues libtorrent's full piece check, keeping the job
 * stopped so the person's ordinary Start verifies before any download.
 */
@Composable
internal fun LocalForeignPartialStartRecovery(infoHash: String) {
    var offer by androidx.compose.runtime.remember(infoHash) {
        androidx.compose.runtime.mutableStateOf<ForeignPartialAddPlan.Offer?>(null)
    }
    var busy by androidx.compose.runtime.remember(infoHash) { androidx.compose.runtime.mutableStateOf(false) }
    var outcome by androidx.compose.runtime.remember(infoHash) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var revision by androidx.compose.runtime.remember(infoHash) { androidx.compose.runtime.mutableIntStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(infoHash, revision) {
        offer = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            LocalEngineHolder.current()?.foreignPartialOffer(infoHash)
        }
    }
    val current = offer
    if (current == null && outcome == null) return
    Column(Modifier.fillMaxWidth().testTag(ChunksSemantics.DETAILS_FOREIGN_PARTIAL),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (current != null) {
            Text("Partial files from another app", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() })
            ForeignPartialOfferFacts(current)
            if (current.reusable.isNotEmpty()) {
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        if (busy) return@OutlinedButton
                        busy = true
                        scope.launch {
                            val result = if (!LocalMutationFence.revision.beginMutation()) null else try {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    LocalEngineHolder.current()?.adoptForeignPartialsForStart(infoHash, current)
                                }
                            } finally { LocalMutationFence.revision.settledMutation() }
                            outcome = when (result) {
                                null -> "Another change to this device's transfers is in progress. Nothing was renamed."
                                is ForeignPartialAddPlan.Adoption.Renamed ->
                                    "Renamed " + result.receipt.renames.size + " partial " +
                                        (if (result.receipt.renames.size == 1) "file" else "files") +
                                        ". The transfer stays stopped; Start checks every piece before downloading what is missing."
                                is ForeignPartialAddPlan.Adoption.Refused -> result.problem + ". Nothing was renamed."
                                ForeignPartialAddPlan.Adoption.NotRequested -> null
                            }
                            busy = false
                            revision++
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag(ChunksSemantics.DETAILS_FOREIGN_PARTIAL_REUSE)
                ) { Text("Reuse partial files and check on Start") }
                Text("Chunks renames them to this torrent's file names; nothing is deleted. Only pieces " +
                    "that pass the check are kept.", style = MaterialTheme.typography.bodySmall)
            }
        }
        outcome?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics {
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        }
    }
}

@Composable
private fun ForeignPartialOfferFacts(offer: ForeignPartialAddPlan.Offer) {
    val count = offer.reusable.size
    if (count > 0) Text("Found " + count + (if (count == 1) " partial file" else " partial files") + " (" +
        Units.bytes(offer.reusableBytes) + ") left by " +
        offer.clients.joinToString { ForeignPartialAddPlan.clientLabel(it) } +
        " next to this torrent's file names.", style = MaterialTheme.typography.bodySmall)
    if (offer.ambiguousFiles > 0) Text(offer.ambiguousFiles.toString() +
        (if (offer.ambiguousFiles == 1) " file has" else " files have") +
        " more than one possible partial copy and will be left untouched.",
        style = MaterialTheme.typography.bodySmall)
    if (offer.untouchedAuxiliary > 0) Text(offer.untouchedAuxiliary.toString() +
        (if (offer.untouchedAuxiliary == 1) " control or fragment file is" else " control or fragment files are") +
        " not supported and will be left untouched.", style = MaterialTheme.typography.bodySmall)
    if (offer.truncated) Text("Only the first partial files were listed.",
        style = MaterialTheme.typography.bodySmall)
}
