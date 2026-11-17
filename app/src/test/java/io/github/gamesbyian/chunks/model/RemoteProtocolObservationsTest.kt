package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.rpc.DelugeReadOnlyRpc
import io.github.gamesbyian.chunks.rpc.QBittorrentReadOnlyRpc
import io.github.gamesbyian.chunks.rpc.TransmissionReadOnlyRpc
import org.junit.Assert.*
import org.junit.Test

class RemoteProtocolObservationsTest {
    private val hash = "a".repeat(40)
    private val envT = EnvironmentId("transmission-exact-profile")
    private val envQ = EnvironmentId("qbittorrent-exact-profile")
    private val envD = EnvironmentId("deluge-exact-daemon")

    private fun transmissionRow(
        hash: String = this.hash, status: Int = 4, total: Long = 100L, done: Long = 75L
    ) = TransmissionReadOnlyRpc.Torrent(
        hash = hash, nativeId = 42L, name = "Sample", totalBytes = total,
        completedBytes = done, reportedDirectory = "/server/transmission",
        nativeStatus = status, downloadBytesPerSecond = 10L, uploadBytesPerSecond = 0L
    )
    private fun transmission(vararg rows: TransmissionReadOnlyRpc.Torrent) =
        TransmissionReadOnlyRpc.Snapshot("4.1.0", 18,
            TransmissionReadOnlyRpc.Dialect.JSON_RPC_2, rows.toList(), false)

    private fun qbRow(
        hash: String = this.hash, state: String = "downloading",
        selected: Long = 100L, done: Long? = 40L
    ) = QBittorrentReadOnlyRpc.Torrent(
        hash = hash, name = "Sample", state = state, selectedSizeBytes = selected,
        totalSizeBytes = 200L, completedBytes = done,
        downloadBytesPerSecond = null, uploadBytesPerSecond = 0L,
        reportedSavePath = "/server/qb"
    )
    private fun qb(vararg rows: QBittorrentReadOnlyRpc.Torrent) =
        QBittorrentReadOnlyRpc.Snapshot("v5.3", "2.14", rows.toList())

    private fun delugeRow(
        hash: String = this.hash, state: String = "Seeding", total: Long? = 200L,
        done: Long? = 200L
    ) = DelugeReadOnlyRpc.Torrent(
        hash = hash, name = "Sample", state = state,
        totalSizeBytes = total, completedBytes = done,
        downloadBytesPerSecond = null, uploadBytesPerSecond = null,
        reportedSavePath = "/server/deluge"
    )
    private fun deluge(vararg rows: DelugeReadOnlyRpc.Torrent) =
        DelugeReadOnlyRpc.Snapshot("daemon-01", "2.1.1", rows.toList())

    @Test fun threeClientsWithSameTorrentHashRemainThreeIndependentKeys() {
        val a = RemoteProtocolObservations.transmission(envT, transmission(transmissionRow())).single()
        val b = RemoteProtocolObservations.qbittorrent(envQ, qb(qbRow())).single()
        val c = RemoteProtocolObservations.deluge(envD, deluge(delugeRow())).single()
        assertEquals(setOf(hash), listOf(a,b,c).map { it.key.infoHash }.toSet())
        assertEquals(3, listOf(a,b,c).map { it.key }.toSet().size)
        assertEquals(setOf(envT,envQ,envD), listOf(a,b,c).map { it.key.environment }.toSet())
        assertEquals(750_000, a.progressPpm)
        assertEquals(400_000, b.progressPpm)
        assertEquals(1_000_000, c.progressPpm)
    }

    @Test fun transmissionNativeIdsAreNeverUsedAsUnifiedTransferKeys() {
        val first = transmissionRow(status = 0)
        val second = first.copy(hash = "b".repeat(40), nativeId = 42L)
        val rows = RemoteProtocolObservations.transmission(envT, transmission(first, second))
        assertEquals(2, rows.size)
        assertFalse(rows.first().started!!)
        assertTrue(rows[1].key.infoHash == "b".repeat(40))
        assertNull(RemoteProtocolObservations.transmission(envT,
            transmission(transmissionRow(status = 1))).single().started)
    }

    @Test fun absentCountersRemainUnknownInsteadOfMeasuredZero() {
        val q = RemoteProtocolObservations.qbittorrent(envQ,
            qb(qbRow(done = null, state = "metaDL"))).single()
        assertNull(q.progressPpm)
        assertNull(q.downloadBytesPerSecond)
        assertNull(q.started)
        assertNull(q.peers)
        assertNull(q.ratioThousandths)
        assertNull(q.metadataPending)
        assertNull(q.addedAtEpochMillis)
        val d = RemoteProtocolObservations.deluge(envD,
            deluge(delugeRow(total = null, done = null))).single()
        assertNull(d.sizeBytes)
        assertNull(d.progressPpm)
        assertNull(d.downloadBytesPerSecond)
        assertNull(d.uploadBytesPerSecond)
        assertNull(d.peers)
    }

    @Test fun inconsistentOrZeroSizesMustNotBeFabricatedAsComplete() {
        val q = RemoteProtocolObservations.qbittorrent(envQ, qb(qbRow(selected = 50, done = 70))).single()
        assertEquals(50L, q.sizeBytes)
        assertNull(q.progressPpm)
        val d = RemoteProtocolObservations.deluge(envD, deluge(delugeRow(total = 0, done = 0))).single()
        assertNull(d.sizeBytes)
        assertNull(d.progressPpm)
        val t = RemoteProtocolObservations.transmission(envT,
            transmission(transmissionRow(total = 0, done = 0))).single()
        assertNull(t.sizeBytes)
        assertNull(t.progressPpm)
    }

    @Test fun onlyPreciselyKnownNativeStoppedAndActiveStatesBecomeBooleans() {
        assertEquals(false, RemoteProtocolObservations.transmission(envT,
            transmission(transmissionRow(status = 0))).single().started)
        assertEquals(true, RemoteProtocolObservations.transmission(envT,
            transmission(transmissionRow(status = 6))).single().started)
        assertNull(RemoteProtocolObservations.transmission(envT,
            transmission(transmissionRow(status = 3))).single().started)
        assertEquals(false, RemoteProtocolObservations.qbittorrent(envQ,
            qb(qbRow(state = "stoppedUP"))).single().started)
        assertEquals(true, RemoteProtocolObservations.qbittorrent(envQ,
            qb(qbRow(state = "stalledDL"))).single().started)
        assertNull(RemoteProtocolObservations.qbittorrent(envQ,
            qb(qbRow(state = "checkingResumeData"))).single().started)
        assertEquals(false, RemoteProtocolObservations.deluge(envD,
            deluge(delugeRow(state = "Paused"))).single().started)
        assertNull(RemoteProtocolObservations.deluge(envD,
            deluge(delugeRow(state = "Queued"))).single().started)
    }

    @Test fun onlyKnownErrorStatesSetExistingAttentionFlag() {
        assertTrue(RemoteProtocolObservations.qbittorrent(envQ,
            qb(qbRow(state = "missingFiles"))).single().hasError)
        assertTrue(RemoteProtocolObservations.deluge(envD,
            deluge(delugeRow(state = "Error"))).single().hasError)
        assertFalse(RemoteProtocolObservations.transmission(envT,
            transmission(transmissionRow())).single().hasError)
    }

    @Test fun serverReportedFolderIsNotRetargetedOrPromotedToLocalPlace() {
        val a = RemoteProtocolObservations.transmission(envT, transmission(transmissionRow())).single()
        val b = RemoteProtocolObservations.qbittorrent(envQ, qb(qbRow())).single()
        val c = RemoteProtocolObservations.deluge(envD, deluge(delugeRow())).single()
        assertEquals("/server/transmission", a.reportedSaveFolder)
        assertEquals("/server/qb", b.reportedSaveFolder)
        assertEquals("/server/deluge", c.reportedSaveFolder)
        assertTrue(listOf(a,b,c).all { it.checkingExistingData == null && it.stopsAfterCheck == null })
    }

    @Test fun emptyReadOnlyInventoryIsNotAnEngineCommandOrAutomaticCompletenessProof() {
        assertTrue(RemoteProtocolObservations.transmission(envT, transmission()).isEmpty())
        assertTrue(RemoteProtocolObservations.qbittorrent(envQ, qb()).isEmpty())
        assertTrue(RemoteProtocolObservations.deluge(envD, deluge()).isEmpty())
        val incomplete = EnvironmentTransferSnapshot(envT, EnvironmentAvailability.LIVE,
            inventoryComplete = false, observedAtEpochMillis = null,
            transfers = RemoteProtocolObservations.transmission(envT, transmission()))
        assertFalse(incomplete.canClaimCompleteLiveInventory)
    }

    @Test fun fabricatedDuplicateRowsAndLocalEnvironmentFailClosed() {
        val two = transmission(transmissionRow(), transmissionRow())
        try {
            RemoteProtocolObservations.transmission(envT, two)
            fail("Ambiguous same-client identity accepted")
        } catch (expected: IllegalArgumentException) { }
        try {
            RemoteProtocolObservations.qbittorrent(EnvironmentSnapshots.LOCAL, qb(qbRow()))
            fail("Remote record was attributed to Local engine")
        } catch (expected: IllegalArgumentException) { }
        try {
            RemoteProtocolObservations.deluge(EnvironmentSnapshots.LOCAL, deluge())
            fail("Empty remote record was attributed to Local engine")
        } catch (expected: IllegalArgumentException) { }
    }
}
