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
    @Test fun preservesRawPercentEncodingInHostedAccountAndDirectRpcPaths() {
        // In the old component-URI constructor %20 became %2520. Hosted
        // account prefixes are legitimate URL paths, not strings to re-encode.
        assertEquals("https://box.test/my%20account/rutorrent/plugins/httprpc/action.php",
            RemoteEndpointHints.rpcAddress("https://box.test/my%20account/rutorrent/"))
        assertEquals("https://box.test/%25members/rutorrent/plugins/httprpc/action.php",
            RemoteEndpointHints.rpcAddress("https://box.test/%25members/rutorrent"))
        assertEquals("https://box.test/custom%2Frpc/RPC%202",
            RemoteEndpointHints.rpcAddress("https://box.test/custom%2Frpc/RPC%202"))
    }

    @Test fun mapsNumberedHostedRutorrentInstancesOnly() {
        assertEquals("https://box.seedhost.eu/rutorrent2/plugins/httprpc/action.php",
            RemoteEndpointHints.rpcAddress("https://box.seedhost.eu/rutorrent2/"))
        assertEquals("https://box.seedhost.eu/ruTorrent3/plugins/httprpc/action.php",
            RemoteEndpointHints.rpcAddress("https://box.seedhost.eu/ruTorrent3"))
        // Not a ruTorrent root: left exactly as entered.
        assertEquals("https://box.test/rutorrentx",
            RemoteEndpointHints.rpcAddress("https://box.test/rutorrentx/"))
        assertEquals("https://box.test/rutorrent2/RPC2",
            RemoteEndpointHints.rpcAddress("https://box.test/rutorrent2/RPC2"))
    }

    @Test fun keepsLiteralLoopbackIpv6AuthorityAndPort() {
        assertEquals("http://[::1]:8080/RPC2",
            RemoteEndpointHints.rpcAddress("http://[::1]:8080/RPC2"))
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
