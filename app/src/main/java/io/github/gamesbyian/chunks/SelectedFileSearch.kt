package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.rpc.ClientFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
            return matches.drop(index * PAGE_SIZE).take(PAGE_SIZE)
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

            val limit = minOf(MAX_FILES_PER_TRANSFER, MAX_FILES_TOTAL - examined)
            val bounded = ArrayList<ClientFile>()
            var pathChars = 0
            for (file in files.take(limit)) {
                if (file.path.length > MAX_PATH_CHARS_TOTAL - examinedPathChars - pathChars) break
                bounded += file
                pathChars += file.path.length
            }
            // Preserve provider indices, including sparse indices and repeated paths.
            // Duplicate indices or malformed rows are ambiguous, so refuse this source.
            if (bounded.any { it.index < 0 || it.sizeBytes < 0 || it.path.isBlank() ||
                    it.path.length > MAX_PATH_LENGTH || it.path.any(Char::isISOControl) ||
                    it.completedBytes?.let { n -> n !in 0L..it.sizeBytes } == true } ||
                bounded.map { it.index }.distinct().size != bounded.size
            ) {
                sources += SourceResult(target.key, Coverage.INVALID, 0)
            } else {
                examined += bounded.size
                examinedPathChars += pathChars
                val observedAt = clock()
                for (file in bounded) if (file.path.contains(query, ignoreCase = true)) {
                    matches += Match(target.key, target.clientLabel, target.transferName, file.index,
                        file.path, file.sizeBytes, file.completedBytes, observedAt)
                }
                sources += SourceResult(target.key,
                    if (files.size > bounded.size) Coverage.LIMITED else Coverage.OBSERVED, bounded.size)
            }
            progress(sources.size, targets.size)
        }
        currentCoroutineContext().ensureActive()
        // Also revoke on a previously read owner's generation change.
        if (!viewCurrent() || targets.any { it.read != null && !it.authorized() }) return Outcome.Revoked
        return Outcome.Observed(Result(matches.toList(), sources.toList()))
    }
}
