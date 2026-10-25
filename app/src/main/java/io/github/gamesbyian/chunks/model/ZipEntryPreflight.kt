package io.github.gamesbyian.chunks.model

import java.text.Normalizer
import java.util.Locale

/**
 * Read-only ZIP central-directory preflight. Entries are untrusted; passing this scan
 * is NEVER authorization to write files, follow links or delete an original archive.
 * The extraction executor must independently re-check each streamed byte, output handle,
 * member type, destination scope and actual expanded size during extraction.
 */
data class ZipMemberDescriptor(
    val rawName: String,
    val directory: Boolean,
    val declaredSizeBytes: Long?,
    val compressedSizeBytes: Long?
)

data class ZipPreflightLimits(
    val maxEntries: Int = 10_000,
    val maxDeclaredBytes: Long = 8L * 1024 * 1024 * 1024,
    val maxRatio: Long = 200,
    val maxDepth: Int = 32
) {
    init {
        require(maxEntries in 1..100_000)
        require(maxDeclaredBytes > 0)
        require(maxRatio in 1..1_000_000)
        require(maxDepth in 1..128)
    }
}

enum class ZipPreflightStatus { BLOCKED, BOUNDED_STREAM_CHECK_REQUIRED }

data class ZipPreflightResult(
    val status: ZipPreflightStatus,
    val reasons: List<String>,
    val declaredOutputBytes: Long?,
    val fileCount: Int,
    val names: List<String>
)

object ZipEntryPreflight {
    fun inspect(
        entries: List<ZipMemberDescriptor>,
        limits: ZipPreflightLimits = ZipPreflightLimits()
    ): ZipPreflightResult {
        val problems = mutableListOf<String>()
        val names = mutableListOf<String>()
        val seen = mutableMapOf<String, Boolean>()
        var total: Long? = 0
        var files = 0
        if (entries.size > limits.maxEntries)
            problems += "ZIP entry count exceeds the configured limit"

        for (entry in entries.take(limits.maxEntries + 1)) {
            val canonical = safeName(entry.rawName, entry.directory, limits.maxDepth)
            if (canonical == null) {
                problems += "ZIP entry has an unsafe, absolute, ambiguous or excessively deep path"
                continue
            }
            val key = canonical.lowercase(Locale.ROOT)
            if (seen.putIfAbsent(key, entry.directory) != null)
                problems += "ZIP contains duplicate or Unicode/case-colliding paths"
            names += canonical
            if (entry.directory) continue

            files++
            val size = entry.declaredSizeBytes
            val compressed = entry.compressedSizeBytes
            if (size != null && size < 0 || compressed != null && compressed < 0) {
                problems += "ZIP contains an invalid negative length"
                continue
            }
            if (size == null) total = null
            else {
                val previous = total
                total = if (previous == null) null
                    else try { Math.addExact(previous, size) }
                    catch (_: ArithmeticException) {
                        problems += "ZIP declared output byte total overflows"
                        null
                    }
                if (size > limits.maxDeclaredBytes || total != null && total > limits.maxDeclaredBytes)
                    problems += "ZIP declared output exceeds the configured byte limit"
            }
            if (size != null && compressed != null) {
                if (compressed == 0L && size > 0L ||
                    compressed > 0L && (size / compressed > limits.maxRatio ||
                        size / compressed == limits.maxRatio && size % compressed > 0L)) {
                    problems += "ZIP member expansion ratio exceeds the configured limit"
                }
            }
        }
        // ZIP files can masquerade as directories for case-insensitive targets.
        val fileKeys = seen.filterValues { !it }.keys
        if (fileKeys.any { file -> seen.keys.any { it.startsWith("$file/") } })
            problems += "ZIP has a file that is also a parent directory"

        return ZipPreflightResult(
            if (problems.isEmpty()) ZipPreflightStatus.BOUNDED_STREAM_CHECK_REQUIRED
            else ZipPreflightStatus.BLOCKED,
            problems.distinct(),
            total,
            files,
            names
        )
    }

    /**
     * The single canonical member-path rule shared by preflight and the extractor,
     * so an executor can never write a name the review would have refused.
     */
    internal fun canonicalPath(raw: String, directory: Boolean, maxDepth: Int): String? =
        safeName(raw, directory, maxDepth)

    private fun safeName(raw: String, directory: Boolean, maxDepth: Int): String? {
        if (raw.isEmpty() || raw.length > 4096 || raw.startsWith("/") ||
            raw.startsWith("\\") || raw.contains('\\') ||
            Regex("^[a-zA-Z]:").containsMatchIn(raw) ||
            raw.any { it.isISOControl() || it in '\u202A'..'\u202E' ||
                it in '\u2066'..'\u2069' }) return null

        val name = if (directory && raw.endsWith('/')) raw.dropLast(1) else raw
        val parts = name.split("/")
        if (parts.size > maxDepth || parts.any {
                it.isBlank() || it == "." || it == ".." ||
                    it.trimEnd(' ', '.') != it
            }) return null
        return Normalizer.normalize(parts.joinToString("/"), Normalizer.Form.NFC)
    }
}
