package io.github.gamesbyian.chunks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
    val prefs by controller.presentation.collectAsState()
    if (!prefs.showSimilarTransfers) return
    val authorized = remember(sources) {
        sources.filter { it.availability != EnvironmentAvailability.LOCKED }
            .flatMap { source -> source.transfers.map { it to source } }
            .associateBy { it.first.key }
    }
    // Recompute when this transfer's own confirmed identity changes (e.g. after Identify).
    val sourceState by rememberTransferMedia(MediaTransferKey.of(transfer.key), transfer.originalName)
    var candidates by remember(transfer.key) { mutableStateOf<List<RelatedTransferCandidate>>(emptyList()) }
    LaunchedEffect(transfer.key, authorized.keys, sourceState.assignment?.displayMatch?.ref, prefs.similarSameEnvironmentOnly) {
        candidates = if (sourceState.assignment?.displayMatch == null) emptyList()
            else controller.similarFor(transfer.key, authorized.keys, sameEnvironmentOnly = prefs.similarSameEnvironmentOnly)
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

/** The phone's own list as a Similar Transfers source (same-environment scope in phone details). */
internal fun phoneSimilarSource(transfers: List<io.github.gamesbyian.chunks.engine.LocalTransferStatus>, stale: Boolean): EnvironmentTransferSnapshot {
    val env = io.github.gamesbyian.chunks.model.EnvironmentSnapshots.PHONE
    val rows = transfers.distinctBy { it.infoHash.lowercase() }.mapNotNull {
        runCatching { io.github.gamesbyian.chunks.model.UnifiedTransferObservations.fromPhone(env, it) }.getOrNull()
    }
    return EnvironmentTransferSnapshot(env, if (stale) EnvironmentAvailability.STALE else EnvironmentAvailability.LIVE,
        !stale, System.currentTimeMillis(), rows)
}

/** One authenticated rTorrent list as a Similar Transfers source (same-environment scope in its cards). */
internal fun remoteSimilarSource(environment: EnvironmentId, transfers: List<io.github.gamesbyian.chunks.rpc.RemoteTransfer>, stale: Boolean): EnvironmentTransferSnapshot {
    val rows = transfers.distinctBy { it.hash.lowercase() }.mapNotNull {
        runCatching { io.github.gamesbyian.chunks.model.UnifiedTransferObservations.fromRTorrent(environment, it) }.getOrNull()
    }
    return EnvironmentTransferSnapshot(environment, if (stale) EnvironmentAvailability.STALE else EnvironmentAvailability.LIVE,
        !stale, System.currentTimeMillis(), rows)
}
