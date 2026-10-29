package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import org.junit.Assert.*
import org.junit.Test

class LocalQueueCatalogTest {
    private fun transfer(
        name: String,
        hash: String,
        stopped: Boolean = false,
        state: LocalTransferState = LocalTransferState.DOWNLOADING,
        progress: Int = 500_000,
        wanted: Long = 100,
        peers: Int = 2,
        metadataKnown: Boolean = true,
        error: String? = null,
        path: String = "/downloads/Chunks Downloads"
    ) = LocalTransferStatus(
        infoHash = hash, name = name, state = state, stopped = stopped,
        progressPpm = progress, wantedBytes = wanted, doneBytes = 0,
        payloadDownloadedBytes = 0, payloadUploadedBytes = 0,
        peers = peers, savePath = path, error = error,
        metadataKnown = metadataKnown
    )

    private val a = transfer("Bravo", "b".repeat(40), peers = 5)
    private val b = transfer("alpha", "a".repeat(40),
        stopped = true, progress = 1_000_000, state = LocalTransferState.SEEDING)
    private val c = transfer("Zeta", "c".repeat(40), progress = 100_000,
        metadataKnown = false, stopped = true, state = LocalTransferState.DOWNLOADING_METADATA)
    private val d = transfer("Error", "d".repeat(40), error = "disk full", peers = 0)
    private val data = listOf(a, b, c, d)

    @Test fun searchMatchesCaseInsensitiveNameHashAndCurrentSavePath() {
        fun search(s: String) = LocalQueueCatalog.visible(data, s,
            LocalQueueFilter.ALL, LocalQueueOrder.NAME).map { it.infoHash }
        assertEquals(listOf(b.infoHash), search("ALP"))
        assertEquals(listOf(a.infoHash), search("BBBB"))
        assertEquals(4, search("chunks downloads").size)
        assertEquals(4, search("  ").size)
        assertEquals(emptyList<String>(), search("not in queue"))
    }

    @Test fun filtersStoppedRunningCompleteAndNeedsAttentionWithoutGuessingStates() {
        fun only(filter: LocalQueueFilter) = LocalQueueCatalog.visible(data, "",
            filter, LocalQueueOrder.NAME).map { it.infoHash }.toSet()
        assertEquals(setOf(a.infoHash, d.infoHash), only(LocalQueueFilter.RUNNING))
        assertEquals(setOf(b.infoHash, c.infoHash), only(LocalQueueFilter.STOPPED))
        assertEquals(setOf(b.infoHash), only(LocalQueueFilter.COMPLETE))
        assertEquals(setOf(c.infoHash, d.infoHash), only(LocalQueueFilter.NEEDS_ATTENTION))
        val zeroSize = transfer("Zero", "f".repeat(40), progress = 1_000_000, wanted = 0)
        assertTrue(LocalQueueCatalog.visible(listOf(zeroSize), "",
            LocalQueueFilter.COMPLETE, LocalQueueOrder.NAME).isEmpty())
    }

    @Test fun sortsWithNameAndStableHashTieBreakIndependentOfEngineInputOrder() {
        val x = transfer("NAME", "e".repeat(40))
        val y = transfer("name", "f".repeat(40))
        val ordered = LocalQueueCatalog.visible(data + x + y, "",
            LocalQueueFilter.ALL, LocalQueueOrder.NAME)
        assertEquals(listOf("alpha", "Bravo", "Error", "NAME", "name", "Zeta"),
            ordered.map { it.name })
        assertEquals(x.infoHash, ordered[3].infoHash)
        assertEquals(y.infoHash, ordered[4].infoHash)
        assertEquals(listOf(x.infoHash, y.infoHash),
            LocalQueueCatalog.visible(listOf(y, x), "", LocalQueueFilter.ALL,
                LocalQueueOrder.NAME).map { it.infoHash })
    }

    @Test fun aliasesAreSearchedAndSortedButOriginalNamesRemainSearchable() {
        val sample = listOf(a, b, c)
        val aliases = mapOf(a.infoHash to "000 new display", c.infoHash to "zz top")
        fun visible(search: String, order: LocalQueueOrder = LocalQueueOrder.NAME) =
            LocalQueueCatalog.visible(sample, search, LocalQueueFilter.ALL, order) { item ->
                aliases[item.infoHash] ?: item.name
            }
        assertEquals(listOf(a.infoHash, b.infoHash, c.infoHash),
            visible("").map { it.infoHash })
        assertEquals(listOf(a.infoHash), visible("new display").map { it.infoHash })
        assertEquals(listOf(a.infoHash), visible("Bravo").map { it.infoHash })
        assertEquals(listOf(c.infoHash), visible("zz top").map { it.infoHash })
        val observed = mutableMapOf<String, Int>()
        LocalQueueCatalog.visible(sample, "", LocalQueueFilter.ALL, LocalQueueOrder.NAME) {
            item -> observed[item.infoHash] = (observed[item.infoHash] ?: 0) + 1
            aliases[item.infoHash] ?: item.name
        }
        assertTrue(observed.values.all { it == 1 })
    }

    @Test fun sortsProgressSizeAndPeersWithoutMutationOrDroppedTransfers() {
        val before = data.toList()
        fun sort(order: LocalQueueOrder) =
            LocalQueueCatalog.visible(data, "", LocalQueueFilter.ALL, order)
        assertEquals(b.infoHash, sort(LocalQueueOrder.PROGRESS).first().infoHash)
        assertEquals(a.infoHash, sort(LocalQueueOrder.PEERS).first().infoHash)
        assertEquals(before.toSet(), sort(LocalQueueOrder.SIZE).toSet())
        assertEquals(before, data)
    }

    @Test fun everyOrderReversesWithUnknownsLastAndFixedTiesInBothDirections() {
        val big = transfer("Big", "1".repeat(40), wanted = 900, progress = 900_000, peers = 9)
        val small = transfer("small", "2".repeat(40), wanted = 10, progress = 100_000, peers = 1)
        val tieA = transfer("Tie", "3".repeat(40), wanted = 50, progress = 500_000, peers = 4)
        val tieB = transfer("tie", "4".repeat(40), wanted = 50, progress = 500_000, peers = 4)
        val magnet = transfer("Magnet", "5".repeat(40), wanted = 0, progress = 0,
            peers = -1, metadataKnown = false)
        val rows = listOf(magnet, tieB, small, big, tieA)
        fun names(order: LocalQueueOrder, direction: UnifiedSortDirection) =
            LocalQueueCatalog.visible(rows, "", LocalQueueFilter.ALL, order, direction).map { it.infoHash }
        for (order in listOf(LocalQueueOrder.SIZE, LocalQueueOrder.PROGRESS, LocalQueueOrder.PEERS)) {
            assertEquals(order.name, listOf(big, tieA, tieB, small, magnet).map { it.infoHash },
                names(order, UnifiedSortDirection.DESCENDING))
            // Unknown stays last and equal values keep A–Z/hash order when reversed.
            assertEquals(order.name, listOf(small, tieA, tieB, big, magnet).map { it.infoHash },
                names(order, UnifiedSortDirection.ASCENDING))
            assertEquals(UnifiedSortDirection.DESCENDING, order.naturalDirection)
        }
        assertEquals(listOf(big, magnet, small, tieA, tieB).map { it.infoHash },
            names(LocalQueueOrder.NAME, UnifiedSortDirection.ASCENDING))
        assertEquals(listOf(tieA, tieB, small, magnet, big).map { it.infoHash },
            names(LocalQueueOrder.NAME, UnifiedSortDirection.DESCENDING))
        assertEquals(UnifiedSortDirection.ASCENDING, LocalQueueOrder.NAME.naturalDirection)
        assertEquals("Z–A", LocalQueueOrder.NAME.directionLabel(UnifiedSortDirection.DESCENDING))
        assertEquals("Fewest peers first", LocalQueueOrder.PEERS.directionLabel(UnifiedSortDirection.ASCENDING))
    }
}
