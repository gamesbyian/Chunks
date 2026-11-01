package io.github.gamesbyian.chunks

import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import io.github.gamesbyian.chunks.model.LibraryCopyByteTotals
import io.github.gamesbyian.chunks.model.LibraryCopyFile
import java.io.File

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
    val bytes: Long = requireNotNull(LibraryCopyByteTotals.checked(files)) {
        "A library copy cannot exceed the supported total file size"
    }

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
            val selected = wanted.map {
                val relative = if (nested) it.path.substringAfter('/') else it.path
                LibraryCopyFile(File(root, relative), relative, it.sizeBytes)
            }
            if (LibraryCopyByteTotals.checked(selected) == null) return null
            return LocalLibraryCopySource(root, selected)
        }
    }
}

/**
 * First bounded completed-file path uses a genuinely new platform Downloads row.
 * Do not enable legacy generic SAF library-copy or recursive folder publication.
 */
@Composable
internal fun LocalLibraryCopyPanel(
    transfer: LocalTransferStatus,
    engineProvider: () -> LocalTorrentEngine? = { LocalEngineHolder.current() },
    onResult: ((LocalSingleLibraryMediaStoreCopy.Result) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by androidx.compose.runtime.remember(transfer.infoHash) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var outcome by androidx.compose.runtime.remember(transfer.infoHash) {
        androidx.compose.runtime.mutableStateOf<LocalSingleLibraryMediaStoreCopy.Result?>(null)
    }
    androidx.compose.material3.Card {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            Text("Copy a finished file", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text("Save one completed, flat .txt, .json, .bin, .torrent or .chunksnap file (up to 8 MiB) as a NEW Downloads/Chunks Exports document. The original keeps seeding. Multi-file folders, larger files, other types, selected SAF destinations and Replace are not available yet.")
            androidx.compose.material3.Button(
                onClick = {
                    busy = true
                    scope.launch {
                        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val engine = engineProvider()
                            if (engine == null) LocalSingleLibraryMediaStoreCopy.Result(
                                LocalSingleLibraryMediaStoreCopy.State.BLOCKED,
                                "The local transfer engine is unavailable; no copy was attempted.")
                            else try {
                                LocalSingleLibraryMediaStoreCopy.copyNew(context, engine, transfer.infoHash)
                            } catch (_: Exception) {
                                LocalSingleLibraryMediaStoreCopy.Result(
                                    LocalSingleLibraryMediaStoreCopy.State.NEEDS_ATTENTION,
                                    "Could not confirm the Downloads copy; no automatic deletion or retry occurred.")
                            }
                        }
                        outcome = result
                        onResult?.invoke(result)
                        busy = false
                    }
                },
                enabled = !busy && outcome == null,
                modifier = androidx.compose.ui.Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) { Text(if (busy) "Checking completed file…" else "Copy single file to Downloads") }
            outcome?.let { result ->
                Text(result.message + (result.publishedName?.let { " Name: " + it } ?: ""),
                    color = if (result.state == LocalSingleLibraryMediaStoreCopy.State.COPIED)
                        androidx.compose.material3.MaterialTheme.colorScheme.primary
                    else androidx.compose.material3.MaterialTheme.colorScheme.error)
            }
        }
    }
}
