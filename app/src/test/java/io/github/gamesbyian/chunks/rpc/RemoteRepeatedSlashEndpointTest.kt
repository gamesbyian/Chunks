package io.github.gamesbyian.chunks.rpc

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * A double separator is not an unambiguous account mount: some reverse
 * proxies merge it, others route it to another location. No credential should
 * ever be sent to a rewritten tenant URL or guessed fallback.
 */
class RemoteRepeatedSlashEndpointTest {
    @Test fun directSavedRpcEndpointsRejectDuplicateSeparatorsBeforeNetwork() {
        val ambiguous = listOf(
            "https://box.test//",
            "https://box.test//RPC2",
            "https://box.test/tenant//RPC1",
            "https://box.test/tenant//rutorrent2/plugins/httprpc/action.php",
            "https://box.test/rutorrent3//index.php",
            "https://box.test/rutorrent2//"
        )
        for (url in ambiguous) {
            try {
                RemoteEndpointHints.rpcAddress(url)
                fail("Direct endpoint mapper accepted ambiguous mount: $url")
            } catch (_: IllegalArgumentException) { }
            try {
                RTorrentRemote(RemoteCredentials(url, "alice", "secret"))
                fail("Saved transport accepted ambiguous mount: $url")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun discoveryRejectsDoubleSeparatorsInsteadOfFallingBackToOtherInstances() = runBlocking {
        val ambiguous = listOf(
            "https://box.test//",
            "https://box.test//RPC1",
            "https://box.test/tenant//rutorrent2/",
            "https://box.test/tenant/rutorrent2//index.php",
            "https://box.test/rutorrent3//"
        )
        for (url in ambiguous) {
            try {
                RemoteSmartConnect.candidates(url, "alice")
                fail("Discovery inferred candidates from ambiguous path: $url")
            } catch (_: IllegalArgumentException) { }
            var attempts = 0
            try {
                RemoteSmartConnect.connect(url, "alice", "secret") {
                    attempts++
                    "never contacted"
                }
                fail("Discovery sent credentials after refusing path: $url")
            } catch (_: IllegalArgumentException) { }
            assertEquals("Unsafe mount reached credential-bearing probe", 0, attempts)
        }
        // The advanced-path field must not strip a pasted duplicate-slash
        // host URL and then appear to validate a different canonical target.
        for (host in listOf("https://box.test//", "https://box.test///")) {
            try {
                RemoteSmartConnect.withExactPath(host, "/RPC2")
                fail("Advanced path normalized an ambiguous host into /RPC2")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun explicitNumberedAndEncodedTenantRoutesRemainStable() {
        val web = "https://box.test/my%20account/rutorrent2/"
        val expected = "https://box.test/my%20account/rutorrent2/plugins/httprpc/action.php"
        assertEquals(expected, RemoteEndpointHints.rpcAddress(web))
        assertEquals(listOf(expected), RemoteSmartConnect.candidates(web, "alice")
            .map { it.endpoint })
        assertEquals(listOf("https://box.test/tenant/rutorrent3/plugins/httprpc/action.php"),
            RemoteSmartConnect.candidates("https://box.test/tenant/rutorrent3/", "alice")
                .map { it.endpoint })
        assertEquals(listOf("https://box.test/tenant/RPC1"),
            RemoteSmartConnect.candidates("https://box.test/tenant/RPC1", "alice")
                .map { it.endpoint })
        RTorrentRemote(RemoteCredentials(expected, "alice", "secret"))
        assertEquals("https://box.test/tenant/RPC2",
            RemoteSmartConnect.withExactPath("box.test", "/tenant/RPC2"))
        try {
            RemoteSmartConnect.withExactPath("box.test", "/tenant//RPC2")
            fail("Advanced path should refuse the same alias")
        } catch (_: IllegalArgumentException) { }
    }
}
