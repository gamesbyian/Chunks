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
        if (startRequested) "The file choices you made could not be confirmed, so the transfer " +
            "was not started. Review its files in Details before starting."
        else "The file choices you made could not be confirmed. Review its files in Details before starting."

    internal fun highNote(count: Int) =
        "Raising $count " + (if (count == 1) "file" else "files") + " to High priority as chosen."

    suspend fun apply(
        remote: RTorrentRemote,
        hash: String,
        skipped: List<DeclaredAddFile>,
        startRequested: Boolean,
        /** Wanted files the person raised to High in the same review. */
        high: List<DeclaredAddFile> = emptyList()
    ): RemotePreStartSetup {
        if (skipped.isEmpty() && high.isEmpty()) return RemotePreStartSetup(null)
        require(high.none { h -> skipped.any { it.index == h.index } }) { "A file is either skipped or High" }
        val refused = RemotePreStartSetup(unconfirmedNote(startRequested), startAllowed = false)
        val files = try {
            remote.listFiles(hash)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return refused
        }
        // Every reviewed choice must match the registered layout before ANY write.
        val toSkip = match(skipped, files) ?: return refused
        val toRaise = match(high, files) ?: return refused
        for ((target, rows) in listOf(0 to toSkip, 2 to toRaise)) for ((index, row) in rows) {
            val outcome = try {
                remote.setFilePriority(hash, index, row.path, row.sizeBytes, row.priority, target)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // A require/check refusal happens before any write for this file.
                return refused
            }
            val confirmed = (outcome is FilePriorityOutcome.Verified && outcome.priority == target) ||
                (outcome is FilePriorityOutcome.Unchanged && outcome.priority == target)
            // An uncertain write is never repeated, and no later file is touched.
            if (!confirmed) return refused
        }
        val notes = listOfNotNull(toSkip.size.takeIf { it > 0 }?.let(::confirmedNote),
            toRaise.size.takeIf { it > 0 }?.let(::highNote))
        return RemotePreStartSetup(notes.joinToString(" "))
    }
}
