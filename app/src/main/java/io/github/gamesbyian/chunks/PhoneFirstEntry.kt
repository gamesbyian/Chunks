package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Primary disconnected entry path. This is an actual navigation callback into
 * the existing phone Transfers/Add screen, not a synthetic downloader or an
 * implicit service start. Remote-only users can ignore it and connect below.
 */
@Composable
internal fun PhoneFirstEntry(onOpenPhone: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("This device", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        ActionTile("Open phone downloads", ChunksColors.Cyan,
            onClick = onOpenPhone, modifier = Modifier.fillMaxWidth(),
            icon = R.drawable.chunks_ic_phone, stacked = false)
        Text("No server account is needed. Phone download and background recovery " +
            "are still being verified on Android devices.",
            style = MaterialTheme.typography.bodySmall)
    }
}
