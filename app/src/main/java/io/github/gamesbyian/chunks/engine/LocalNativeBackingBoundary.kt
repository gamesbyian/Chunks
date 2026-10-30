package io.github.gamesbyian.chunks.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Temporary production containment until libtorrent4j exposes a disk writer
 * with exclusive, descriptor-pinned file ownership. The app's native writer
 * is not permitted to operate on shared public Downloads, removable media or
 * arbitrary folders. Older resume records stay on disk but are not re-added
 * to the native session when their root is outside this private sandbox.
 *
 * This is an isolation boundary, not a claim of O_EXCL native writes. Call it
 * at Add, restoration, metadata admission AND each Start; it must never be
 * bypassed through an alternative Add overload.
 */
internal object LocalNativeBackingBoundary {
    const val REFUSAL =
        "Native downloads are restricted to Chunks-private storage to protect existing files. " +
        "Shared Downloads and SD destinations require a non-clobbering native writer."

    fun refusal(privateRoot: File, requested: File): String? {
        val base = try { privateRoot.toPath().toAbsolutePath().normalize() }
            catch (_: Exception) { return REFUSAL }
        val destination = try { requested.toPath().toAbsolutePath().normalize() }
            catch (_: Exception) { return REFUSAL }
        if (!destination.startsWith(base)) return REFUSAL
        try {
            // No follow at the root or along any child directory. /data/user
            // may be an OS-created alias, so do not reject its ancestors.
            if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(base) || !Files.isWritable(base))
                return REFUSAL
            var current = base
            val relative = base.relativize(destination)
            for (part in relative) {
                current = current.resolve(part)
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(current) || !Files.isWritable(current))
                    return REFUSAL
            }
            if (!destination.toRealPath().startsWith(base.toRealPath())) return REFUSAL
        } catch (_: Exception) {
            return REFUSAL
        }
        return null
    }
}
