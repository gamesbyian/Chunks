package io.github.gamesbyian.chunks

import io.github.gamesbyian.chunks.engine.LocalTransferState
import io.github.gamesbyian.chunks.engine.LocalTransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LocalServicePollTest {
    private fun transfer(stopped: Boolean, progressPpm: Int): LocalTransferStatus =
        LocalTransferStatus(
            infoHash = "a".repeat(40),
            name = "test", state = LocalTransferState.DOWNLOADING,
            stopped = stopped, progressPpm = progressPpm,
            wantedBytes = 100, doneBytes = 0, payloadDownloadedBytes = 0,
            payloadUploadedBytes = 0, peers = 0, savePath = "/tmp",
            error = null
        )

    @Test fun failedReadIsNotTreatedAsIdle() {
        assertSame(LocalServicePoll.Unavailable, LocalServicePoll.classify(null))
    }

    @Test fun observedEmptyOrStoppedQueueMayStopTheService() {
        assertSame(LocalServicePoll.Idle, LocalServicePoll.classify(emptyList()))
        assertSame(LocalServicePoll.Idle, LocalServicePoll.classify(listOf(transfer(true, 1_000_000))))
    }

    @Test fun runningTransfersRemainActiveAndIgnoreStoppedEntries() {
        assertEquals(
            LocalServicePoll.Active(2, 50),
            LocalServicePoll.classify(
                listOf(transfer(false, 250_000), transfer(true, 0), transfer(false, 750_000))
            )
        )
    }

    @Test fun averageUsesLongArithmeticAndHandlesLargeQueues() {
        val list = List(20_000) { transfer(false, 1_000_000) }
        assertEquals(LocalServicePoll.Active(20_000, 100), LocalServicePoll.classify(list))
    }
}
