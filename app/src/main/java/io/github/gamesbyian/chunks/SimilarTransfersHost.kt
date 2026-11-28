package io.github.gamesbyian.chunks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.gamesbyian.chunks.media.LocalTransferMediaController
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
 * privacy-authorized sources (a LOCKED source contributes nothing). The controller owns relation
 * evidence; this host does not register a new automatic media observer or perform an RPC.
 * [onOpen] navigates to the exact environment + transfer.
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
    val authorized = remember(sources, transfer.key.environment, prefs.similarSameEnvironmentOnly) {
        sources.filter { source ->
            source.availability != EnvironmentAvailability.LOCKED &&
                (!prefs.similarSameEnvironmentOnly || source.environment == transfer.key.environment)
        }.flatMap { source -> source.transfers.map { it to source } }
            .associateBy { it.first.key }
    }
    // A details route can temporarily outlive its authorized inventory. Do not query or render
    // relations for a source no longer present in that inventory.
    if (transfer.key !in authorized) return
    val assignmentRevision by controller.assignmentRevision.collectAsState()
    val visibleKeys = authorized.keys
    // Reset old results synchronously when the exact scope or stored evidence changes, rather
    // than displaying a previous query while the new asynchronous read runs. The revision also
    // covers corrections to other transfers and same-provider-record episode/season corrections.
    var candidates by remember(controller, transfer.key, visibleKeys, assignmentRevision) {
        mutableStateOf<List<RelatedTransferCandidate>>(emptyList())
    }
    LaunchedEffect(controller, transfer.key, visibleKeys, assignmentRevision) {
        candidates = controller.similarFor(transfer.key, visibleKeys,
            sameEnvironmentOnly = prefs.similarSameEnvironmentOnly)
    }
    key(transfer.key) {
        SimilarTransfersSection(
            candidates = candidates,
            display = { key ->
                val (row, source) = authorized[key] ?: return@SimilarTransfersSection null
                val label = environmentLabel(key.environment) ?: return@SimilarTransfersSection null
                SimilarTransferDisplay(name = row.displayName, environmentName = label,
                    environmentColor = environmentColor(key.environment), statusText = transferAtAGlance(row),
                    stale = !source.canClaimCompleteLiveInventory)
            },
            onOpen = { target -> if (target in visibleKeys) onOpen(target) },
            modifier = modifier
        )
    }
}

/** The local's own list as a Similar Transfers source (same-environment scope in local details). */
internal fun localSimilarSource(transfers: List<io.github.gamesbyian.chunks.engine.LocalTransferStatus>, stale: Boolean): EnvironmentTransferSnapshot {
    val env = io.github.gamesbyian.chunks.model.EnvironmentSnapshots.LOCAL
    val rows = transfers.distinctBy { it.infoHash.lowercase() }.mapNotNull {
        runCatching { io.github.gamesbyian.chunks.model.UnifiedTransferObservations.fromLocal(env, it) }.getOrNull()
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
