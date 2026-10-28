package io.github.gamesbyian.chunks.snapshots

import java.util.Locale

/** Bounded, read-only catalog navigation for a verified offline .chunksnap. */
internal object SnapshotInspectCatalogV1 {
    private const val PAGE_SIZE = 20

    data class Page(
        val items: List<SnapshotManifestV1.Item>,
        val matchingCount: Int,
        val totalCount: Int,
        val firstIndex: Int,
        val lastIndex: Int,
        val pageIndex: Int,
        val hasPrevious: Boolean,
        val hasNext: Boolean
    )

    fun select(
        items: List<SnapshotManifestV1.Item>,
        search: String,
        recovery: SnapshotManifestV1.Recovery?,
        requestedPage: Int
    ): Page {
        require(items.size <= 512) { "Snapshot catalog exceeds bounded schema" }
        val needle = search.trim().lowercase(Locale.ROOT)
        val filtered = items.filter { item ->
            (recovery == null || item.recovery == recovery) &&
                (needle.isEmpty() ||
                    item.originalName.lowercase(Locale.ROOT).contains(needle) ||
                    item.infoHashV1.contains(needle))
        }
        val lastPage = if (filtered.isEmpty()) 0 else (filtered.size - 1) / PAGE_SIZE
        val index = requestedPage.coerceIn(0, lastPage)
        val start = index * PAGE_SIZE
        val shown = filtered.drop(start).take(PAGE_SIZE)
        return Page(
            items = shown,
            matchingCount = filtered.size,
            totalCount = items.size,
            firstIndex = if (shown.isEmpty()) 0 else start + 1,
            lastIndex = start + shown.size,
            pageIndex = index,
            hasPrevious = index > 0,
            hasNext = index < lastPage
        )
    }
}
