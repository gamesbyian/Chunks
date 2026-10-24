package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.*
import org.junit.Test

class TransferCatalogPerformanceTest {
    private val items = (0 until 500).map { index ->
        RemoteTransfer(index.toString().padStart(40, '0'),
            "Original.Series.S01E" + index.toString().padStart(3, '0') + ".mkv",
            1000, index.toLong(), index % 2 == 0, "/remote/files")
    }

    @Test fun largeNameSortLooksUpEachAliasExactlyOnce() {
        val counts = mutableMapOf<String, Int>()
        val list = TransferCatalog.visible(items, "", TransferFilter.ALL, TransferOrder.NAME) {
            counts[it.hash] = (counts[it.hash] ?: 0) + 1
            "Alias " + (500 - it.completedBytes).toString().padStart(4, '0')
        }
        assertEquals(500, list.size)
        assertEquals(500, counts.size)
        assertTrue(counts.values.all { it == 1 })
        assertEquals(items.last().hash, list.first().hash)
    }

    @Test fun allOrderingsLookupEachPassingAliasOnceAndKeepOriginalItems() {
        for (order in TransferOrder.entries) {
            var calls = 0
            val subset = TransferCatalog.visible(items, "", TransferFilter.ACTIVE, order) {
                calls++
                "Human label " + it.hash.takeLast(4)
            }
            assertEquals(250, calls)
            assertEquals(250, subset.size)
            assertTrue(subset.all { it.started && it.name.startsWith("Original.Series") })
        }
    }

    @Test fun aliasAndOriginalSearchDoNotChangeHashOrPaths() {
        val filterByAlias = TransferCatalog.visible(items, "favorite", TransferFilter.ALL,
            TransferOrder.NAME) { if (it == items[42]) "My favorite" else it.name }
        assertEquals(items[42], filterByAlias.single())
        val filterByOriginal = TransferCatalog.visible(items, "S01E042", TransferFilter.ALL,
            TransferOrder.NAME) { "Something else" }
        assertEquals(items[42], filterByOriginal.single())
    }
}
