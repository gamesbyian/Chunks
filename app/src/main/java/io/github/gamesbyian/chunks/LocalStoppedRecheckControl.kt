package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalCommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * In-flight recheck runs and their last outcome, per exact infoHash. During
 * the check libtorrent unpauses the job, so stopped-only Details hides the
 * control mid-check; the run must outlive it, and its outcome must still be
 * there when the job stops itself and the control returns.
 */
internal object LocalRecheckRuns {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val running = mutableStateMapOf<String, Boolean>()
    val outcomes = mutableStateMapOf<String, String>()

    /**
     * False when a run for this exact hash is already in flight (single flight).
     * [awaitFenceMillis] lets the check that follows a stopped Add wait for that
     * Add's own change lock and readback to clear; it never waits past the bound.
     */
    fun start(infoHash: String, awaitFenceMillis: Long = 0,
              recheck: (String) -> LocalCommandResult?): Boolean {
        require(awaitFenceMillis in 0L..120_000L)
        synchronized(this) {
            if (running[infoHash] == true) return false
            running[infoHash] = true
        }
        outcomes[infoHash] = "Checking every piece. The transfer stops again by itself when the check ends."
        scope.launch {
            try {
                val deadline = System.nanoTime() + awaitFenceMillis * 1_000_000
                var fenced = LocalMutationFence.revision.beginMutation()
                while (!fenced && System.nanoTime() < deadline) {
                    delay(100)
                    fenced = LocalMutationFence.revision.beginMutation()
                }
                val result = if (!fenced) null else try { recheck(infoHash) }
                    finally { LocalMutationFence.revision.settledMutation() }
                outcomes[infoHash] = recheckOutcomeText(fenced, result)
            } catch (_: Exception) {
                outcomes[infoHash] = recheckOutcomeText(true, null)
            } finally {
                synchronized(this@LocalRecheckRuns) { running.remove(infoHash) }
            }
        }
        return true
    }
}

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
    val busy = LocalRecheckRuns.running[infoHash] == true
    val outcome = LocalRecheckRuns.outcomes[infoHash]
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = { LocalRecheckRuns.start(infoHash, recheck = recheck) },
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
