package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.UnifiedTransferKey

/** A verified Add key alone is insufficient: this exact source must also have observed it. */
internal fun combinedAddedTransferHidden(
    added: UnifiedTransferKey?,
    sources: List<EnvironmentTransferSnapshot>,
    shown: List<UnifiedTransferKey>
): Boolean = added != null && added !in shown && sources.any { source ->
    source.environment == added.environment && source.canClaimCompleteLiveInventory &&
        source.transfers.any { it.key == added }
}

@Composable
internal fun CombinedAddedHiddenNotice(environmentLabel: String, onShowAll: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Your new transfer on $environmentLabel is hidden by the current search, filter or environment view.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        OutlinedButton(onClick = onShowAll,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Show all transfers")
        }
    }
}
