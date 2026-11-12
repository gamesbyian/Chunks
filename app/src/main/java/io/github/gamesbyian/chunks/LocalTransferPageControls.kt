package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.ManualCollectionPager

/**
 * View-only paging of the local device transfer list. Keep the visible range and
 * current page readable, while avoiding two full-width stacked action rows
 * on an ordinary handset. Page navigation never targets engine mutations.
 */
@Composable
internal fun LocalTransferPageControls(
    page: ManualCollectionPager.Page<*>,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    if (page.totalPages <= 1) return

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 380.dp || LocalDensity.current.fontScale >= 1.5f
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "On-local transfers: " + page.totalMatches +
                    " · page " + (page.pageIndex + 1) + " of " + page.totalPages,
                style = MaterialTheme.typography.bodyMedium
            )
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onPrevious,
                        enabled = page.hasPrevious,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .semantics { contentDescription = "Previous local transfers" }
                    ) { Text("Previous local transfers") }
                    OutlinedButton(
                        onClick = onNext,
                        enabled = page.hasNext,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .semantics { contentDescription = "Next local transfers" }
                    ) { Text("Next local transfers") }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onPrevious,
                        enabled = page.hasPrevious,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .semantics { contentDescription = "Previous local transfers" }
                    ) { Text("Previous local transfers") }
                    OutlinedButton(
                        onClick = onNext,
                        enabled = page.hasNext,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .semantics { contentDescription = "Next local transfers" }
                    ) { Text("Next local transfers") }
                }
            }
        }
    }
}
