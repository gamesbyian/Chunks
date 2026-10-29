package io.github.gamesbyian.chunks.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Non-mutating, point-in-time survey of candidate libtorrent payload paths.
 * A clear result is NOT a write authorization: it cannot defeat races after
 * inspection and does not prove that the selected root is owned by Chunks.
 *
 * Existing files are always classified as collisions, even if their size or
 * name resembles torrent contents. Reuse/rehydration must have a separate,
 * explicitly reviewed native hash-check path. Paths that cannot be inspected
 * or whose parents traverse symbolic links are UNKNOWN rather than safe.
 */
internal object LocalPayloadCollisionSurvey {
    internal sealed interface Result {
        data class ClearAtObservation(val checked: Int) : Result
        data class ExistingPaths(val paths: List<String>, val total: Int) : Result
        data class Unknown(val problem: String) : Result
    }

    fun inspect(root: File, relativePaths: List<String>, maxPaths: Int = 100_000): Result {
        if (maxPaths <= 0 || relativePaths.size > maxPaths)
            return Result.Unknown("Payload path count is unsupported")
        val base = try {
            root.toPath().toAbsolutePath().normalize().also {
                if (Files.isSymbolicLink(it) || !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS))
                    return Result.Unknown("Download root is unavailable or indirect")
            }
        } catch (_: Exception) { return Result.Unknown("Download root cannot be inspected") }
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
