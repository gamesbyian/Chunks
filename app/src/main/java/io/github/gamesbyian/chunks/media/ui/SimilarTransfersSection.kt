package io.github.gamesbyian.chunks.media.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.ChunksColors
import io.github.gamesbyian.chunks.model.MediaRelationKind
import io.github.gamesbyian.chunks.model.RelatedTransferCandidate
import io.github.gamesbyian.chunks.model.UnifiedTransferKey

/** What the host knows about a related row from its existing inventory (no extra RPC). */
data class SimilarTransferDisplay(
    val name: String,
    /** The environment's user-visible name; always shown as text, colour is only an accent. */
    val environmentName: String,
    val environmentColor: Color,
    val statusText: String,
    /** True when the row comes from a cached, offline or stale observation. */
    val stale: Boolean = false
)

/** Specific, evidence-derived wording (SHARED-MEDIA-IDENTITY "relationship labels"). */
fun similarRelationLabel(c: RelatedTransferCandidate, targetEnvironmentName: String): String {
    val first = c.evidence.first()
    return when (c.strongestRelation) {
        MediaRelationKind.SAME_TORRENT -> "Also on $targetEnvironmentName"
        MediaRelationKind.SAME_WORK -> "Same work"
        MediaRelationKind.ADJACENT_EPISODE -> "Nearby episode"
        MediaRelationKind.SAME_SERIES -> "Same series"
        MediaRelationKind.SHARED_WRITER -> first.name?.let { "Same writer: $it" } ?: "Same writer"
        MediaRelationKind.BIOGRAPHICAL_SUBJECT_AND_ARTIST -> first.name?.let { "About $it" } ?: "About the artist"
        MediaRelationKind.SAME_ARTIST -> first.name?.let { "Same artist: $it" } ?: "Same artist"
        MediaRelationKind.SHARED_ACTOR -> first.name?.let { "Featuring $it" } ?: "Shared cast"
        MediaRelationKind.SHARED_TOPIC -> "Related topic"
    }.let { label ->
        // A partial collection match is disclosed, never presented as the whole transfer.
        if (c.observedMembers > 1 && c.matchedMembers < c.observedMembers) "$label (${c.matchedMembers} of ${c.observedMembers})" else label
    }
}

/**
 * Compact "Similar transfers" for expanded details. Renders nothing when there are no candidates.
 * The host supplies already-authorized, privacy-filtered candidates (e.g. from
 * `MediaRelationIndex.similar` over `MediaRelationBridge.members(...)`) and resolves each to display
 * facts from its existing inventory; [onOpen] navigates to that exact environment + transfer.
 */
@Composable
fun SimilarTransfersSection(
    candidates: List<RelatedTransferCandidate>,
    display: (UnifiedTransferKey) -> SimilarTransferDisplay?,
    onOpen: (UnifiedTransferKey) -> Unit,
    modifier: Modifier = Modifier,
    defaultLimit: Int = 5
) {
    val rows = candidates.mapNotNull { c -> display(c.key)?.let { c to it } }
    if (rows.isEmpty()) return
    var showAll by rememberSaveable { mutableStateOf(false) }
    val visible = if (showAll) rows else rows.take(defaultLimit)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Similar transfers", style = MaterialTheme.typography.titleSmall, color = ChunksColors.Text,
            modifier = Modifier.semantics { heading() })
        visible.forEach { (c, d) ->
            val label = similarRelationLabel(c, d.environmentName)
            val spoken = listOfNotNull(d.name, label, d.environmentName, d.statusText, if (d.stale) "cached, may be out of date" else null)
                .joinToString(", ")
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min).heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).background(ChunksColors.Surface)
                .clickable(role = Role.Button, onClickLabel = "Open transfer") { onOpen(c.key) }
                .semantics(mergeDescendants = true) { contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(5.dp).fillMaxHeight().background(d.environmentColor).clearAndSetSemantics {})
                Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = ChunksColors.TextSecondary, fontWeight = FontWeight.SemiBold)
                    Text(d.name, style = MaterialTheme.typography.bodyMedium, color = ChunksColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(d.environmentName + " · " + d.statusText + if (d.stale) " · cached" else "",
                        style = MaterialTheme.typography.labelSmall, color = ChunksColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (rows.size > defaultLimit) TextButton(onClick = { showAll = !showAll }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (showAll) "Show fewer" else "Show all ${rows.size}")
        }
    }
}
