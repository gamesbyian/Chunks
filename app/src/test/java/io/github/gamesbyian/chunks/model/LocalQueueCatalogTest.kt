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
}
