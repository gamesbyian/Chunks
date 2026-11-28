package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.rpc.ClientFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One explicitly requested, bounded read-only search over exact selected jobs.
 * Adapter responses are observations, not verified metainfo completeness or
 * proof of readable payload bytes. No operation here grants mutation authority.
 */
internal object SelectedFileSearch {
    const val MAX_TRANSFERS = 24
    const val MAX_FILES_PER_TRANSFER = 4_096
    const val MAX_FILES_TOTAL = 20_000
    const val MAX_PATH_LENGTH = 4_096
    const val MAX_PATH_CHARS_TOTAL = 2_000_000
    const val MAX_QUERY_LENGTH = 256
    const val PAGE_SIZE = 20
    const val READ_TIMEOUT_MILLIS = 12_000L

    data class Target(
        val key: UnifiedTransferKey,
        val clientLabel: String,
        val transferName: String,
        val metadataPending: Boolean?,
        /** Null for unavailable, stale, or unverified read owners. */
        val read: (suspend () -> List<ClientFile>)?,
        /** Must check exact current session and authorization before and after each read. */
        val authorized: () -> Boolean
    )

    enum class Coverage { OBSERVED, METADATA_PENDING, EMPTY_OR_UNKNOWN, UNAVAILABLE, LIMITED, INVALID }
    data class SourceResult(val key: UnifiedTransferKey, val coverage: Coverage, val examined: Int)
    data class Match(
        val key: UnifiedTransferKey,
        val clientLabel: String,
        val transferName: String,
        val index: Int,
        val path: String,
        val sizeBytes: Long,
        val completedBytes: Long?,
        val observedAtMillis: Long
    )
    data class Result(val matches: List<Match>, val sources: List<SourceResult>) {
        val hasCoverageGap: Boolean get() = sources.any { it.coverage != Coverage.OBSERVED }
        val pageCount: Int get() = ((matches.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        fun page(index: Int): List<Match> {
            require(index in 0 until pageCount)
            val start = index * PAGE_SIZE
            return matches.subList(start, minOf(start + PAGE_SIZE, matches.size))
        }
    }

    sealed interface Outcome {
        data class Observed(val result: Result) : Outcome
        /** Never publish even earlier results if the selected view is no longer authorized. */
        data object Revoked : Outcome
        data object InvalidRequest : Outcome
    }

    suspend fun scan(
        rawQuery: String,
        targets: List<Target>,
        viewCurrent: () -> Boolean,
        clock: () -> Long = System::currentTimeMillis,
        progress: (Int, Int) -> Unit = { _, _ -> }
    ): Outcome {
        val query = rawQuery.trim()
        if (query.isEmpty() || query.length > MAX_QUERY_LENGTH ||
            query.any(Char::isISOControl) || targets.isEmpty() ||
            targets.size > MAX_TRANSFERS || targets.map { it.key }.distinct().size != targets.size
        ) return Outcome.InvalidRequest
        val matches = ArrayList<Match>()
        val sources = ArrayList<SourceResult>()
        var examined = 0
        var examinedPathChars = 0

        for (target in targets) {
            currentCoroutineContext().ensureActive()
            if (!viewCurrent()) return Outcome.Revoked
            val read = target.read
            val coverage = when {
                !target.authorized() || read == null -> Coverage.UNAVAILABLE
                target.metadataPending == true -> Coverage.METADATA_PENDING
                examined >= MAX_FILES_TOTAL || examinedPathChars >= MAX_PATH_CHARS_TOTAL -> Coverage.LIMITED
                else -> null
            }
            if (coverage != null) {
                sources += SourceResult(target.key, coverage, 0)
                progress(sources.size, targets.size)
                continue
            }
            // A timeout is bounded observation failure, not a missing transfer.
            val files = try {
                withTimeoutOrNull(READ_TIMEOUT_MILLIS) { requireNotNull(read).invoke() }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            currentCoroutineContext().ensureActive()
            if (!viewCurrent() || !target.authorized()) return Outcome.Revoked
            if (files.isNullOrEmpty()) {
                sources += SourceResult(target.key,
                    if (files == null) Coverage.UNAVAILABLE else Coverage.EMPTY_OR_UNKNOWN, 0)
                progress(sources.size, targets.size)
                continue
            }

            // Potentially expensive Unicode matching and row validation are CPU work,
            // not UI work. Authorization checks remain on the caller dispatcher.
            val inspected = withContext(Dispatchers.Default) {
                inspect(target, files, query, MAX_FILES_TOTAL - examined,
                    MAX_PATH_CHARS_TOTAL - examinedPathChars, clock())
            }
            if (!viewCurrent() || !target.authorized()) return Outcome.Revoked
            examined += inspected.examined
            examinedPathChars += inspected.pathChars
            matches += inspected.matches
            sources += SourceResult(target.key, inspected.coverage, inspected.examined)
            progress(sources.size, targets.size)
        }
        currentCoroutineContext().ensureActive()
        // Also revoke on a previously read owner's generation change.
        if (!viewCurrent() || targets.any { it.read != null && !it.authorized() }) return Outcome.Revoked
        return Outcome.Observed(Result(matches.toList(), sources.toList()))
    }

    private data class Inspected(
        val coverage: Coverage,
        val examined: Int,
        val pathChars: Int,
        val matches: List<Match>
    )

    private suspend fun inspect(
        target: Target, files: List<ClientFile>, query: String,
        remainingFiles: Int, remainingPathChars: Int, observedAt: Long
    ): Inspected {
        val limit = minOf(MAX_FILES_PER_TRANSFER, remainingFiles)
        val indices = HashSet<Int>()
        val matches = ArrayList<Match>()
        var examined = 0
        var pathChars = 0
        for (file in files.take(limit)) {
            currentCoroutineContext().ensureActive()
            if (file.path.length > remainingPathChars - pathChars) break
            // Preserve provider indices, including sparse indices and repeated paths.
            // Duplicate indices or malformed rows are ambiguous; refuse this source.
            if (file.index < 0 || file.sizeBytes < 0 || file.path.isBlank() ||
                file.path.length > MAX_PATH_LENGTH ||
                file.path.any { it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' } ||
                file.completedBytes?.let { n -> n !in 0L..file.sizeBytes } == true ||
                !indices.add(file.index)
            ) return Inspected(Coverage.INVALID, 0, 0, emptyList())
            examined++
            pathChars += file.path.length
            if (file.path.contains(query, ignoreCase = true)) {
                matches += Match(target.key, target.clientLabel, target.transferName, file.index,
                    file.path, file.sizeBytes, file.completedBytes, observedAt)
            }
        }
        return Inspected(if (files.size > examined) Coverage.LIMITED else Coverage.OBSERVED,
            examined, pathChars, matches)
    }
}
