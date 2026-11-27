package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Explicit read only; no preview, native flush or file read is launched on composition. */
@Composable
internal fun LocalFileInspectionPanel(
    infoHash: String,
    engineProvider: () -> LocalDetailsPort?
) {
    val scope = rememberCoroutineScope()
    var review by remember(infoHash) { mutableStateOf<LocalFileInspection.Review?>(null) }
    var expanded by remember(infoHash) { mutableStateOf(false) }
    var selected by remember(infoHash) { mutableStateOf<LocalTransferFile?>(null) }
    var outcome by remember(infoHash) { mutableStateOf<LocalFileInspection.Outcome?>(null) }
    var notice by remember(infoHash) { mutableStateOf<String?>(null) }
    var supplied by remember(infoHash) { mutableStateOf("") }
    var includeLegacy by remember(infoHash) { mutableStateOf(false) }
    var comparisonAlgorithm by remember(infoHash) { mutableStateOf(LocalChecksumComparison.Algorithm.SHA256) }
    var page by remember(infoHash) { mutableStateOf(0) }
    var task by remember(infoHash) { mutableStateOf<Job?>(null) }
    val progressFlow = remember(infoHash) { MutableStateFlow(0L) }
    val progress by progressFlow.collectAsState()
    val busy = task != null
    fun runRead(action: suspend () -> Unit) {
        if (task != null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                action()
            } catch (cancel: CancellationException) {
                outcome = null
                notice = "Inspection cancelled. No checksum was accepted."
                throw cancel
            } catch (_: Exception) {
                outcome = LocalFileInspection.Outcome.Refused("Independent inspection could not be completed.")
            } finally {
                task = null
            }
        }
        task = job
        job.start()
    }

    fun loadReview() {
        expanded = true
        review = null
        page = 0
        selected = null
        outcome = null
        supplied = ""
        notice = null
        runRead {
            review = withContext(Dispatchers.IO) { engineProvider()?.checksumReview(infoHash) }
            if (review == null)
                notice = "No eligible files could be reviewed. This version supports completed wanted files of a complete local transfer, up to 64 GiB each."
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = {
                if (expanded) {
                    expanded = false
                    review = null
                    selected = null
                    outcome = null
                    supplied = ""
                    notice = null
                } else loadReview()
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text(if (expanded) "Hide checksum inspection" else "Inspect checksums…") }
        if (!expanded) return@Column
        Text("Independent SHA-256 and byte size. Reads the selected local file twice; this is not a new BitTorrent piece verification or a remote-storage check.",
            style = MaterialTheme.typography.bodySmall)
        notice?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        val shown = review
        if (shown != null) {
            OutlinedButton(onClick = { includeLegacy = !includeLegacy }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { stateDescription = if (includeLegacy) "On" else "Off" }) {
                Text("Include legacy checksums: " + if (includeLegacy) "On" else "Off")
            }
            Text("SHA-1 and MD5 are weak; CRC-32 is non-cryptographic. Optional legacy checksums are for comparison with older supplied values, not security proof.",
                style = MaterialTheme.typography.bodySmall)
            val pageSize = 8
            val lastPage = (shown.files.size - 1) / pageSize
            Text("Files " + (page * pageSize + 1) + "–" +
                minOf((page + 1) * pageSize, shown.files.size) + " of " + shown.files.size)
            shown.files.drop(page * pageSize).take(pageSize).forEach { file ->
                OutlinedButton(
                    onClick = {
                        selected = file
                        outcome = null
                        supplied = ""
                        comparisonAlgorithm = LocalChecksumComparison.Algorithm.SHA256
                        val requestedLegacy = includeLegacy
                        notice = null
                        progressFlow.value = 0
                        runRead {
                            val result = withContext(Dispatchers.IO) {
                                val worker = currentCoroutineContext()[Job]
                                val port = engineProvider()
                                port?.inspectChecksum(shown, file,
                                    cancelled = { worker?.isActive != true },
                                    includeLegacy = requestedLegacy,
                                    onProgress = { progressFlow.value = it })
                                    ?: LocalFileInspection.Outcome.Refused("The local client is unavailable.")
                            }
                            outcome = result
                        }
                    }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("Inspect " + file.path + " (" + file.sizeBytes + " bytes)") }
            }
            if (lastPage > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { page-- }, enabled = !busy && page > 0,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Previous files") }
                    OutlinedButton(onClick = { page++ }, enabled = !busy && page < lastPage,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Next files") }
                }
            }
            OutlinedButton(onClick = ::loadReview, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Refresh file review") }
        }
        if (busy) {
            val file = selected
            Text(if (file == null) "Reviewing local files…" else
                "Reading " + progress + " of " + file.sizeBytes * 2 + " bytes (two passes)…")
            OutlinedButton(onClick = { task?.cancel() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel inspection") }
        }
        when (val result = outcome) {
            is LocalFileInspection.Outcome.Refused ->
                Text(result.reason, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            is LocalFileInspection.Outcome.Observed -> {
                Text("Two matching reads of " + result.file.path + " (" + result.file.sizeBytes + " bytes).",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                SelectionContainer { Text("SHA-256: " + result.sha256) }
                val checksums = mapOf(LocalChecksumComparison.Algorithm.SHA256 to result.sha256) + result.legacy
                result.legacy.forEach { (algorithm, digest) ->
                    SelectionContainer { Text(algorithm.label + ": " + digest) }
                }
                if (checksums.size > 1) {
                    checksums.keys.forEach { algorithm ->
                        OutlinedButton(onClick = { comparisonAlgorithm = algorithm; supplied = "" },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .semantics { stateDescription = if (comparisonAlgorithm == algorithm) "Selected" else "Not selected" }) {
                            Text("Compare " + algorithm.label)
                        }
                    }
                }
                val algorithm = comparisonAlgorithm.takeIf { it in checksums }
                    ?: LocalChecksumComparison.Algorithm.SHA256
                OutlinedTextField(value = supplied, onValueChange = { supplied = it.take(LocalChecksumComparison.MAX_INPUT + 1) },
                    label = { Text("Compare supplied " + algorithm.label) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Paste a digest or one checksum-file row naming this exact relative path: " + result.file.path,
                    style = MaterialTheme.typography.bodySmall)
                when (LocalChecksumComparison.compare(algorithm, requireNotNull(checksums[algorithm]), supplied, result.file.path)) {
                    LocalChecksumComparison.State.NONE -> Unit
                    LocalChecksumComparison.State.INVALID -> Text("Enter " + algorithm.digits + " hexadecimal characters or one supported checksum-file row.")
                    LocalChecksumComparison.State.WRONG_FILE -> Text("This checksum row does not name the exact reviewed file.")
                    LocalChecksumComparison.State.MATCHES -> Text("Matches supplied checksum. Its origin is not verified.")
                    LocalChecksumComparison.State.DIFFERS -> Text("Does not match supplied checksum. No files were changed.")
                }
                result.textPreview?.let { preview ->
                    Text(if (result.previewTruncated) "UTF-8 text preview (first 64 KiB; truncated)"
                        else "UTF-8 text preview", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer { Text(preview, style = MaterialTheme.typography.bodySmall) }
                }
            }
            null -> Unit
        }
    }
}
