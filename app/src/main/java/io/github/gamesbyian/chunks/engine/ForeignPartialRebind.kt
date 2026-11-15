package io.github.gamesbyian.chunks.engine

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Explicit, reversible adoption of user-approved foreign partial files
 * (FOREIGN-PARTIAL-TORRENT-RECOVERY P1). Each approved candidate is renamed
 * inside its own directory from `<declared><suffix>` to the declared name so
 * libtorrent's ordinary hash check can verify it. Rename is never a claim of
 * validity: the caller must force a real recheck before reuse.
 *
 * Guarantees: only importable candidates from a fresh scan; the destination must
 * be absent (never replaces); symlinks and non-regular files refused; no copy,
 * delete or cross-directory move; if any step fails every completed rename is
 * reversed, and the returned [Receipt] can undo a successful adoption later.
 */
internal object ForeignPartialRebind {
    data class Receipt(val renames: List<Pair<String, String>>) // relative from -> relative to

    sealed interface Result {
        data class Adopted(val receipt: Receipt) : Result
        data class Refused(val problem: String, val rolledBack: Boolean) : Result
    }

    fun adopt(root: File, approved: List<ForeignPartialLayoutScanner.Candidate>): Result {
        if (approved.isEmpty()) return Result.Refused("Nothing approved", true)
        if (approved.any { !it.kind.importable }) return Result.Refused("Auxiliary state cannot be adopted", true)
        if (approved.any { it.sharedOrUnknownLinks })
            return Result.Refused("A partial file shares its data with another file name", true)
        if (approved.map { it.declaredPath }.toSet().size != approved.size)
            return Result.Refused("Several candidates approved for one file", true)
        // Revalidate against the live filesystem instead of trusting a stale scan.
        val fresh = ForeignPartialLayoutScanner.scan(root, approved.map { it.declaredPath })
        if (fresh !is ForeignPartialLayoutScanner.Result.Found)
            return Result.Refused((fresh as ForeignPartialLayoutScanner.Result.Unknown).problem, true)
        if (fresh.ambiguous.isNotEmpty() && fresh.ambiguous.any { d -> approved.any { it.declaredPath == d } })
            return Result.Refused("Candidate set changed or is ambiguous", true)
        val base = root.toPath().toAbsolutePath().normalize().toRealPath()
        val done = ArrayList<Pair<Path, Path>>()
        fun rollback(): Boolean = rollbackMovedFiles(base, done)
        for (c in approved) {
            val match = fresh.candidates.firstOrNull {
                it.declaredPath == c.declaredPath && it.kind == c.kind && it.sizeBytes == c.sizeBytes
            } ?: return Result.Refused("Candidate changed since review", rollback())
            // The fresh scan re-read the link count; writes through a hard link would change another file.
            if (match.sharedOrUnknownLinks)
                return Result.Refused("A partial file shares its data with another file name", rollback())
            val to = base.resolve(match.declaredPath)
            val from = base.resolve(match.relativeCandidatePath)
            try {
                require(from.startsWith(base) && to.startsWith(base))
                if (!Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS))
                    return Result.Refused("Candidate is no longer a regular file", rollback())
                // Explicitly reject an occupied destination regardless of provider
                // implementation details for Files.move without REPLACE_EXISTING.
                if (Files.exists(to, LinkOption.NOFOLLOW_LINKS))
                    return Result.Refused("Destination appeared during adoption", rollback())
                // The scan is not a permanent lease. Re-read the link count as
                // close to the mutation as possible; an unknown count is unsafe.
                if (PayloadLinkCount.of(from) != 1)
                    return Result.Refused("Candidate shares data with another file name", rollback())
                Files.move(from, to)
                done += from to to
                // A late hard link made after the scan must not reach native
                // piece-check/write. Undo this rename before returning to Add.
                if (PayloadLinkCount.of(to) != 1)
                    return Result.Refused("Candidate became shared during adoption", rollback())
            } catch (_: FileAlreadyExistsException) {
                return Result.Refused("Destination appeared during adoption", rollback())
            } catch (_: Exception) {
                return Result.Refused("Rename failed", rollback())
            }
        }
        return Result.Adopted(Receipt(done.map { base.relativize(it.first).toString().replace(File.separatorChar, '/') to
            base.relativize(it.second).toString().replace(File.separatorChar, '/') }))
    }

    /**
     * Reverses all still-safe renames after a partially failed adoption.
     * Never short-circuit on one failed reverse: every other file still needs
     * its own independent recovery attempt. Never overwrite an occupied name.
     */
    internal fun rollbackMovedFiles(base: Path, done: List<Pair<Path, Path>>): Boolean {
        var allRestored = true
        for ((from, to) in done.asReversed()) {
            val restored = try {
                if (!withinDirectParents(base, from) || !withinDirectParents(base, to) ||
                    !Files.isRegularFile(to, LinkOption.NOFOLLOW_LINKS) ||
                    Files.exists(from, LinkOption.NOFOLLOW_LINKS)) false
                else { Files.move(to, from); true }
            } catch (_: Exception) { false }
            if (!restored) allRestored = false
        }
        return allRestored
    }

    /** A parent symbolic link must never redirect a rollback outside the reviewed root. */
    private fun withinDirectParents(base: Path, child: Path): Boolean {
        if (child == base || !child.normalize().startsWith(base)) return false
        var parent = child.parent
        while (parent != null && parent != base) {
            if (Files.isSymbolicLink(parent) ||
                !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return false
            parent = parent.parent
        }
        return parent == base
    }

    /**
     * Reverse an accepted adoption only if every receipt path and name still
     * qualifies. Preflight ALL entries before moving the first file; otherwise
     * an occupied original near the end would cause a needlessly partial undo.
     * A concurrent filesystem change can still prevent full rollback, so the
     * result must be checked by the caller rather than presumed successful.
     */
    fun undo(root: File, receipt: Receipt): Boolean {
        val base = try { root.toPath().toAbsolutePath().normalize().toRealPath() }
            catch (_: Exception) { return false }
        if (receipt.renames.isEmpty()) return false
        val done = ArrayList<Pair<Path, Path>>()
        for ((from, to) in receipt.renames) {
            val src = base.resolve(from).normalize()
            val dst = base.resolve(to).normalize()
            if (src == dst || !withinDirectParents(base, src) || !withinDirectParents(base, dst) ||
                !Files.isRegularFile(dst, LinkOption.NOFOLLOW_LINKS) ||
                Files.exists(src, LinkOption.NOFOLLOW_LINKS) ||
                done.any { (priorSrc, priorDst) ->
                    src == priorSrc || dst == priorDst || src == priorDst || dst == priorSrc
                }) return false
            done += src to dst
        }
        return rollbackMovedFiles(base, done)
    }
}
