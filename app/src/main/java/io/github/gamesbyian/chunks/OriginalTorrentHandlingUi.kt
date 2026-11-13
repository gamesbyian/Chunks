package io.github.gamesbyian.chunks

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Saved default for what happens to a user's original .torrent *document*
 * after a verified new Add (SMART-ROUTING-AND-SOURCE-LIFECYCLE "Source file
 * handling"). Archive is the proposed default. Torrent payload is never involved.
 */
internal class OriginalTorrentHandlingStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("chunks_original_torrent_handling", Context.MODE_PRIVATE)

    val default: OriginalTorrentHandling
        get() = preferences.getString(KEY, null)
            ?.let { saved -> OriginalTorrentHandling.entries.firstOrNull { it.name == saved } }
            ?: OriginalTorrentHandling.ARCHIVE

    fun setDefault(handling: OriginalTorrentHandling): Boolean =
        preferences.edit().putString(KEY, handling.name).commit()

    private companion object { const val KEY = "default" }
}

internal val OriginalTorrentHandling.label: String get() = when (this) {
    OriginalTorrentHandling.KEEP -> "Keep original"
    OriginalTorrentHandling.ARCHIVE -> "Archive in Chunks"
    OriginalTorrentHandling.DELETE -> "Delete original"
}

internal val OriginalTorrentHandling.explanation: String get() = when (this) {
    OriginalTorrentHandling.KEEP -> "Chunks only reads the file."
    OriginalTorrentHandling.ARCHIVE -> "Keeps a private copy in Chunks after the Add is confirmed. The original stays where it is."
    OriginalTorrentHandling.DELETE -> "After the Add is confirmed, Chunks asks once more before deleting the .torrent file. Downloaded files are never touched."
}

internal fun originalHandlingTag(handling: OriginalTorrentHandling) =
    ChunksSemantics.ADD_ORIGINAL_HANDLING + "." + handling.name.lowercase()

/** One accessible single-choice group; every row is a full-width 48dp target. */
@Composable
internal fun OriginalTorrentHandlingChoice(
    title: String,
    selected: OriginalTorrentHandling,
    enabled: Boolean,
    onSelect: (OriginalTorrentHandling) -> Unit
) {
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        for (choice in OriginalTorrentHandling.entries) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(selected = choice == selected, enabled = enabled,
                        role = Role.RadioButton, onClick = { onSelect(choice) })
                    .testTag(originalHandlingTag(choice)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The row owns the click and semantics; the radio is visual only.
                RadioButton(selected = choice == selected, onClick = null, enabled = enabled)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(choice.label, fontWeight = FontWeight.Medium)
                    Text(choice.explanation, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Process-memory only: a lost request after process death simply keeps the original. */
internal class OriginalDeletionRequest(
    val document: Uri,
    val displayName: String,
    val reviewedSha256: ByteArray,
    val transferName: String
)

internal enum class OriginalDeletionOutcome { DELETED, CHANGED, REFUSED, UNCONFIRMED }

internal fun originalDeletionMessage(outcome: OriginalDeletionOutcome): String = when (outcome) {
    OriginalDeletionOutcome.DELETED -> "The original .torrent file was deleted. The transfer and its downloaded files are unchanged."
    OriginalDeletionOutcome.CHANGED -> "The original .torrent file changed or could not be read since you reviewed it, so it was kept."
    OriginalDeletionOutcome.REFUSED -> "Android or the app that holds the file did not allow Chunks to delete it. The original was kept."
    OriginalDeletionOutcome.UNCONFIRMED -> "Chunks asked to delete the original, but it may still exist. Check it in your Files app; nothing was retried."
}

internal object OriginalTorrentDocuments {
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Display name only, for the confirmation; never logged or persisted. */
    fun displayName(resolver: ContentResolver, document: Uri): String = try {
        resolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) { null }?.takeIf { it.isNotBlank() }?.take(160) ?: "this .torrent file"

    /**
     * Deletes exactly the reviewed document, only after its bytes still match the
     * reviewed SHA-256 and only through the document provider's own delete. Absence
     * on a fresh read is required before DELETED is reported. Never retried.
     */
    fun deleteReviewed(
        resolver: ContentResolver,
        request: OriginalDeletionRequest,
        delete: (Uri) -> Boolean = { DocumentsContract.deleteDocument(resolver, it) }
    ): OriginalDeletionOutcome {
        val current = try {
            SharedTorrentDocument.read(resolver, request.document).bytes
        } catch (_: Exception) { return OriginalDeletionOutcome.CHANGED }
        if (!MessageDigest.isEqual(sha256(current), request.reviewedSha256))
            return OriginalDeletionOutcome.CHANGED
        val accepted = try { delete(request.document) } catch (_: Exception) { false }
        if (!accepted) return OriginalDeletionOutcome.REFUSED
        return try {
            resolver.openInputStream(request.document)?.close()
            OriginalDeletionOutcome.UNCONFIRMED
        } catch (_: FileNotFoundException) {
            OriginalDeletionOutcome.DELETED
        } catch (_: SecurityException) {
            // A revoked grant after deletion cannot prove absence.
            OriginalDeletionOutcome.UNCONFIRMED
        } catch (_: Exception) {
            OriginalDeletionOutcome.UNCONFIRMED
        }
    }
}

/** The separate, explicit approval that a Delete-original choice always needs. */
@Composable
internal fun OriginalDeletionPrompt(
    request: OriginalDeletionRequest,
    busy: Boolean,
    onDelete: () -> Unit,
    onKeep: () -> Unit
) {
    Card(Modifier.fillMaxWidth().testTag(ChunksSemantics.ADD_ORIGINAL_DELETE_PROMPT)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Delete the original .torrent file?", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text("“" + request.displayName + "” was added as " + request.transferName +
                ". Deleting it removes only that .torrent file from the app or folder you opened it from. " +
                "The transfer and its downloaded files stay.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onDelete, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag(ChunksSemantics.ADD_ORIGINAL_DELETE_CONFIRM)) {
                Text("Delete original .torrent")
            }
            OutlinedButton(onClick = onKeep, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Keep it") }
        }
    }
}

internal fun archiveOutcomeMessage(outcome: SourceArchiveOutcome): String? = when (outcome) {
    is SourceArchiveOutcome.ArchivedCopy -> "A private copy of the .torrent is archived in Chunks; the original was kept."
    SourceArchiveOutcome.OriginalKept, SourceArchiveOutcome.OriginalDeletionRequiresApproval -> null
    SourceArchiveOutcome.NeedsReconciliation ->
        "The .torrent archive copy could not be confirmed as saved. The original was kept."
    SourceArchiveOutcome.ArchiveFull ->
        "The Chunks .torrent archive is full, so no copy was made. The original was kept."
    else -> "No archive copy of the .torrent was made. The original was kept."
}
