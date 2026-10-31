package io.github.gamesbyian.chunks

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.model.Units
import io.github.gamesbyian.chunks.model.ZipExtractionResult
import io.github.gamesbyian.chunks.model.ZipExtractionStatus
import io.github.gamesbyian.chunks.model.ZipExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the user must see before approving a write. */
internal data class LocalZipExtractionPlan(
    val archiveLabel: String,
    val destinationLabel: String,
    val folderName: String,
    val outputBytes: Long,
    val fileCount: Int,
    val freeBytes: Long?,
    /** A library copy of a finished transfer rather than a ZIP extraction. */
    val copy: Boolean = false
)

/**
 * Explain why destination writing is temporarily unavailable; ZIP review remains available.
 */
@Composable
internal fun LocalZipExtractionPanel(sourceUri: Uri, review: LocalZipReview) {
    if (review.verification == null) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Extract", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            Text("Extraction is temporarily unavailable while destination file safety is being improved. You can still review the ZIP.")
        }
    }
}

@Composable
internal fun LocalZipExtractionConfirm(
    plan: LocalZipExtractionPlan,
    onConfirm: (acknowledgedUnknownSpace: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var acknowledged by remember(plan) { mutableStateOf(false) }
    val reserve = maxOf(16L * 1024 * 1024, plan.outputBytes / 20)
    val enoughSpace = plan.freeBytes?.let { it - minOf(it, reserve) >= plan.outputBytes }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (plan.copy) "Review copy" else "Review extraction", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            Text((if (plan.copy) "Transfer: " else "Archive: ") + plan.archiveLabel)
            Text("Into: " + plan.destinationLabel)
            Text("New folder: " + plan.folderName +
                " (a numbered name such as \"" + plan.folderName + " (2)\" is used if that name is taken)")
            Text("Writes " + plan.fileCount + " files, " + Units.bytes(plan.outputBytes) + ".")
            when (enoughSpace) {
                true -> Text("Free space: " + Units.bytes(requireNotNull(plan.freeBytes)) + ".")
                false -> Text("Not enough free space: " + Units.bytes(requireNotNull(plan.freeBytes)) +
                    " available, including a safety reserve. Choose another folder or free up space.",
                    color = MaterialTheme.colorScheme.error)
                null -> Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = acknowledged, role = Role.Checkbox,
                            onValueChange = { acknowledged = it }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = null)
                    Text("This folder can't report free space. " + (if (plan.copy) "Copy" else "Extract") +
                        " anyway; Chunks stops and removes partial files if writing fails.",
                        modifier = Modifier.padding(start = 8.dp))
                }
            }
            Text("Existing files and folders are not opened, merged or replaced. " + if (plan.copy)
                "The downloaded originals stay where they are and keep seeding." else "The ZIP itself is not changed or deleted.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onConfirm(enoughSpace == null && acknowledged) },
                enabled = enoughSpace == true || enoughSpace == null && acknowledged,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (plan.copy) "Copy to new folder" else "Extract to new folder")
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Choose a different folder")
            }
        }
    }
}

@Composable
internal fun LocalZipExtractionRunning(onCancel: () -> Unit, copy: Boolean = false) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (copy) "Copying and checking every file…" else "Extracting and checking every file…", fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text("Files are written into a hidden partial folder and only appear under their final name when every byte has been checked.")
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (copy) "Cancel copy" else "Cancel extraction")
            }
        }
    }
}

internal fun extractionMessage(result: ZipExtractionResult, copy: Boolean = false): String =
    if (copy) copyMessage(result) else when (result.status) {
        ZipExtractionStatus.EXTRACTED -> if (result.writtenFiles == 0 && result.reason != null)
            "An interrupted extraction had already finished into the folder \"" + result.folderName + "\"."
        else "Extracted " + result.writtenFiles + " files (" +
            Units.bytes(result.writtenBytes) + ") into the new folder \"" + result.folderName + "\". The ZIP was kept."
        ZipExtractionStatus.BLOCKED -> "Not extracted: " + result.reason + ". Nothing was written."
        ZipExtractionStatus.CANCELLED -> "Extraction cancelled. Partial files were removed; nothing else changed."
        ZipExtractionStatus.FAILED_ROLLED_BACK -> "Extraction failed: " + result.reason +
            ". Partial files were removed; nothing else changed."
        ZipExtractionStatus.NEEDS_ATTENTION -> "Needs attention: " + result.reason +
            ". Chunks did not change the destination during this check. Look for a folder starting with \"" +
            ZipExtractor.PARTIAL_PREFIX + "\" in the destination."
    }

private fun copyMessage(result: ZipExtractionResult): String = when (result.status) {
    ZipExtractionStatus.EXTRACTED -> "Copied " + result.writtenFiles + " files (" + Units.bytes(result.writtenBytes) +
        ") into the new folder \"" + result.folderName + "\". The originals keep seeding."
    ZipExtractionStatus.BLOCKED -> "Not copied: " + result.reason + ". Nothing was written."
    ZipExtractionStatus.CANCELLED -> "Copy cancelled. Partial files were removed; nothing else changed."
    ZipExtractionStatus.FAILED_ROLLED_BACK -> "Copy failed: " + result.reason +
        ". Partial files were removed; nothing else changed."
    ZipExtractionStatus.NEEDS_ATTENTION -> "Needs attention: " + result.reason +
        ". Chunks did not change the destination during this check. Look for a folder starting with \"" +
        ZipExtractor.PARTIAL_PREFIX + "\" in the destination."
}

@Composable
internal fun LocalZipExtractionOutcome(result: ZipExtractionResult, archiveLabel: String, copy: Boolean = false) {
    val success = result.status == ZipExtractionStatus.EXTRACTED
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(archiveLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(extractionMessage(result, copy),
                color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

/** Reports journals left by an earlier process death; resolving them never re-extracts. */
@Composable
internal fun LocalZipInterruptedExtractions() {
    val context = LocalContext.current
    var results by remember { mutableStateOf<List<LocalZipExtraction.Reconciled>>(emptyList()) }
    LaunchedEffect(Unit) {
        results = withContext(Dispatchers.IO) {
            try { LocalZipExtraction.reconcilePending(context) } catch (_: Exception) { emptyList() }
        }
    }
    if (results.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("An earlier extraction or copy was interrupted", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            results.forEach { Text(extractionMessage(it.result, it.copy)) }
        }
    }
}
