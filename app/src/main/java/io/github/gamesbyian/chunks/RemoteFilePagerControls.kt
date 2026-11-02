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
import androidx.compose.ui.unit.dp

/**
 * Display-only navigation for an already bounded server file listing. The
 * caller still owns the exact file indices and priority/command permissions.
 *
 * For <=40 files the parent already shows the file count and all rows, so
 * blank Previous/Next buttons and "Page 1 of 1" are redundant. At narrow width
 * or increased system text, multi-page buttons stack instead of clipping.
 */
@Composable
internal fun RemoteFilePagerControls(
    page: FilePage,
    totalFiles: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    if (page.count <= 1) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Showing ${page.firstIndex + 1}–${page.endExclusive} of $totalFiles files",
            style = MaterialTheme.typography.bodySmall)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 380.dp || LocalDensity.current.fontScale >= 1.5f
            if (stacked) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilePageBack(page, onPrevious, Modifier.fillMaxWidth())
                    FilePageForward(page, onNext, Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilePageBack(page, onPrevious, Modifier.weight(1f))
                    FilePageForward(page, onNext, Modifier.weight(1f))
                }
            }
        }
        Text("Page ${page.number + 1} of ${page.count}",
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun FilePageBack(page: FilePage, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = page.number > 0,
        modifier = modifier.heightIn(min = 48.dp)
    ) { Text("Previous ${FilePages.SIZE}") }
}

@Composable
private fun FilePageForward(page: FilePage, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = page.number < page.count - 1,
        modifier = modifier.heightIn(min = 48.dp)
    ) { Text("Next ${FilePages.SIZE}") }
}
