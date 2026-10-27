package io.github.gamesbyian.chunks.rpc

import org.junit.Assert.*
import org.junit.Test

class RemoteTransferActivityTest {
    @Test fun reasonForNoTrafficDistinguishesEngineFromPeers() {
        assertTrue(RemoteTransferActivity(false, false, 0, 0).summary().contains("not active"))
        assertTrue(RemoteTransferActivity(true, false, 0, 0).summary().contains("not opened"))
        assertTrue(RemoteTransferActivity(true, true, 0, 0).summary().contains("0 peers"))
        assertTrue(RemoteTransferActivity(true, true, 3, 0).summary().contains("no download traffic"))
        assertTrue(RemoteTransferActivity(true, true, 3, 140000).summary().contains("reporting download traffic"))
    }

    @Test fun unavailableCountersDoNotPretendZeroOrLeakServerData() {
        val report = RemoteTransferActivity(null, null, null, null)
        assertTrue(report.summary().contains("did not expose enough"))
        assertFalse(report.summary().contains("http"))
    }
}
