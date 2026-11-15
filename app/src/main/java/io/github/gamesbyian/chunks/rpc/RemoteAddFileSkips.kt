package io.github.gamesbyian.chunks.rpc

import io.github.gamesbyian.chunks.DeclaredAddFile

/**
 * What the stopped-first Add hook established before an optional Start.
 * [startAllowed] = false keeps a verified-stopped job stopped: Start is never
 * sent when the person's reviewed file choices could not be confirmed.
 */
internal data class RemotePreStartSetup(val note: String?, val startAllowed: Boolean = true)

/**
 * "Files to download" for a remote .torrent Add. The job is loaded STOPPED,
 * each reviewed skipped file is set to rTorrent priority 0 through the existing
 * identity-checked [RTorrentRemote.setFilePriority] (never replayed), and only
 * when every skip reads back as 0 may the requested Start follow. Skipping
 * never deletes bytes. Mirrors the local Add's reviewed-skip rule.
 */
internal object RemoteAddFileSkips {
    /** rTorrent's f.path is relative to the torrent root for a multi-file torrent. */
    internal fun withinRoot(declaredPath: String): String =
        if ('/' in declaredPath) declaredPath.substringAfter('/') else declaredPath

    /**
     * The registered job's own file rows for the reviewed skips, matched by
     * native metainfo index AND path AND byte size. Null when the server's
     * layout does not match what the person reviewed: then nothing is written.
     */
    internal fun match(skipped: List<DeclaredAddFile>, files: List<RemoteFile>): List<Pair<Int, RemoteFile>>? =
        skipped.map { declared ->
            val row = files.getOrNull(declared.index) ?: return null
            if (row.sizeBytes != declared.sizeBytes ||
                (row.path != withinRoot(declared.path) && row.path != declared.path)) return null
            declared.index to row
        }

    internal fun confirmedNote(count: Int) =
        "Skipping $count " + (if (count == 1) "file" else "files") + " as chosen; they will not download."

    internal fun unconfirmedNote(startRequested: Boolean) =
        if (startRequested) "The files you chose to skip could not be confirmed as skipped, so the transfer " +
            "was not started. Review its files in Details before starting."
        else "The files you chose to skip could not be confirmed as skipped. Review its files in Details before starting."

    suspend fun apply(
        remote: RTorrentRemote,
        hash: String,
        skipped: List<DeclaredAddFile>,
        startRequested: Boolean
    ): RemotePreStartSetup {
        if (skipped.isEmpty()) return RemotePreStartSetup(null)
        val refused = RemotePreStartSetup(unconfirmedNote(startRequested), startAllowed = false)
        val files = try {
            remote.listFiles(hash)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return refused
        }
        val matched = match(skipped, files) ?: return refused
        for ((index, row) in matched) {
            val outcome = try {
                remote.setFilePriority(hash, index, row.path, row.sizeBytes, row.priority, 0)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // A require/check refusal happens before any write for this file.
                return refused
            }
            val confirmed = (outcome is FilePriorityOutcome.Verified && outcome.priority == 0) ||
                (outcome is FilePriorityOutcome.Unchanged && outcome.priority == 0)
            // An uncertain write is never repeated, and no later file is touched.
            if (!confirmed) return refused
        }
        return RemotePreStartSetup(confirmedNote(matched.size))
    }
}
