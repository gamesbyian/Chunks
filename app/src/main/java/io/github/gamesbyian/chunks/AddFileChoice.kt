package io.github.gamesbyian.chunks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.gamesbyian.chunks.engine.LocalFileBatchOutcome
import io.github.gamesbyian.chunks.engine.LocalPayloadCollisionSurvey
import io.github.gamesbyian.chunks.engine.LocalTransferFile
import io.github.gamesbyian.chunks.model.Units
import org.libtorrent4j.TorrentInfo

/** One declared (non-pad) payload file, by libtorrent's own native index. */
internal data class DeclaredAddFile(val index: Int, val path: String, val sizeBytes: Long)

internal object AddFileChoices {
    /** Above this the per-file list is not shown at Add; Details offers the paged selector after Add. */
    const val MAX_LISTED = 300

    /** libtorrent's own view of the reviewed bytes (same as the engine's Add survey); null if unreadable. */
    fun declared(torrentBytes: ByteArray): List<DeclaredAddFile>? = try {
        val info = TorrentInfo.bdecode(torrentBytes)
        if (!info.isValid) null else {
            val files = info.files()
            val count = files.numFiles()
            if (!LocalPayloadCollisionSurvey.supportsNativeFileCount(count)) null
            else (0 until count).filterNot { files.padFileAt(it) }
                .map { DeclaredAddFile(it, files.filePath(it), files.fileSize(it)) }
        }
    } catch (_: Exception) { null }

    /**
     * The registered job's own files that the person chose to skip, matched by native
     * index AND path to what they reviewed. Null when the job no longer matches the
     * reviewed layout (then nothing is written).
     */
    fun reviewedSkips(jobFiles: List<LocalTransferFile>, skipped: Map<Int, String>): List<LocalTransferFile>? {
        if (skipped.isEmpty()) return emptyList()
        val byIndex = jobFiles.associateBy { it.index }
        return skipped.map { (index, path) ->
            byIndex[index]?.takeIf { it.path == path } ?: return null
        }
    }

    fun confirmed(outcome: LocalFileBatchOutcome?) =
        outcome == LocalFileBatchOutcome.VERIFIED || outcome == LocalFileBatchOutcome.UNCHANGED

    fun outcomeNote(outcome: LocalFileBatchOutcome?, skippedCount: Int, startRequested: Boolean): String? = when {
        skippedCount == 0 -> null
        confirmed(outcome) ->
            "Skipping " + skippedCount + (if (skippedCount == 1) " file" else " files") + " as chosen; they will not download."
        startRequested -> "The files you chose to skip could not be confirmed as skipped, so the transfer was not started. " +
            "Review its files in Details before starting."
        else -> "The files you chose to skip could not be confirmed as skipped. Review its files in Details before starting."
    }
}

/**
 * "Files to download" in the one Universal Add (local .torrent with 2+ files): every
 * file is selected by default; unticking only marks it to be skipped at Add. Nothing is
 * written until the explicit Add, and skipping never deletes bytes.
 */
@Composable
internal fun AddFileChoice(
    files: List<DeclaredAddFile>,
    skipped: Set<Int>,
    expanded: Boolean,
    enabled: Boolean,
    onExpanded: (Boolean) -> Unit,
    /** True when the same reviewed torrent could instead be sent to a server Add. */
    localOnlyNote: Boolean = false,
    onSkippedChange: (Set<Int>) -> Unit
) {
    val (chosen, chosenBytes) = androidx.compose.runtime.remember(files, skipped) {
        files.count { it.index !in skipped } to files.filter { it.index !in skipped }.sumOf { it.sizeBytes }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { onExpanded(!expanded) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(ChunksSemantics.ADD_FILE_CHOICE)
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
            Text("Files to download ($chosen of ${files.size} · ${Units.bytes(chosenBytes)})")
        }
        if (chosen == 0) Text("Choose at least one file to download.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (localOnlyNote) Text("File choices apply when adding on this device.",
            style = MaterialTheme.typography.bodySmall)
        if (!expanded) return@Column
        if (files.size > AddFileChoices.MAX_LISTED) {
            Text("This torrent has ${files.size} files. Add it stopped, then choose files in its Details before starting.",
                style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Text("Untick files you do not want. Skipped files are not downloaded; you can change this later in Details.",
            style = MaterialTheme.typography.bodySmall)
        for (file in files) {
            val selected = file.index !in skipped
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = selected, enabled = enabled, role = Role.Checkbox,
                    onValueChange = { want -> onSkippedChange(if (want) skipped - file.index else skipped + file.index) }),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                    Text(Units.bytes(file.sizeBytes) + (if (file.path.contains('/'))
                        " · " + file.path.substringBeforeLast('/') else ""),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
