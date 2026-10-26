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
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.ManualCollectionPager

/**
 * Pure view-only paging controls. All actual transfer actions still target
 * a verified engine infohash, never a page number.
 */
@Composable
internal fun LocalTransferPageControls(
    page: ManualCollectionPager.Page<*>,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Text("On-phone transfers: " + page.totalMatches +
        " · page " + (page.pageIndex + 1) + " of " + page.totalPages,
        style = MaterialTheme.typography.bodyMedium)
    if (page.totalPages > 1) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPrevious, enabled = page.hasPrevious,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Previous phone transfers")
            }
            OutlinedButton(onClick = onNext, enabled = page.hasNext,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Next phone transfers")
            }
        }
    }
}
