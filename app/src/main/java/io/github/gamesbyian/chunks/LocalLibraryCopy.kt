package io.github.gamesbyian.chunks

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.LibraryCopyFile
import io.github.gamesbyian.chunks.model.ZipExtractionResult
import io.github.gamesbyian.chunks.model.ZipExtractionStatus
import io.github.gamesbyian.chunks.model.ZipExtractor
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The torrent engine intentionally bounds file enumeration. A published copy
 * must never infer "all wanted files are complete" from a truncated prefix.
 * Ask for one extra row to distinguish exactly 10,000 files from 10,001+.
 */
internal object LocalLibraryCopySelection {
    const val MAX_FILES = 10_000
    const val PROBE_LIMIT = MAX_FILES + 1

    fun completeWanted(files: List<io.github.gamesbyian.chunks.engine.LocalTransferFile>):
        List<io.github.gamesbyian.chunks.engine.LocalTransferFile>? {
        if (files.size > MAX_FILES) return null
        return files.filter { it.wanted }.takeIf { wanted ->
            wanted.isNotEmpty() && wanted.all { it.sizeBytes >= 0L && it.doneBytes == it.sizeBytes }
        }
    }
}

/** The finished, wanted files of one transfer, laid out relative to a new library folder. */
internal class LocalLibraryCopySource(val sourceRoot: File, val files: List<LibraryCopyFile>) {
    val bytes: Long get() = files.sumOf { it.sizeBytes }

    companion object {
        /**
         * Null unless every wanted file is complete. A multi-file torrent's own top
         * folder becomes the new folder, so its contents are not nested twice.
         */
        fun of(engine: LocalTorrentEngine, transfer: LocalTransferStatus): LocalLibraryCopySource? {
            // A 10,000-row response is ambiguous unless the 10,001st row is
            // probed. Fail closed if an extra row exists; never publish a
            // possibly incomplete multi-file collection.
            val scanned = engine.files(transfer.infoHash, LocalLibraryCopySelection.PROBE_LIMIT) ?: return null
            val wanted = LocalLibraryCopySelection.completeWanted(scanned) ?: return null
            val save = File(transfer.savePath)
            val top = wanted.map { it.path.substringBefore('/', "") }.distinct().singleOrNull()
            val nested = !top.isNullOrEmpty() && wanted.all { it.path.contains('/') }
            val root = if (nested) File(save, top!!) else save
            return LocalLibraryCopySource(root, wanted.map {
                val relative = if (nested) it.path.substringAfter('/') else it.path
                LibraryCopyFile(File(root, relative), relative, it.sizeBytes)
            })
        }
    }
}

/**
 * "Copy to a folder…" for a finished transfer: pick a folder, review, then a
 * verified copy into a new folder. The downloaded originals keep seeding.
 */
@Composable
internal fun LocalLibraryCopyPanel(transfer: LocalTransferStatus) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember(transfer.infoHash) { mutableStateOf<LocalLibraryCopySource?>(null) }
    var treeUri by remember(transfer.infoHash) { mutableStateOf<Uri?>(null) }
    var plan by remember(transfer.infoHash) { mutableStateOf<LocalZipExtractionPlan?>(null) }
    var running by remember(transfer.infoHash) { mutableStateOf(false) }
    var result by remember(transfer.infoHash) { mutableStateOf<ZipExtractionResult?>(null) }
    val cancel = remember(transfer.infoHash) { AtomicBoolean(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        treeUri = picked
        result = null
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                try { LocalEngineHolder.current()?.let { LocalLibraryCopySource.of(it, transfer) } } catch (_: Exception) { null }
            }
            source = found
            if (found == null) {
                result = ZipExtractionResult(ZipExtractionStatus.BLOCKED, null, null, 0, 0,
                    "Copy unavailable: files may be unfinished, metadata missing, or more than 10,000 files are present. No library copy was started.")
                return@launch
            }
            plan = withContext(Dispatchers.IO) {
                LocalZipExtractionPlan(transfer.name, LocalZipExtraction.destinationLabel(context, picked),
                    ZipExtractor.folderBaseName(transfer.name), found.bytes, found.files.size,
                    LocalZipExtraction.availableBytes(context, picked), copy = true)
            }
        }
    }

    val currentPlan = plan
    val currentResult = result
    when {
        running -> LocalZipExtractionRunning(onCancel = { cancel.set(true) }, copy = true)
        currentPlan != null -> LocalZipExtractionConfirm(currentPlan,
            onConfirm = { acknowledgedUnknownSpace ->
                val destination = treeUri ?: return@LocalZipExtractionConfirm
                val files = source ?: return@LocalZipExtractionConfirm
                cancel.set(false)
                running = true
                scope.launch {
                    result = withContext(Dispatchers.IO) {
                        try {
                            LocalZipExtraction.copyFinished(context, files.sourceRoot, files.files, transfer.name,
                                destination, acknowledgedUnknownSpace) { cancel.get() }
                        } catch (_: Exception) {
                            ZipExtractionResult(ZipExtractionStatus.NEEDS_ATTENTION, null, null, 0, 0,
                                "Copying stopped unexpectedly; check the destination folder")
                        }
                    }
                    plan = null
                    running = false
                }
            },
            onDismiss = { plan = null; treeUri = null })
        else -> {
            if (currentResult != null) LocalZipExtractionOutcome(currentResult, transfer.name, copy = true)
            OutlinedButton(onClick = { folderPicker.launch(null) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Copy to a folder…") }
        }
    }
}
