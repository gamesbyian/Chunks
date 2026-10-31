package io.github.gamesbyian.chunks.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.text.Normalizer
import java.util.Locale
import java.util.TreeMap

/**
 * Session-local exclusion of *declared backing names* across checked .torrent jobs.
 *
 * This is admission conflict detection between simultaneously registered
 * torrents, NOT proof of filesystem ownership or a race-free path lock.
 * The selected root authorizes libtorrent to recheck/reuse/replace existing
 * regular declared-path files; a custom owned-descriptor backend is not a
 * prerequisite. This reservation cannot authorize an escape or cleanup.
 *
 * Keep reservations for a running or stopped handle, not merely until Start,
 * so a second checked Add cannot target the same data while the first is live.
 */
internal class LocalCheckedPayloadReservations {
    internal sealed interface Result {
        data object Reserved : Result
        data class Refused(val problem: String) : Result
    }

    private val byPath = TreeMap<String, String>()
    private val byOwner = HashMap<String, Set<String>>()

    /**
     * Called after the non-mutating path/target survey, before native Add.
     * Resolve real roots to equate aliases such as /data/user/0 and /data/data
     * while conservatively folding names for FAT/provider namespaces.
     * Reservation excludes competing torrent writers, not regular payload reuse.
     */
    @Synchronized
    fun reserve(
        owner: String, root: File, relativePaths: List<String>,
        allowExistingOwner: Boolean = false
    ): Result {
        if (relativePaths.isEmpty() ||
            relativePaths.size > LocalPayloadCollisionSurvey.MAX_NATIVE_PATHS)
            return Result.Refused("Payload path count is unsupported")
        val realRoot = try {
            val lexical = root.toPath().toAbsolutePath()
            if (!Files.isDirectory(lexical, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(lexical) ||
                lexical.any { it.toString() == ".." })
                return Result.Refused("Download root is not directly inspectable")
            lexical.toRealPath()
        } catch (_: Exception) {
            return Result.Refused("Download root cannot be resolved")
        }
        val paths = LinkedHashSet<String>()
        for (relative in relativePaths) {
            if (relative.isBlank() || relative.length > 4096 ||
                relative.startsWith('/') || relative.contains('\\') ||
                relative.any { it.code < 32 || it.code == 127 } ||
                relative.split('/').any { it.isBlank() || it == "." || it == ".." })
                return Result.Refused("Torrent contains an ambiguous payload path")
            val full = fold(realRoot.resolve(relative).toString().replace(File.separatorChar, '/'))
            if (!paths.add(full))
                return Result.Refused("Torrent declares aliased backing paths")
        }
        // Explicit Start of an existing metadata-ready magnet may recheck
        // its own exact previously reserved path set; an external ancestor
        // alias retarget or changed native destination must never match.
        // Checked Add always uses the default false to prevent same-hash
        // resurrection after a non-blocking native Remove tombstones the job.
        byOwner[owner]?.let { previous ->
            return if (allowExistingOwner && previous == paths) Result.Reserved
            else Result.Refused("This transfer already reserves different download paths")
        }
        for (path in paths) {
            if (conflictsWithReserved(path))
                return Result.Refused("Another checked transfer already reserves these download paths")
        }
        // Commit the whole set only after validating every path; a refused Add
        // never leaves a partial reservation behind.
        paths.forEach { byPath[it] = owner }
        byOwner[owner] = paths
        return Result.Reserved
    }

    /** Same admitted magnet may be observed again at an explicit Start. */
    @Synchronized
    fun hasReservation(owner: String): Boolean = byOwner.containsKey(owner)

    @Synchronized
    fun release(owner: String) {
        byOwner.remove(owner)?.forEach { path ->
            if (byPath[path] == owner) byPath.remove(path)
        }
    }

    @Synchronized
    fun clear() {
        byOwner.clear()
        byPath.clear()
    }

    private fun conflictsWithReserved(path: String): Boolean {
        if (byPath.containsKey(path)) return true
        // Exact parent-file conflicts. A nearby lexical key is not necessarily
        // the nearest path ancestor, so TreeMap.floorKey alone is insufficient.
        var end = path.lastIndexOf('/')
        while (end > 0) {
            if (byPath.containsKey(path.substring(0, end))) return true
            end = path.lastIndexOf('/', end - 1)
        }
        // If a new payload path is the parent of any already-reserved file,
        // the first key at or above "path/" must begin with that prefix.
        val prefix = "$path/"
        return byPath.ceilingKey(prefix)?.startsWith(prefix) == true
    }

    private fun fold(path: String): String =
        Normalizer.normalize(path, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}
