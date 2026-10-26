package io.github.gamesbyian.chunks.model

/**
 * View-only paging after search and Collection membership filtering.
 * Every matched transfer remains reachable without loading all Compose cards.
 */
object ManualCollectionPager {
    const val PAGE_SIZE = 40

    data class Page<T>(
        val items: List<T>,
        val pageIndex: Int,
        val totalPages: Int,
        val totalMatches: Int
    ) {
        val hasPrevious: Boolean get() = pageIndex > 0
        val hasNext: Boolean get() = pageIndex + 1 < totalPages
    }

    fun <T> slice(items: List<T>, requestedPage: Int, size: Int = PAGE_SIZE): Page<T> {
        require(size in 1..1_000)
        val totalPages = if (items.isEmpty()) 1 else 1 + (items.size - 1) / size
        val pageIndex = requestedPage.coerceIn(0, totalPages - 1)
        val start = pageIndex * size
        val end = minOf(start + size, items.size)
        return Page(items.subList(start, end), pageIndex, totalPages, items.size)
    }
}
