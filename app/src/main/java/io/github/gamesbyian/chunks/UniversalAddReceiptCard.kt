package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.gamesbyian.chunks.model.UniversalAddDisplayStage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.UniversalAddDisplay

/**
 * One Add receipt for Local, rTorrent, and future clients. The pure projector
 * decides what the evidence permits saying; this surface never interprets an
 * RPC reply, changes a ticket or triggers a second engine write.
 *
 * Optional detail content may explain an engine-specific observation or offer
 * a read-only View transfer action. It must never contradict the shared heading.
 */
@Composable
internal fun UniversalAddReceiptCard(
    receipt: UniversalAddDisplay,
    modifier: Modifier = Modifier,
    needsAttention: Boolean = false,
    details: @Composable () -> Unit = {}
) {
    Card(
        // Stable, human-readable state for TalkBack and installed acceptance.
        // The projection owns evidence; this never upgrades an uncertain write.
        modifier = modifier.fillMaxWidth().semantics {
            liveRegion = LiveRegionMode.Polite
            stateDescription = when (receipt.stage) {
                UniversalAddDisplayStage.SUBMITTING -> "Adding"
                UniversalAddDisplayStage.REGISTERED_CHECKING -> "Added, checking"
                UniversalAddDisplayStage.VERIFIED -> "Verified"
                UniversalAddDisplayStage.SESSION_CONFIRMED -> "Present, origin unverified"
                UniversalAddDisplayStage.START_UNVERIFIED -> "Start unverified"
                UniversalAddDisplayStage.UNCONFIRMED -> "Add unverified"
                UniversalAddDisplayStage.NO_NEW_ADD -> "Not added"
            }
        },
        colors = CardDefaults.cardColors(
            containerColor = if (needsAttention) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(receipt.title, fontWeight = FontWeight.Bold)
            Text(receipt.explanation, style = MaterialTheme.typography.bodyMedium)
            details()
        }
    }
}
