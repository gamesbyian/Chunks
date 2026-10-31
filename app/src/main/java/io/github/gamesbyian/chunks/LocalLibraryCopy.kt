package io.github.gamesbyian.chunks

import androidx.compose.material3.Text
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
 * Publication remains unavailable while destination ownership safety is addressed.
 */
@Composable
internal fun LocalLibraryCopyPanel(transfer: LocalTransferStatus) {
    Text("Copy to a folder is temporarily unavailable while destination file safety is being improved.")
}
