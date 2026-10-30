package io.github.gamesbyian.chunks.rpc

import org.junit.Assert.*
import org.junit.Test

/**
 * Construction-time boundary for saved/direct endpoints that did not pass
 * through RemoteSmartConnect. No network or credentials are used.
 */
class RTorrentRemoteEndpointScopeTest {
    @Test fun directAdapterRejectsAmbiguousRpcMountsBeforeAnyNetworkIO() {
        val rejected = listOf(
            "https://box.test/rutorrent2%2frutorrent3/RPC2",
            "https://box.test/rutorrent2%5C..%5Crutorrent3/RPC2",
            "https://box.test/rutorrent2%252frutorrent3/RPC2",
            "https://box.test/rutorrent2%25252Frutorrent3/RPC2",
            "https://box.test/tenant%00/RPC2",
            "https://box.test/tenant%250d%250a/RPC2",
            "https://box.test/tenant%7f/RPC2",
            "https://box.test/rutorrent2/../rutorrent3/RPC2",
            "https://box.test/rutorrent2/%2e%2e/rutorrent3/RPC2",
            "https://box.test/rutorrent2/%252E%252e/rutorrent3/RPC2",
            "https://box.test/rutorrent2/%25252E%25252e/rutorrent3/RPC2"
        )
        for (endpoint in rejected) {
            try {
                RTorrentRemote(RemoteCredentials(endpoint, "", ""))
                fail("Unsafe stored RPC endpoint was accepted: $endpoint")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun legitimateHostedAccountEncodingAndNumberedInstanceStillConstruct() {
        val accepted = listOf(
            "https://box.test/my%20account/rutorrent2/",
            "https://box.test/%25members/rutorrent3/",
            "https://box.test/my%20account/RPC%202",
            "https://box.test/rutorrent3/plugins/httprpc/action.php",
            "http://127.0.0.1:49153/RPC2"
        )
        for (endpoint in accepted) {
            RTorrentRemote(RemoteCredentials(endpoint, "", ""))
        }
    }
}
