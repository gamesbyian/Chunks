package io.github.gamesbyian.chunks

import android.content.ContentResolver
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.gamesbyian.chunks.rpc.TorrentMetainfoReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one engine-neutral application of the global original-`.torrent`
 * Keep/Archive/Delete policy after an Add, shared by rTorrent and the
 * Transmission/qBittorrent/Deluge clients (Local applies the same
 * [SourceTorrentArchive] inside its native sequence).
 *
 * Only a verified NEW exact job ([verifiedNewHash] non-null) may archive a
 * private copy or request deletion; an uncertain, exact-session or
 * Start-unverified result always keeps the original. Delete is never
 * performed here: it becomes a separate request the person must confirm.
 */
internal object OriginalSourceRetirement {
    class Result(val note: String?, val deletion: OriginalDeletionRequest?)

    suspend fun afterAdd(
        context: Context,
        frozen: RemoteAddSession.FrozenOriginalHandling,
        exactEnvironmentId: String,
        verifiedNewHash: String?
    ): Result {
        if (frozen.handling == OriginalTorrentHandling.KEEP) return Result(null, null)
        val verifiedNew = verifiedNewHash
            ?: return Result("The original .torrent file was kept because this Add did not verify a new transfer.", null)
        val archived = try {
            withContext(Dispatchers.IO) {
                SourceTorrentArchive(context.filesDir).apply(frozen.handling,
                    OriginalTorrentOrigin.ANDROID_DOCUMENT, frozen.bytes,
                    SourceAddVerification(exactEnvironmentId, verifiedNew, verifiedNew = true))
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) { SourceArchiveOutcome.IoFailure }
        suspend fun request(archivedCopy: Boolean) = OriginalDeletionRequest(frozen.document,
            withContext(Dispatchers.IO) {
                OriginalTorrentDocuments.displayName(context.contentResolver, frozen.document)
            }, OriginalTorrentDocuments.sha256(frozen.bytes),
            runCatching { TorrentMetainfoReader.preview(frozen.bytes).name }.getOrDefault("this transfer"),
            archived = archivedCopy)
        if (archived == SourceArchiveOutcome.OriginalDeletionRequiresApproval)
            return Result(null, request(archivedCopy = false))
        // Archive prefers a move: removing an original its app lets us delete is a separate question.
        val deletion = if (archived is SourceArchiveOutcome.ArchivedCopy && frozen.deletable)
            request(archivedCopy = true) else null
        return Result(archiveOutcomeMessage(archived, frozen.handling), deletion)
    }
}

/** A separately approved original-document action remains bound to its exact reviewed request. */
internal class OriginalAddDocumentState {
    var deletion by mutableStateOf<OriginalDeletionRequest?>(null)
    var note by mutableStateOf<String?>(null)
    var deleting by mutableStateOf(false)
        private set

    fun keep(request: OriginalDeletionRequest) {
        if (deleting || deletion !== request) return
        deletion = null
        note = if (request.archived)
            "The original .torrent file was kept; Chunks also keeps its archived copy."
        else "The original .torrent file was kept."
    }

    fun delete(resolver: ContentResolver, scope: CoroutineScope, request: OriginalDeletionRequest) {
        if (deleting || deletion !== request) return
        deleting = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    OriginalTorrentDocuments.deleteReviewed(resolver, request)
                }
                if (deletion === request) note = originalDeletionMessage(result, request.archived)
            } finally {
                if (deletion === request) deletion = null
                deleting = false
            }
        }
    }
}

@Composable
internal fun OriginalAddDocumentStatus(state: OriginalAddDocumentState, scope: CoroutineScope) {
    val resolver = LocalContext.current.applicationContext.contentResolver
    state.deletion?.let { request ->
        OriginalDeletionPrompt(request, busy = state.deleting,
            onDelete = { state.delete(resolver, scope, request) },
            onKeep = { state.keep(request) })
    }
    state.note?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}
