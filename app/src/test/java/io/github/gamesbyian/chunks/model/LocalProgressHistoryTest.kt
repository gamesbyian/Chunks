package io.github.gamesbyian.chunks.model

import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import org.junit.Assert.*
import org.junit.Test

class LocalProgressHistoryTest {
    private fun item(
        hash: String = "a".repeat(40),
        verified: Long = 0,
        received: Long = 0,
        sent: Long = 0,
        peers: Int = 0,
        metadata: Boolean = true
    ) = LocalTransferStatus(
        infoHash = hash, name = "Licensed test torrent",
        state = LocalTransferState.DOWNLOADING, stopped = false,
        progressPpm = 0, wantedBytes = 1000,
        doneBytes = verified, payloadDownloadedBytes = received,
        payloadUploadedBytes = sent, peers = peers,
        savePath = "/data/Chunks", error = null, metadataKnown = metadata
    )

    @Test fun requiresTwoFreshTimeSeparatedObservationsBeforeClaimingGrowth() {
        val h = LocalProgressHistory()
        val hash = "a".repeat(40)
        assertTrue(h.observe(listOf(item(verified = 2, received = 4)), 1000).isEmpty())
        assertTrue(h.observe(listOf(item(verified = 3, received = 5)), 2999).isEmpty())
        val delta = h.observe(listOf(item(verified = 16, received = 20, peers = 3)), 4500)[hash]
        assertNotNull(delta)
        assertEquals(3L, delta!!.seconds)
        assertEquals(14L, delta.verifiedBytes)
        assertEquals(16L, delta.peerPayloadBytes)
        assertEquals(3, delta.peersNow)
    }

    @Test fun zeroCountersAreMeasuredZeroNotFalseProofOfPeerAvailability() {
        val h = LocalProgressHistory()
        h.observe(listOf(item()), 1000)
        val delta = requireNotNull(h.observe(listOf(item()), 5000)["a".repeat(40)])
        assertEquals(0, delta.verifiedBytes)
        assertEquals(0, delta.peerPayloadBytes)
        assertEquals(0, delta.peersNow)
    }

    @Test fun webseedOrDiskGrowthCanBeVerifiedWithoutPeerCounterIncrease() {
        val h = LocalProgressHistory()
        h.observe(listOf(item(verified = 0, received = 0)), 1000)
        val d = requireNotNull(h.observe(listOf(item(verified = 200, received = 0)), 5000)["a".repeat(40)])
        assertEquals(200, d.verifiedBytes)
        assertEquals(0, d.peerPayloadBytes)
    }

    @Test fun counterRestartAndClockRollbackInvalidateOldBaselines() {
        val h = LocalProgressHistory()
        h.observe(listOf(item(verified = 20, received = 30)), 1000)
        assertTrue(h.observe(listOf(item(verified = 1, received = 3)), 5000).isEmpty())
        assertEquals(4, h.observe(listOf(item(verified = 5, received = 10)), 9000)
            .getValue("a".repeat(40)).verifiedBytes)
        assertTrue(h.observe(listOf(item(verified = 6, received = 10)), 8000).isEmpty())
        assertTrue(h.observe(listOf(item(verified = 7, received = 10)), 10000).isEmpty())
    }

    @Test fun missingTransferAndDuplicateFrameNeverLeakOldIdentityGrowth() {
        val h = LocalProgressHistory()
        val a = "a".repeat(40)
        val b = "b".repeat(40)
        h.observe(listOf(item(a), item(b)), 1000)
        assertTrue(h.observe(listOf(item(a)), 5000).containsKey(a))
        assertFalse(h.observe(listOf(item(b)), 9000).containsKey(b))
        h.observe(listOf(item(b)), 10000)
        assertTrue(h.observe(listOf(item(b)), 10000).isEmpty())
        assertTrue(h.observe(listOf(item(b), item(b)), 20000).isEmpty())
        assertTrue(h.observe(listOf(item(b)), 24000).isEmpty())
    }

    @Test fun horizonAndInputCapacityAreBounded() {
        val h = LocalProgressHistory(maxTracked = 1, horizonMs = 6_000, minWindowMs = 3_000)
        h.observe(listOf(item(verified = 1)), 1000)
        val d = h.observe(listOf(item(verified = 100)), 11000)
        assertFalse(d.containsKey("a".repeat(40))) // baseline slid to last sample
        assertTrue(h.observe(listOf(item("a".repeat(40)), item("b".repeat(40))), 15000).isEmpty())
        assertTrue(h.observe(listOf(item(verified = 10)), 16000).isEmpty())
        assertEquals(1, h.observe(listOf(item(verified = 15)), 19000).size)
    }
}
