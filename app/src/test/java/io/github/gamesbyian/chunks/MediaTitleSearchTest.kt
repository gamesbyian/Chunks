package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.model.EnvironmentAvailability
import io.github.gamesbyian.chunks.model.EnvironmentId
import io.github.gamesbyian.chunks.model.EnvironmentTransferSnapshot
import io.github.gamesbyian.chunks.model.TransferCatalog
import io.github.gamesbyian.chunks.model.TransferFilter
import io.github.gamesbyian.chunks.model.TransferOrder
import io.github.gamesbyian.chunks.model.UnifiedTransferCatalog
import io.github.gamesbyian.chunks.model.UnifiedTransferKey
import io.github.gamesbyian.chunks.model.UnifiedTransferObservation
import io.github.gamesbyian.chunks.model.UnifiedTransferQuery
import io.github.gamesbyian.chunks.rpc.RemoteTransfer
import org.junit.Assert.assertEquals
import org.junit.Test

/** A confirmed media title is searchable like a name, scoped to the exact transfer; names still work. */
class MediaTitleSearchTest {
    private val b5 = "a".repeat(40)
    private val other = "b".repeat(40)

    @Test fun remoteListFindsConfirmedTitleAndKeepsNameSearch() {
        val list = listOf(RemoteTransfer(b5, "B5.S02E07.1080p.mkv", 10, 5, true, "/tv"),
            RemoteTransfer(other, "Unrelated.Release.mkv", 10, 5, true, "/tv"))
        val titles = mapOf(b5 to "Babylon 5 Soul Mates")
        val found = TransferCatalog.visible(list, "babylon", TransferFilter.ALL, TransferOrder.NAME,
            mediaTitle = { titles[it.hash] })
        assertEquals(listOf(b5), found.map { it.hash })
        assertEquals(listOf(other), TransferCatalog.visible(list, "Unrelated", TransferFilter.ALL, TransferOrder.NAME,
            mediaTitle = { titles[it.hash] }).map { it.hash })
        assertEquals("No title lookup keeps the old behaviour", emptyList<String>(),
            TransferCatalog.visible(list, "babylon", TransferFilter.ALL, TransferOrder.NAME).map { it.hash })
    }

    @Test fun combinedSearchUsesTheExactEnvironmentsTitleOnly() {
        val local = UnifiedTransferKey.of(EnvironmentId("local"), b5)
        val server = UnifiedTransferKey.of(EnvironmentId("rtorrent:x"), b5)
        fun obs(k: UnifiedTransferKey) = UnifiedTransferObservation(k, "B5.S02E07", "B5.S02E07", 1, 0, true, 0, 0, 0)
        val sources = listOf(
            EnvironmentTransferSnapshot(EnvironmentId("local"), EnvironmentAvailability.LIVE, true, 1L, listOf(obs(local))),
            EnvironmentTransferSnapshot(EnvironmentId("rtorrent:x"), EnvironmentAvailability.LIVE, true, 1L, listOf(obs(server))))
        val r = UnifiedTransferCatalog.query(sources, UnifiedTransferQuery(search = "Babylon")) { k ->
            if (k == local) "Babylon 5" else null
        }
        assertEquals(listOf(local), r.transfers.map { it.key })
    }
}
