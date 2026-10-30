package io.github.gamesbyian.chunks.rpc

import org.junit.Assert.*
import org.junit.Test

/** Discovery and the transport must agree about proxy-decodable tenant paths. */
class RemoteSmartConnectMultiEncodedPathTest {
    @Test fun doubleAndTripleEncodedSeparatorsOrTraversalNeverProduceCandidates() {
        val paths = listOf(
            "/rutorrent2%252frutorrent3/RPC2",
            "/rutorrent2%25252Fprimary/RPC2",
            "/tenant%255c..%255cother/RPC2",
            "/tenant%250d%250aother/RPC2",
            "/tenant/%252e%252e/RPC2",
            "/tenant/%25252E%25252e/RPC2",
            "/tenant/.%252e/RPC2",
            "/tenant/%25252e./RPC2",
            "/tenant%2525252fother/RPC2",
            "/tenant%2525252525252fother/RPC2",
            "/tenant/%2525252e%2525252e/RPC2"
        )
        for (path in paths) {
            try {
                RemoteSmartConnect.withExactPath("box.example", path)
                fail("Advanced path accepted encoded alias: $path")
            } catch (_: IllegalArgumentException) { }
            try {
                RemoteSmartConnect.candidates("https://box.example$path", "alice")
                fail("Pasted path accepted encoded alias: $path")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun harmlessEncodingAndExplicitNumberedInstancesRemainValid() {
        assertEquals(
            listOf("https://box.example/%25members/rutorrent2/plugins/httprpc/action.php"),
            RemoteSmartConnect.candidates(
                "https://box.example/%25members/rutorrent2/", "alice"
            ).map { it.endpoint }
        )
        assertEquals(
            listOf("https://box.example/my%20account/rutorrent3/plugins/httprpc/action.php"),
            RemoteSmartConnect.candidates(
                "https://box.example/my%20account/rutorrent3/", "alice"
            ).map { it.endpoint }
        )
        assertEquals("https://box.example/my%20account/RPC%202",
            RemoteSmartConnect.withExactPath("box.example", "/my%20account/RPC%202"))
    }
}
