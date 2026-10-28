package io.github.gamesbyian.chunks.snapshots

import java.util.Locale

/** Read-only, complete navigation of a bounded snapshot-versus-phone comparison. */
internal object SnapshotRestoreCompareCatalogV1 {
    private const val PAGE_SIZE = 20

    data class Page(
        val entries: List<SnapshotRestorePreviewV1.Entry>,
        val matches: Int,
        val total: Int,
        val first: Int,
        val last: Int,
        val page: Int,
        val hasPrevious: Boolean,
        val hasNext: Boolean
    )

    fun select(
        entries: List<SnapshotRestorePreviewV1.Entry>,
        query: String,
        decision: SnapshotRestorePreviewV1.Decision?,
        requestedPage: Int
    ): Page {
        require(entries.size <= 512) { "Restore comparison exceeds snapshot v1 limit" }
        val needle = query.trim().lowercase(Locale.ROOT)
        val filtered = entries.filter { item ->
            (decision == null || item.decision == decision) &&
                (needle.isEmpty() ||
                    item.originalName.lowercase(Locale.ROOT).contains(needle) ||
                    item.infoHashV1.lowercase(Locale.ROOT).contains(needle))
        }
        val max = if (filtered.isEmpty()) 0 else (filtered.size - 1) / PAGE_SIZE
        val page = requestedPage.coerceIn(0, max)
        val from = page * PAGE_SIZE
        val shown = filtered.drop(from).take(PAGE_SIZE)
        return Page(
            entries = shown, matches = filtered.size, total = entries.size,
            first = if (shown.isEmpty()) 0 else from + 1,
            last = from + shown.size, page = page,
            hasPrevious = page > 0, hasNext = page < max
        )
    }
}
