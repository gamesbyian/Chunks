package io.github.gamesbyian.chunks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.gamesbyian.chunks.media.LocalTransferMediaController
import io.github.gamesbyian.chunks.media.MediaTransferKey
import io.github.gamesbyian.chunks.media.rememberTransferMedia
import io.github.gamesbyian.chunks.media.ui.SimilarTransferDisplay
import io.github.gamesbyian.chunks.media.ui.SimilarTransfersSection
import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.RelatedTransferCandidate
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation

/**
 * Similar Transfers for Combined details. Inventory comes only from the already displayed,
 * privacy-authorized sources (a LOCKED source contributes nothing); relations use only confirmed
 * media identities through `MediaRelationBridge` + `MediaRelationIndex`. Opening details never
 * starts a provider lookup or RPC, and [onOpen] navigates to the exact environment + transfer.
 */
@Composable
internal fun SimilarTransfersHost(
    transfer: UnifiedTransferObservation,
    sources: List<EnvironmentTransferSnapshot>,
    environmentLabel: (EnvironmentId) -> String?,
    environmentColor: (EnvironmentId) -> Color,
    onOpen: (UnifiedTransferKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val controller = LocalTransferMediaController.current ?: return
    val authorized = remember(sources) {
        sources.filter { it.availability != EnvironmentAvailability.LOCKED }
            .flatMap { source -> source.transfers.map { it to source } }
            .associateBy { it.first.key }
    }
    // Recompute when this transfer's own confirmed identity changes (e.g. after Identify).
    val sourceState by rememberTransferMedia(MediaTransferKey.of(transfer.key), transfer.originalName)
    var candidates by remember(transfer.key) { mutableStateOf<List<RelatedTransferCandidate>>(emptyList()) }
    LaunchedEffect(transfer.key, authorized.keys, sourceState.assignment?.displayMatch?.ref) {
        candidates = if (sourceState.assignment?.displayMatch == null) emptyList()
            else controller.similarFor(transfer.key, authorized.keys)
    }
    SimilarTransfersSection(
        candidates = candidates,
        display = { key ->
            val (row, source) = authorized[key] ?: return@SimilarTransfersSection null
            val label = environmentLabel(key.environment) ?: return@SimilarTransfersSection null
            SimilarTransferDisplay(name = row.displayName, environmentName = label,
                environmentColor = environmentColor(key.environment), statusText = transferAtAGlance(row),
                stale = !source.canClaimCompleteLiveInventory)
        },
        onOpen = onOpen,
        modifier = modifier
    )
}
