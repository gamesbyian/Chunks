package io.github.gamesbyian.chunks.create

import io.github.gamesbyian.chunks.engine.LocalAddResult
import io.github.gamesbyian.chunks.engine.LocalCommandOutcome
import io.github.gamesbyian.chunks.engine.LocalTorrentEngine
import java.io.File

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
        val target = File(root, request.name)
        if (target.exists()) return Outcome.Refused(
            "Something named \"${request.name}\" is already in this folder. Nothing was copied or replaced.")
        if (root.usableSpace < expected.totalBytes + SPACE_MARGIN) return Outcome.Refused(
            "Not enough free space here for a copy. Nothing was copied.")
        val single = request.entries.size == 1 && request.entries[0].path == listOf(request.name)
        val created = ArrayList<File>()
        val copies = try {
            var copied = 0L
            request.entries.map { entry ->
                val file = if (single) target else File(target, entry.path.joinToString(File.separator))
                // Stay inside the new folder whatever the provider called things.
                check(file.canonicalPath.startsWith(target.canonicalPath)) { "Unsafe file name" }
                file.parentFile?.mkdirs()
                val part = File(file.path + ".chunks-part")
                created += part
                entry.open().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (isCancelled()) throw TorrentMetainfoBuilder.Cancelled()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(copied)
                        }
                        output.fd.sync()
                    }
                }
                check(part.renameTo(file)) { "Could not finish copying ${file.name}" }
                created -= part; created += file
                TorrentMetainfoBuilder.Entry(entry.path, file.length()) { file.inputStream() }
            }
        } catch (e: Exception) {
            // Only the files this attempt just created; never seeded, never the user's originals.
            created.forEach { it.delete() }
            if (!single) target.deleteRecursively()
            return Outcome.Refused(if (e is TorrentMetainfoBuilder.Cancelled) "Cancelled. The partial copy was removed."
                else "The copy failed (${e.message ?: "read or write error"}). The partial copy was removed.")
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
