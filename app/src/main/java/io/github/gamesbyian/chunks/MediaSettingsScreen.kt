package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.media.ui.MediaSettingsRoute

/**
 * Options → Artwork & media info. One settings screen for every environment: media preferences are
 * app-wide presentation choices and never change a transfer, server, login or Add target. It holds
 * no transfer names, so it needs no transfer privacy gate. Scrolls as one column with IME padding,
 * so the provider-key field and Back stay reachable at 320dp and 200% text.
 */
@Composable
internal fun MediaSettingsScreen(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Artwork & media info", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        Text("For every environment's lists and details. Artwork and matches are labels only: " +
            "no download, file or server is ever changed.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Back")
        }
        MediaSettingsRoute(showHeading = false)
    }
}
