package io.github.gamesbyian.chunks.rpc

import org.junit.Assert.*
import org.junit.Test

class RemoteEndpointHintsTest {
    @Test fun resolvesHostedRutorrentAndPreservesDirectRpc() {
        assertEquals("https://box.seedhost.eu/rutorrent/plugins/httprpc/action.php",
            RemoteEndpointHints.rpcAddress("https://box.seedhost.eu/rutorrent/"))
        assertEquals("https://box.test/account/rutorrent/plugins/httprpc/action.php",
            RemoteEndpointHints.rpcAddress("https://box.test/account/rutorrent/"))
        assertEquals("https://box.test/account/RPC1",
            RemoteEndpointHints.rpcAddress("https://box.test/account/RPC1"))
    }
    @Test fun rejectsUnencryptedRemoteAndCredentialsInUrl() {
        for (candidate in listOf("http://box.test/rutorrent",
            "https://user:pass@box.test/rutorrent",
            "https://box.test/rutorrent/?token=secret")) {
            try {
                RemoteEndpointHints.rpcAddress(candidate)
                fail("Expected invalid endpoint")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
