package io.github.gamesbyian.chunks.rpc

import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * Read-only proof, taken before a typed Remove + data, that no OTHER job in the
 * same client lists a payload file at one of this job's exact paths.
 *
 * Transmission, qBittorrent and Deluge delete the files of the job they are
 * asked about and never ask whether a cross-seeded sibling still needs them, so
 * the check cannot be left to the daemon. Local and rTorrent prove this inside
 * their own adapters ([TorrentClient.provesNoSharedPayloadBeforeDelete]).
 *
 * Anything that cannot be read or interpreted refuses: an unreadable sibling is
 * never "no conflict". The inventory and this job's layout are read again after
 * the sibling reads, so a job added, moved or renamed during the preflight also
 * refuses. A writer that acts between the last read and the dispatch cannot be
 * excluded by any read-only protocol; that window is narrowed, not closed.
 */
internal object ClientPayloadOverlap {
    /** Above this many same-folder siblings the file lists are not compared; Remove + data refuses. */
    const val MAX_SIBLINGS = 300

    const val UNREADABLE = "Could not check this client's other transfers for shared files. Nothing was deleted"
    const val CHANGED = "This client's transfers changed while checking for shared files. Nothing was deleted"
    const val SHARED = "Another transfer in this client uses some of these files. Nothing was deleted"
    const val TOO_MANY = "Too many other transfers share this folder to check their files. Nothing was deleted"

    private class Scan(val signature: List<String>, val targetFiles: List<ClientFile>, val refusal: String?)

    /** Null only when every other job provably lists no file at one of [target]'s paths. */
    suspend fun refusal(client: TorrentClient, target: ClientTorrent): String? {
        val first = scan(client, target, compare = true)
        first.refusal?.let { return it }
        val second = scan(client, target, compare = false)
        second.refusal?.let { return it }
        return if (first.signature != second.signature || first.targetFiles != second.targetFiles) CHANGED else null
    }

    private suspend fun scan(client: TorrentClient, target: ClientTorrent, compare: Boolean): Scan {
        try {
            val targetDirectory = safeDirectory(target.downloadDirectory)
                ?: return Scan(emptyList(), emptyList(), UNREADABLE)
            val inventory = client.torrents()
            val signature = inventory.map { it.hash.lowercase(Locale.ROOT) + "|" + it.downloadDirectory }.sorted()
            val targetFiles = client.files(target.hash)
            if (!compare) return Scan(signature, targetFiles, null)
            if (inventory.none { it.hash.equals(target.hash, ignoreCase = true) })
                return Scan(signature, targetFiles, UNREADABLE)
            if (targetFiles.any { !safeRelativePath(it.path) }) return Scan(signature, targetFiles, UNREADABLE)
            if (targetFiles.isEmpty()) return Scan(signature, targetFiles, null)
            val selected = targetFiles.map { full(targetDirectory, it.path) }.toSet()
            val siblings = ArrayList<ClientTorrent>()
            for (other in inventory) {
                if (other.hash.equals(target.hash, ignoreCase = true)) continue
                // A relative, dot-segment or unreported folder cannot prove non-overlap.
                val otherDirectory = safeDirectory(other.downloadDirectory)
                    ?: return Scan(signature, targetFiles, UNREADABLE)
                if (nested(targetDirectory, otherDirectory) || nested(otherDirectory, targetDirectory)) siblings += other
            }
            if (siblings.size > MAX_SIBLINGS) return Scan(signature, targetFiles, TOO_MANY)
            for (other in siblings) {
                val otherFiles = client.files(other.hash)
                if (otherFiles.any { !safeRelativePath(it.path) }) return Scan(signature, targetFiles, UNREADABLE)
                val otherDirectory = requireNotNull(safeDirectory(other.downloadDirectory))
                if (otherFiles.any { full(otherDirectory, it.path) in selected })
                    return Scan(signature, targetFiles, SHARED)
            }
            return Scan(signature, targetFiles, null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Scan(emptyList(), emptyList(), UNREADABLE)
        }
    }

    private fun full(directory: String, path: String) =
        (directory.trimEnd('/') + "/" + path).lowercase(Locale.ROOT)

    private fun nested(path: String, ancestor: String) =
        ancestor == "/" || path == ancestor || path.startsWith("$ancestor/")

    private fun safeDirectory(path: String?): String? {
        if (path == null || !path.startsWith("/") || path.length > 4096 || path.any { it.isISOControl() }) return null
        if (path.split('/').any { it == "." || it == ".." }) return null
        return path.trimEnd('/').ifEmpty { "/" }
    }

    private fun safeRelativePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith("/") && path.length <= 4096 && path.none { it.isISOControl() } &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}
