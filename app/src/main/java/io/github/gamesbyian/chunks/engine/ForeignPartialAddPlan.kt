package io.github.gamesbyian.chunks.engine

import java.io.File

/**
 * Universal Add's view of foreign partial files (FOREIGN-PARTIAL-TORRENT-RECOVERY P1).
 *
 * [offer] is read-only: it reports which declared files have exactly one regular
 * `<declared><client suffix>` sibling that the person may choose to reuse. [adoptReviewed]
 * runs only after that explicit choice, on the IO lane, immediately before native Add. It
 * re-scans the live folder and renames only the reviewed candidates that are still byte-size
 * identical, never replacing anything. A rename is not progress: the native checked Add
 * verifies every piece, and [undo] restores the original names if Add is refused.
 */
internal object ForeignPartialAddPlan {
    data class Offer(
        /** Canonical folder the review scanned; adoption refuses any other folder. */
        val folder: String,
        val reusable: List<ForeignPartialLayoutScanner.Candidate>,
        /** Declared files with several plausible client copies: left untouched. */
        val ambiguousFiles: Int,
        /** aria2 control or BitComet fragment state: recognised, never trusted or removed. */
        val untouchedAuxiliary: Int,
        val truncated: Boolean
    ) {
        val reusableBytes: Long get() = reusable.sumOf { it.sizeBytes }
        val clients: List<ForeignPartialLayoutScanner.Kind> get() = reusable.map { it.kind }.distinct()
        val hasAnything: Boolean get() = reusable.isNotEmpty() || ambiguousFiles > 0 || untouchedAuxiliary > 0
    }

    sealed interface Adoption {
        data object NotRequested : Adoption
        data class Renamed(val receipt: ForeignPartialRebind.Receipt) : Adoption
        data class Refused(val problem: String) : Adoption
    }

    /** Null when nothing recognisable is present or the layout cannot be inspected safely. */
    fun offer(torrentBytes: ByteArray, folder: File): Offer? =
        ForeignPartialRecovery.declaredPaths(torrentBytes)?.let { offerForPaths(it, folder) }

    /** Same as [offer] for an existing job whose declared paths come from native metadata. */
    fun offerForPaths(declaredPaths: List<String>, folder: File): Offer? {
        if (!folder.isDirectory || declaredPaths.isEmpty()) return null
        val found = ForeignPartialLayoutScanner.scan(folder, declaredPaths)
            as? ForeignPartialLayoutScanner.Result.Found ?: return null
        val reusable = found.candidates.filter { it.kind.importable && it.declaredPath !in found.ambiguous }
        val offer = Offer(
            folder = canonical(folder) ?: return null,
            reusable = reusable,
            ambiguousFiles = found.ambiguous.size,
            untouchedAuxiliary = found.candidates.count { !it.kind.importable },
            truncated = found.truncated)
        return offer.takeIf { it.hasAnything }
    }

    fun adoptReviewed(torrentBytes: ByteArray, folder: File, reviewed: Offer?): Adoption {
        if (reviewed == null || reviewed.reusable.isEmpty()) return Adoption.NotRequested
        val paths = ForeignPartialRecovery.declaredPaths(torrentBytes)
            ?: return Adoption.Refused("Torrent file paths could not be inspected")
        return adoptReviewedPaths(paths, folder, reviewed)
    }

    fun adoptReviewedPaths(declaredPaths: List<String>, folder: File, reviewed: Offer?): Adoption {
        if (reviewed == null || reviewed.reusable.isEmpty()) return Adoption.NotRequested
        if (canonical(folder) != reviewed.folder)
            return Adoption.Refused("The download folder changed after review")
        val live = offerForPaths(declaredPaths, folder)
            ?: return Adoption.Refused("The partial files are no longer present")
        // Exactly the reviewed set, unchanged in kind and size; anything else needs a new review.
        if (live.reusable.toSet() != reviewed.reusable.toSet())
            return Adoption.Refused("The partial files changed after review")
        return when (val result = ForeignPartialRebind.adopt(folder, reviewed.reusable)) {
            is ForeignPartialRebind.Result.Adopted -> Adoption.Renamed(result.receipt)
            is ForeignPartialRebind.Result.Refused -> Adoption.Refused(
                result.problem + if (result.rolledBack) "" else ". Some names could not be restored")
        }
    }

    private fun canonical(folder: File): String? = try { folder.canonicalPath } catch (_: Exception) { null }

    fun undo(folder: File, adoption: Adoption): Boolean =
        adoption !is Adoption.Renamed || ForeignPartialRebind.undo(folder, adoption.receipt)

    fun clientLabel(kind: ForeignPartialLayoutScanner.Kind): String = when (kind) {
        ForeignPartialLayoutScanner.Kind.QBITTORRENT -> "qBittorrent (.!qB)"
        ForeignPartialLayoutScanner.Kind.TRANSMISSION -> "Transmission (.part)"
        ForeignPartialLayoutScanner.Kind.UTORRENT -> "µTorrent (.!ut)"
        ForeignPartialLayoutScanner.Kind.BITCOMET -> "BitComet (.bc!)"
        ForeignPartialLayoutScanner.Kind.ARIA2_CONTROL -> "aria2 control file"
        ForeignPartialLayoutScanner.Kind.BITCOMET_PIECE_PART -> "BitComet piece fragment"
    }
}
