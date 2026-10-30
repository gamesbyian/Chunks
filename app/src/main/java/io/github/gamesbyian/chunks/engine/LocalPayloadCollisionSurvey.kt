package io.github.gamesbyian.chunks.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale

/**
 * Non-mutating, point-in-time survey of candidate libtorrent payload paths.
 * A clear result is NOT a write authorization: it cannot defeat races after
 * inspection and does not prove that the selected root is owned by Chunks.
 *
 * Existing files are always classified as collisions, even if their size or
 * name resembles torrent contents. Reuse/rehydration must have a separate,
 * explicitly reviewed native hash-check path. Paths that cannot be inspected
 * or whose *payload* parents traverse symbolic links are UNKNOWN. System
 * directory aliases in the selected root's ancestors are resolved to their
 * real path before inspecting children (including Android /data/user aliases).
 */
internal object LocalPayloadCollisionSurvey {
    internal sealed interface Result {
        data class ClearAtObservation(val checked: Int) : Result
        data class ExistingPaths(val paths: List<String>, val total: Int) : Result
        data class Unknown(val problem: String) : Result
    }

    // Validate the native metadata count BEFORE allocating a Java/Kotlin path
    // list. The survey's maxPaths check would otherwise occur too late.
    const val MAX_NATIVE_PATHS = 100_000

    fun supportsNativeFileCount(count: Int): Boolean = count in 1..MAX_NATIVE_PATHS

    fun inspect(root: File, relativePaths: List<String>, maxPaths: Int = MAX_NATIVE_PATHS): Result {
        if (maxPaths <= 0 || relativePaths.size > maxPaths)
            return Result.Unknown("Payload path count is unsupported")
        val base = try {
            val supplied = root.toPath().toAbsolutePath()
            // Normalizing a lexical link/../child can select a different
            // directory from the one reached when the OS follows that link.
            if (supplied.any { it.toString() == ".." })
                return Result.Unknown("Download root contains parent traversal")
            val lexical = supplied.normalize()
            // The chosen directory itself must not be a symlink. Its ancestor
            // aliases can be OS-managed (/data/user/0 on Android), however:
            // inspecting lexical descendants would miss the real destination.
            if (!Files.isDirectory(lexical, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(lexical))
                return Result.Unknown("Download root is unavailable or indirect")
            lexical.toRealPath().also { actual ->
                if (!Files.isDirectory(actual, LinkOption.NOFOLLOW_LINKS))
                    return Result.Unknown("Resolved download root is unavailable")
            }
        } catch (_: Exception) { return Result.Unknown("Download root cannot be inspected") }
        // Case-sensitive phone storage may distinguish these lexical names,
        // while FAT/exFAT and providers may map them to a single file. Reject
        // a conservative case-folded and Unicode-normalized namespace, not
        // only byte-identical names. Actual provider rules can vary.
        fun aliasKey(path: String) = Normalizer.normalize(
            path, Normalizer.Form.NFKC
        ).uppercase(Locale.ROOT).lowercase(Locale.ROOT)
        val declaredFiles = HashSet<String>()
        for (relative in relativePaths) {
            if (relative.length > 4096)
                return Result.Unknown("Torrent payload path is too long")
            if (!declaredFiles.add(aliasKey(relative)))
                return Result.Unknown("Torrent declares potentially aliased payload paths")
        }
        for (relative in relativePaths) {
            var slash = relative.indexOf('/')
            while (slash >= 0) {
                if (declaredFiles.contains(aliasKey(relative.substring(0, slash))))
                    return Result.Unknown("Torrent declares a file as another file's parent")
                slash = relative.indexOf('/', slash + 1)
            }
        }
        val collisions = ArrayList<String>()
        var total = 0
        for (relative in relativePaths) {
            if (relative.isBlank() || relative.startsWith('/') || relative.contains('\\') ||
                relative.any { it.code < 32 || it.code == 127 })
                return Result.Unknown("Torrent contains an ambiguous payload path")
            val segments = relative.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." })
                return Result.Unknown("Torrent payload path is not a relative child")
            try {
                var path: Path = base
                for ((index, segment) in segments.withIndex()) {
                    path = path.resolve(segment)
                    if (!path.startsWith(base))
                        return Result.Unknown("Torrent payload path escapes download root")
                    val exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    val absent = Files.notExists(path, LinkOption.NOFOLLOW_LINKS)
                    if (!exists && !absent)
                        return Result.Unknown("Payload path existence cannot be determined")
                    if (exists && Files.isSymbolicLink(path))
                        return Result.Unknown("Torrent payload path crosses a symbolic link")
                    if (index < segments.lastIndex && exists &&
                        !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                        return Result.Unknown("Torrent payload parent is not a directory")
                    if (index == segments.lastIndex && exists) {
                        total++
                        if (collisions.size < 20) collisions += relative
                    }
                }
            } catch (_: Exception) {
                return Result.Unknown("Torrent payload path cannot be inspected")
            }
        }
        return if (total == 0) Result.ClearAtObservation(relativePaths.size)
        else Result.ExistingPaths(collisions, total)
    }
}
