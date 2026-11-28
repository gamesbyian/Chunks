package io.github.gamesbyian.chunks.rpc

import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * Read-only, bounded, same-client path-mapping check before typed Remove + data.
 *
 * Transmission, qBittorrent and Deluge delete the files of the job they are
 * asked about and never ask whether a cross-seeded sibling still needs them.
 * Local and rTorrent prove this inside their own adapters.
 *
 * Both scans must observe the reviewed target directory and the same complete
 * roster of registrations. Both compare overlapping directory trees and their
 * file mappings; a final inventory brackets the last file-list reads. This
 * narrows, but cannot eliminate, an external writer's race. Reported paths
 * are not independent filesystem evidence of hardlinks or symlink aliases.
 */
internal object ClientPayloadOverlap {
    const val MAX_SIBLINGS = 300

    const val UNREADABLE = "Could not check this client's other transfers for shared files. Nothing was deleted"
    const val CHANGED = "This client's transfers changed while checking for shared files. Nothing was deleted"
    const val SHARED = "Another transfer in this client uses some of these files. Nothing was deleted"
    const val TOO_MANY = "Too many other transfers share this folder to check their files. Nothing was deleted"

    private data class Scan(
        val targetFiles: List<ClientFile> = emptyList(),
        val layouts: Map<String, String> = emptyMap(),
        val refusal: String? = null
    )

    /** Null only after two matching, disjoint observations of the reported mappings. */
    suspend fun refusal(client: TorrentClient, target: ClientTorrent): String? {
        try {
            val exact = ClientHashes.requireHash(target.hash)
            val reviewed = safeDirectory(target.downloadDirectory) ?: return UNREADABLE
            val initial = inventory(client) ?: return UNREADABLE
            if (initial[exact] != reviewed) return CHANGED
            val first = scan(client, exact, reviewed, initial)
            first.refusal?.let { return it }

            val confirming = inventory(client) ?: return UNREADABLE
            if (confirming != initial) return CHANGED
            val second = scan(client, exact, reviewed, confirming)
            second.refusal?.let { return it }
            if (first.targetFiles != second.targetFiles || first.layouts != second.layouts) return CHANGED

            // A registration or directory change during the last file reads is
            // also a refusal, not permission to operate on the earlier target.
            val final = inventory(client) ?: return UNREADABLE
            return if (final == initial) null else CHANGED
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return UNREADABLE
        }
    }

    private suspend fun inventory(client: TorrentClient): Map<String, String>? {
        val directories = mutableMapOf<String, String>()
        for (row in client.torrents()) {
            val hash = ClientHashes.requireHash(row.hash)
            val directory = safeDirectory(row.downloadDirectory) ?: return null
            if (directories.put(hash, directory) != null) return null
        }
        return directories
    }

    private suspend fun scan(
        client: TorrentClient, targetHash: String, targetDirectory: String,
        directories: Map<String, String>
    ): Scan {
        val targetFiles = client.files(targetHash)
        if (layoutSignature(targetFiles) == null) return Scan(refusal = UNREADABLE)
        val selected = targetFiles.map { full(targetDirectory, it.path) }.toSet()
        val siblings = directories.filter { (hash, directory) ->
            hash != targetHash &&
                (nested(targetDirectory, directory) || nested(directory, targetDirectory))
        }
        if (siblings.size > MAX_SIBLINGS) return Scan(refusal = TOO_MANY)
        val layouts = mutableMapOf<String, String>()
        for ((hash, directory) in siblings) {
            val otherFiles = client.files(hash)
            val layout = layoutSignature(otherFiles) ?: return Scan(refusal = UNREADABLE)
            if (otherFiles.any { full(directory, it.path) in selected }) return Scan(refusal = SHARED)
            layouts[hash] = layout
        }
        return Scan(targetFiles, layouts)
    }

    /** Retain only bounded digests of sibling rosters between scans, not their full inventories.
     * Progress/priority changes do not change a sibling's physical mapping. A missing metadata
     * roster, duplicate index, negative length or unsafe pathname cannot prove non-overlap. */
    private fun layoutSignature(files: List<ClientFile>): String? {
        if (files.isEmpty() || !ClientFileLayout.sameRoster(files, files) ||
            files.any { !safeRelativePath(it.path) }) return null
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in files.sortedBy { it.index }) {
            // Control characters (including newline) cannot occur in a validated path.
            digest.update("${file.index}:${file.sizeBytes}:${file.path}\n".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun full(directory: String, path: String) =
        (directory.trimEnd('/') + "/" + path).lowercase(Locale.ROOT)

    // Use the same conservative case-folding for directory ancestry and file
    // equality, so a possible case-insensitive alias cannot bypass the scan.
    private fun nested(path: String, ancestor: String): Boolean {
        val p = path.lowercase(Locale.ROOT)
        val a = ancestor.lowercase(Locale.ROOT)
        return a == "/" || p == a || p.startsWith("$a/")
    }

    private fun safeDirectory(path: String?): String? {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.length > 4096 ||
            path.any { it.isISOControl() || it == '\\' }) return null
        val normalized = path.trimEnd('/').ifEmpty { "/" }
        if (normalized != "/" && normalized.removePrefix("/").split('/').any {
                it.isEmpty() || it == "." || it == ".."
            }) return null
        return normalized
    }

    private fun safeRelativePath(path: String): Boolean =
        path.isNotBlank() && !path.startsWith("/") && path.length <= 4096 &&
            path.none { it.isISOControl() || it == '\\' } &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}
