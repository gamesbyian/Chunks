package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalTransferFile
import java.util.Locale

/**
 * Name-only, read-only candidates for RAR volume sets. This never determines
 * the last volume, validates an archive header, proves verified piece ranges,
 * or authorizes extraction. Exact paths and native indices are retained.
 */
internal object RarVolumeCandidates {
    const val MAX_FILES = 10_000
    const val MAX_GROUPS = 512
    private const val MAX_ORDINAL = 10_000

    enum class Form { MODERN, LEGACY_OR_SINGLE, MIXED }
    data class Volume(val file: LocalTransferFile, val ordinal: Int?, val digits: Int?)
    data class Candidate(
        val folder: String,
        val stem: String,
        val form: Form,
        val volumes: List<Volume>,
        val ambiguous: Boolean,
        val missingCount: Int,
        val firstMissing: Int?
    ) {
        val listedCompleteCount: Int get() = volumes.count {
            it.file.sizeBytes > 0 && it.file.doneBytes == it.file.sizeBytes
        }
        val allListedComplete: Boolean get() = listedCompleteCount == volumes.size
    }

    private data class Name(val folder: String, val stem: String, val modern: Boolean, val volume: Volume)
    private val modern = Regex("(.+)\\.part([0-9]+)\\.rar", RegexOption.IGNORE_CASE)
    private val legacy = Regex("(.+)\\.([r-z])([0-9]{2})", RegexOption.IGNORE_CASE)
    private fun safePath(path: String) = path.isNotEmpty() && path.length <= 4096 &&
        !path.startsWith('/') && !path.contains('\\') &&
        path.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() } &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun name(file: LocalTransferFile): Name? {
        val folder = file.path.substringBeforeLast('/', "")
        val base = file.path.substringAfterLast('/')
        modern.matchEntire(base)?.let { match ->
            val digits = match.groupValues[2]
            val ordinal = digits.takeIf { it.length <= 5 }?.toIntOrNull()?.takeIf { it in 1..MAX_ORDINAL }
            return Name(folder, match.groupValues[1], true, Volume(file, ordinal, digits.length))
        }
        legacy.matchEntire(base)?.let { match ->
            val letter = match.groupValues[2].lowercase(Locale.ROOT).single()
            val ordinal = 2 + (letter - 'r') * 100 + match.groupValues[3].toInt()
            return Name(folder, match.groupValues[1], false, Volume(file, ordinal, null))
        }
        if (base.endsWith(".rar", ignoreCase = true) && base.length > 4)
            return Name(folder, base.dropLast(4), false, Volume(file, 1, null))
        return null
    }

    /** null is an incomplete, invalid or excessive inventory, never no archives. */
    fun identify(files: List<LocalTransferFile>): List<Candidate>? {
        if (files.size > MAX_FILES ||
            files.map { it.index }.toSet().size != files.size ||
            files.map { it.path }.toSet().size != files.size ||
            files.any { it.index < 0 || !safePath(it.path) || it.sizeBytes < 0 ||
                it.doneBytes !in 0..it.sizeBytes }) return null
        // Never join across exact directory boundaries. Fold only the stem
        // inside one directory to flag case aliases as ambiguous, not merge
        // them into an extractable set.
        val groups = files.mapNotNull(::name).groupBy { it.folder to it.stem.lowercase(Locale.ROOT) }
        if (groups.size > MAX_GROUPS) return null
        return groups.values.map { group ->
            val first = group.first()
            val form = when {
                group.all { it.modern } -> Form.MODERN
                group.none { it.modern } -> Form.LEGACY_OR_SINGLE
                else -> Form.MIXED
            }
            val volumes = group.map { it.volume }.sortedWith(
                compareBy<Volume> { it.ordinal ?: Int.MAX_VALUE }.thenBy { it.file.path })
            val ordinals = volumes.mapNotNull { it.ordinal }
            val unique = ordinals.toSet()
            val width = volumes.singleOrNull { it.ordinal == 1 }?.digits
            val inconsistentWidth = form == Form.MODERN && width != null &&
                volumes.any { volume ->
                    volume.ordinal?.let { ordinal ->
                        volume.digits != maxOf(width, ordinal.toString().length)
                    } ?: true
                }
            val ambiguous = form == Form.MIXED || group.any { it.stem != first.stem } ||
                ordinals.size != volumes.size || unique.size != volumes.size || inconsistentWidth
            val maximum = unique.maxOrNull() ?: 0
            val missing = maximum - unique.size
            Candidate(first.folder, first.stem, form, volumes, ambiguous, missing,
                if (missing > 0) (1..maximum).firstOrNull { it !in unique } else null)
        }.sortedWith(compareBy<Candidate> { it.folder }.thenBy { it.stem })
    }
}
