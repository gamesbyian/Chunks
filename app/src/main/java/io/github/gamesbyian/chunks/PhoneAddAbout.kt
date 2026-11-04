package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

internal const val PHONE_ENGINE_SUMMARY =
    "Downloads and seeds with Chunks' own engine. Peers and trackers can see this phone's IP address."

internal const val PHONE_ENGINE_DETAILS =
    "While a transfer runs, an ongoing notification keeps it going in the background, " +
        "with Stop all. Android may still stop it, and from Android 15 limits this kind of " +
        "background transfer to about 6 hours a day, after which Chunks pauses everything. " +
        "Nothing connects to the network until you add a torrent. Transfers are remembered " +
        "and come back stopped after Chunks restarts."

/**
 * The phone Add form's engine explanation: the privacy-relevant fact is always
 * visible; the background-limit detail is one labelled tap away instead of a
 * long paragraph between the Add controls and the destination.
 */
@Composable
internal fun PhoneEngineAbout() {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(PHONE_ENGINE_SUMMARY, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { open = !open },
            modifier = Modifier.heightIn(min = 48.dp)
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }) {
            Text(if (open) "Hide background details" else "About phone downloads in the background")
        }
        if (open) Text(PHONE_ENGINE_DETAILS, style = MaterialTheme.typography.bodySmall)
    }
}
