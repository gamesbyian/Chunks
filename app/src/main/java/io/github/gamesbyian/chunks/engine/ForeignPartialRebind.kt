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
        fun rollback(): Boolean = done.asReversed().all { (from, to) ->
            try { Files.move(to, from); true } catch (_: Exception) { false }
        }
        for (c in approved) {
            val match = fresh.candidates.firstOrNull {
                it.declaredPath == c.declaredPath && it.kind == c.kind && it.sizeBytes == c.sizeBytes
            } ?: return Result.Refused("Candidate changed since review", rollback())
            val to = base.resolve(match.declaredPath)
            val from = base.resolve(match.relativeCandidatePath)
            try {
                require(from.startsWith(base) && to.startsWith(base))
                if (!Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS))
                    return Result.Refused("Candidate is no longer a regular file", rollback())
                Files.move(from, to) // fails if destination exists; no REPLACE_EXISTING
                done += from to to
            } catch (_: FileAlreadyExistsException) {
                return Result.Refused("Destination appeared during adoption", rollback())
            } catch (_: Exception) {
                return Result.Refused("Rename failed", rollback())
            }
        }
        return Result.Adopted(Receipt(done.map { base.relativize(it.first).toString().replace(File.separatorChar, '/') to
            base.relativize(it.second).toString().replace(File.separatorChar, '/') }))
    }

    /** Reverses [adopt]; refuses (touching nothing further) if a source name is occupied or a file vanished. */
    fun undo(root: File, receipt: Receipt): Boolean {
        val base = try { root.toPath().toAbsolutePath().normalize().toRealPath() } catch (_: Exception) { return false }
        for ((from, to) in receipt.renames.asReversed()) {
            val src = base.resolve(from); val dst = base.resolve(to)
            if (!src.startsWith(base) || !dst.startsWith(base)) return false
            try {
                if (!Files.isRegularFile(dst, LinkOption.NOFOLLOW_LINKS) ||
                    Files.exists(src, LinkOption.NOFOLLOW_LINKS)) return false
                Files.move(dst, src)
            } catch (_: Exception) { return false }
        }
        return true
    }
}
