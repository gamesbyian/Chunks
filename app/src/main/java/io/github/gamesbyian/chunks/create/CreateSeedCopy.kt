package io.github.gamesbyian.chunks.create

import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/**
 * "Seed a copy on this phone" for a created torrent. The phone engine can only
 * seed real files, and picked files are provider documents, so Chunks copies
 * them into a NEW folder in a verified download Place and seeds that copy.
 * The originals are never touched, and nothing existing is overwritten.
 *
 * Success is claimed only after (1) the copy rebuilds to the same info hash
 * and (2) the engine has verified every piece. Anything else is refused.
 */
internal object CreateSeedCopy {
    /** Kept free beyond the copy itself, so seeding never fills the phone. */
    const val SPACE_MARGIN = 64L * 1024 * 1024

    sealed class Outcome {
        data class Seeding(val infoHash: String, val copy: File) : Outcome()
        data class Refused(val reason: String) : Outcome()
    }

    fun copyVerifyAndSeed(
        request: TorrentMetainfoBuilder.Request,
        expected: TorrentMetainfoBuilder.Result,
        root: File,
        engine: LocalTorrentEngine,
        onProgress: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        verifyTimeoutMs: Long = 120_000
    ): Outcome {

        fun safeSegment(value: String) = value.isNotEmpty() && value != "." && value != ".." &&
            value.toByteArray(Charsets.UTF_8).size <= 1024 &&
            value.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }
        if (!safeSegment(request.name) ||
            request.entries.isEmpty() || request.entries.size > TorrentMetainfoBuilder.MAX_FILES ||
            request.entries.any { entry ->
                entry.path.isEmpty() || entry.path.size > 64 ||
                    entry.path.any { !safeSegment(it) } || entry.sizeBytes < 0
            }) return Outcome.Refused("The torrent has an unsafe copy destination. Nothing was copied.")
        val base = root.toPath()
        if (!root.isAbsolute || !Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(base))
            return Outcome.Refused("The copy destination is unavailable or indirect.")
        val target = File(root, request.name)
        val targetPath = target.toPath()
        // File.exists() follows links and can miss dangling symlinks.
        if (!Files.notExists(targetPath, LinkOption.NOFOLLOW_LINKS)) return Outcome.Refused(
            "Something named \"${request.name}\" is already in this folder. Nothing was copied or replaced.")
        if (root.usableSpace < SPACE_MARGIN ||
            root.usableSpace - SPACE_MARGIN < expected.totalBytes)
            return Outcome.Refused("Not enough free space here for a copy. Nothing was copied.")

        val single = request.entries.size == 1 && request.entries[0].path == listOf(request.name)
        val ownedFiles = ArrayList<java.nio.file.Path>()
        val ownedDirs = ArrayList<java.nio.file.Path>()
        val ownedDirSet = HashSet<java.nio.file.Path>()
        // Delete only known created entries. Never recursively traverse a
        // folder that could now contain someone else's files.
        fun cleanup(): Boolean {
            var clear = true
            for (path in ownedFiles.asReversed()) {
                try {
                    if (Files.isSymbolicLink(path)) { clear = false; continue }
                    Files.deleteIfExists(path)
                    if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) clear = false
                } catch (_: Exception) { clear = false }
            }
            for (path in ownedDirs.asReversed()) {
                try {
                    if (Files.isSymbolicLink(path)) { clear = false; continue }
                    Files.deleteIfExists(path) // fails if unowned entries appeared
                    if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) clear = false
                } catch (_: Exception) { clear = false }
            }
            return clear
        }

        val copies = try {
            if (!single) {
                // Exclusive directory creation prevents reusing a foreign
                // folder (or a pre-existing dangling symlink).
                Files.createDirectory(targetPath)
                ownedDirs.add(targetPath)
                ownedDirSet.add(targetPath)
            }
            var copied = 0L
            request.entries.map { entry ->
                val path = if (single) targetPath else {
                    var parent = targetPath
                    for (segment in entry.path.dropLast(1)) {
                        parent = parent.resolve(segment)
                        if (ownedDirSet.add(parent)) {
                            Files.createDirectory(parent)
                            ownedDirs.add(parent)
                        }
                    }
                    parent.resolve(entry.path.last())
                }
                check(path.startsWith(if (single) base else targetPath)) {
                    "Copy path escapes its destination"
                }
                // Write the final file through an EXCLUSIVE non-following
                // descriptor. No predictable .chunks-part file or rename.
                FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { out ->
                    ownedFiles.add(path)
                    entry.open().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var zeroReads = 0
                        var remaining = entry.sizeBytes
                        while (true) {
                            if (isCancelled()) throw TorrentMetainfoBuilder.Cancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) {
                                check(++zeroReads <= 16) { "Copy source stopped making progress" }
                                continue
                            }
                            zeroReads = 0
                            // A document/provider may change after the metainfo
                            // was built. Never let it consume an unbounded
                            // amount of space before the later hash check.
                            check(count.toLong() <= remaining &&
                                count.toLong() <= expected.totalBytes - copied) {
                                "Copy source is larger than its reviewed torrent"
                            }
                            val chunk = ByteBuffer.wrap(buffer, 0, count)
                            while (chunk.hasRemaining()) {
                                check(out.write(chunk) > 0) { "Copy write stopped making progress" }
                            }
                            copied += count
                            remaining -= count
                            onProgress(copied)
                        }
                        check(remaining == 0L) { "Copy source is shorter than its reviewed torrent" }
                    }
                    out.force(true)
                }
                val written = path.toFile()
                TorrentMetainfoBuilder.Entry(entry.path, written.length()) { written.inputStream() }
            }
        } catch (e: Exception) {
            val cleared = cleanup()
            val suffix = if (cleared) "The partial copy was removed."
                else "Partial files remain for manual review; no foreign directories were recursively deleted."
            return Outcome.Refused(
                (if (e is TorrentMetainfoBuilder.Cancelled) "Cancelled."
                    else "The copy failed (${e.message ?: "read or write error"}).") + " " + suffix
            )
        }
        val rebuilt = try {
            TorrentMetainfoBuilder.build(TorrentMetainfoBuilder.Request(request.name, copies, request.trackerTiers,
                request.private, expected.pieceLength, request.comment, request.creationDate))
        } catch (e: Exception) { null }
        if (rebuilt?.infoHashV1 != expected.infoHashV1) return Outcome.Refused(
            "The files changed after the torrent was created, so the copy does not match it. " +
                "The copy is kept at ${target.path}; create the torrent again to seed it.")

        val added = engine.addStopped(expected.torrent, root)
        if (added !is LocalAddResult.AddedStopped) return Outcome.Refused(
            ((added as? LocalAddResult.Refused)?.reason ?: "The phone engine did not accept the torrent") +
                ". The copy is kept at ${target.path}.")
        val hash = added.status.infoHash
        if (engine.resume(hash).outcome != LocalCommandOutcome.VERIFIED) return Outcome.Refused(
            "The phone engine did not confirm starting. The torrent is added and stopped; start it from On this phone.")
        val deadline = System.currentTimeMillis() + verifyTimeoutMs
        while (engine.status(hash)?.progressPpm != 1_000_000) {
            if (System.currentTimeMillis() > deadline) {
                engine.pause(hash)
                return Outcome.Refused("The phone engine could not verify every piece of the copy, so it is " +
                    "not seeding. The torrent is stopped in On this phone.")
            }
            Thread.sleep(200)
        }
        return Outcome.Seeding(hash, target)
    }
}
