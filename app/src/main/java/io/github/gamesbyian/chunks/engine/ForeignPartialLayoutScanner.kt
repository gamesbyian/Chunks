package io.github.gamesbyian.chunks.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Read-only, bounded detection of other BitTorrent clients' incomplete-file
 * naming next to a torrent's declared payload paths (FOREIGN-PARTIAL-TORRENT-RECOVERY P1).
 *
 * This reports *candidates only*. A suffix match proves nothing about identity,
 * completeness or piece validity: no percent complete is derived, nothing is
 * renamed, deleted, opened for write or followed through a symbolic link, and
 * auxiliary sidecars (aria2 control files, BitComet piece fragments) are only
 * surfaced as untouched, unsupported state.
 */
internal object ForeignPartialLayoutScanner {
    enum class Kind(val suffix: String, val importable: Boolean) {
        QBITTORRENT(".!qB", true),
        TRANSMISSION(".part", true),
        UTORRENT(".!ut", true),
        /** BitTorrent (Windows, µTorrent-derived) "Append .!bt to incomplete files". */
        BITTORRENT(".!bt", true),
        BITCOMET(".bc!", true),
        /**
         * aria2 control file: unsupported auxiliary state, never trusted or removed.
         * aria2 writes BitTorrent payload at the exact declared paths (usually
         * preallocated) and keeps `<file>.aria2` beside a single-file torrent, or
         * `<torrent name>.aria2` beside a multi-file torrent's top directory. Those
         * exact files are ordinary exact-path reuse verified by libtorrent's check.
         */
        ARIA2_CONTROL(".aria2", false),
        /** BitComet partial-piece fragment: unsupported auxiliary state. */
        BITCOMET_PIECE_PART(".piece_part.bc!", false)
    }

    data class Candidate(
        val declaredPath: String,
        val relativeCandidatePath: String,
        val kind: Kind,
        val sizeBytes: Long,
        /**
         * True when the candidate shares its inode with another name (link count > 1)
         * or the count cannot be read. libtorrent writes into an adopted file in
         * place, which would silently change every other name of that inode, so
         * such candidates are disclosed but never adopted.
         */
        val sharedOrUnknownLinks: Boolean = false
    )

    sealed interface Result {
        /** [ambiguous] holds declared paths with more than one importable candidate. */
        data class Found(
            val candidates: List<Candidate>,
            val ambiguous: Set<String>,
            val truncated: Boolean
        ) : Result
        data class Unknown(val problem: String) : Result
    }

    const val MAX_DECLARED_PATHS = 20_000
    const val MAX_CANDIDATES = 500

    /**
     * Probes only the exact sibling names `<declared><suffix>` of declared paths whose exact
     * file is absent. Declared paths that already exist as regular files are the exact-path
     * reuse case and are not scanned for partial copies. aria2 control files are disclosed
     * wherever aria2 actually puts them (see [Kind.ARIA2_CONTROL]), including beside exact
     * files. Unsafe layouts yield [Result.Unknown].
     */
    fun scan(root: File, relativePaths: List<String>): Result {
        if (relativePaths.isEmpty() || relativePaths.size > MAX_DECLARED_PATHS)
            return Result.Unknown("Payload path count is unsupported")
        val base: Path = try {
            val lexical = root.toPath().toAbsolutePath()
            if (lexical.any { it.toString() == ".." }) return Result.Unknown("Download root contains parent traversal")
            val normal = lexical.normalize()
            if (Files.isSymbolicLink(normal) || !Files.isDirectory(normal, LinkOption.NOFOLLOW_LINKS))
                return Result.Unknown("Download root is unavailable or indirect")
            normal.toRealPath()
        } catch (_: Exception) { return Result.Unknown("Download root cannot be inspected") }

        val found = ArrayList<Candidate>()
        val importableByDeclared = HashMap<String, Int>()
        var truncated = false
        val aria2Seen = HashSet<String>()
        // A multi-file torrent's aria2 control file is named after its top directory.
        val top = relativePaths.first().substringBefore('/', "")
        if (top.isNotEmpty() && top != "." && top != ".." && relativePaths.all { it.startsWith("$top/") }) {
            aria2Seen += top
            try {
                aria2Control(base, top)?.let { found += Candidate(top, top + Kind.ARIA2_CONTROL.suffix, Kind.ARIA2_CONTROL, it) }
            } catch (_: Exception) { return Result.Unknown("Torrent payload path cannot be inspected") }
        }
        for (relative in relativePaths) {
            if (relative.isBlank() || relative.length > 4096 || relative.startsWith('/') ||
                relative.contains('\\') || relative.any { it.code < 32 || it.code == 127 })
                return Result.Unknown("Torrent contains an ambiguous payload path")
            val segments = relative.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." })
                return Result.Unknown("Torrent payload path is not a relative child")
            try {
                // Walk parents without crossing links; a missing parent means no sibling can exist.
                var dir: Path = base
                var parentsOk = true
                for (segment in segments.dropLast(1)) {
                    dir = dir.resolve(segment)
                    if (Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) { parentsOk = false; break }
                    if (Files.isSymbolicLink(dir)) return Result.Unknown("Torrent payload path crosses a symbolic link")
                    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
                        return Result.Unknown("Torrent payload parent is not a directory")
                }
                if (!parentsOk) continue
                val exact = dir.resolve(segments.last())
                if (relative !in aria2Seen) {
                    aria2Seen += relative
                    aria2Control(dir, segments.last())?.let { size ->
                        if (found.size >= MAX_CANDIDATES) truncated = true
                        else found += Candidate(relative, relative + Kind.ARIA2_CONTROL.suffix, Kind.ARIA2_CONTROL, size)
                    }
                }
                if (Files.exists(exact, LinkOption.NOFOLLOW_LINKS)) continue
                for (kind in Kind.values()) {
                    if (kind == Kind.ARIA2_CONTROL) continue
                    val sibling = dir.resolve(segments.last() + kind.suffix)
                    if (!Files.exists(sibling, LinkOption.NOFOLLOW_LINKS)) continue
                    // Symlinks, directories and special files are ignored, never followed.
                    if (!Files.isRegularFile(sibling, LinkOption.NOFOLLOW_LINKS)) continue
                    // The presentation cap must not turn a partially listed pair
                    // into an apparently unique reusable file. Keep counting
                    // importable siblings even when their rows are omitted.
                    if (kind.importable) importableByDeclared.merge(relative, 1, Int::plus)
                    if (found.size >= MAX_CANDIDATES) { truncated = true; continue }
                    found += Candidate(relative, relative + kind.suffix, kind, Files.size(sibling),
                        sharedOrUnknownLinks = kind.importable && PayloadLinkCount.of(sibling).let { it == null || it > 1 })
                }
            } catch (_: Exception) {
                return Result.Unknown("Torrent payload path cannot be inspected")
            }
        }
        val ambiguous = importableByDeclared.filterValues { it > 1 }.keys
        return Result.Found(found, ambiguous, truncated)
    }

    /** Size of a regular, non-link `<name>.aria2` in [dir], or null. Never opened. */
    private fun aria2Control(dir: Path, name: String): Long? {
        val control = dir.resolve(name + Kind.ARIA2_CONTROL.suffix)
        return if (Files.isRegularFile(control, LinkOption.NOFOLLOW_LINKS)) Files.size(control) else null
    }
}
