package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A failed read of the server list, with its repair beside it. The action
 * repeats only the read-only list refresh, on any connected route; it never
 * resubmits a command, so a timed-out mutation still needs its own review.
 */
@Composable
internal fun RefreshProblemNotice(message: String, refreshing: Boolean, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChunksBanner("Refresh problem", message, ChunksTone.ERROR)
        OutlinedButton(onClick = onRefresh, enabled = !refreshing,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (refreshing) "Refreshing…" else "Refresh list again")
        }
    }
}
