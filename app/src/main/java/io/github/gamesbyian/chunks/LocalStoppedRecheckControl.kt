package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalCommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Recheck files" for a STOPPED local job in canonical Details. libtorrent reads
 * every piece and stops the job again by itself; nothing downloads, uploads or
 * writes payload. Shares the local change lock with Start/Stop.
 */
@Composable
internal fun LocalStoppedRecheckControl(
    infoHash: String,
    recheck: (String) -> LocalCommandResult? = { LocalEngineHolder.current()?.recheckStopped(it) }
) {
    var busy by remember(infoHash) { mutableStateOf(false) }
    var outcome by remember(infoHash) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = {
                if (busy) return@OutlinedButton
                busy = true
                outcome = "Checking every piece. The transfer stays stopped."
                scope.launch {
                    val fenced = LocalMutationFence.revision.beginMutation()
                    val result = if (!fenced) null else try {
                        withContext(Dispatchers.IO) { recheck(infoHash) }
                    } finally { LocalMutationFence.revision.settledMutation() }
                    outcome = recheckOutcomeText(fenced, result)
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag(ChunksSemantics.DETAILS_LOCAL_RECHECK)
        ) { Text(if (busy) "Checking files…" else "Recheck files") }
        Text("Reads the files already on this device and recounts verified pieces. " +
            "Nothing is downloaded or changed.", style = MaterialTheme.typography.bodySmall)
        outcome?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

internal fun recheckOutcomeText(fenced: Boolean, result: LocalCommandResult?): String {
    if (!fenced) return "Another change to this device's transfers is in progress. Nothing was checked."
    val status = result?.status
    return when (result?.outcome) {
        LocalCommandOutcome.VERIFIED -> {
            val percent = (status?.progressPpm ?: 0) / 10_000.0
            "Check finished: " + String.format(java.util.Locale.ROOT, "%.1f", percent) +
                "% verified on this device. The transfer is still stopped."
        }
        LocalCommandOutcome.UNCERTAIN ->
            "The check is still running. The transfer stops again by itself when it finishes, or you can Stop it now."
        LocalCommandOutcome.REFUSED ->
            "This transfer can be rechecked only while it is stopped with known files."
        LocalCommandOutcome.NOT_FOUND, null ->
            "This transfer is no longer on this device. Nothing was checked."
    }
}
