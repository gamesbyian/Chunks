package io.github.gamesbyian.chunks.rpc

import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteSmartConnectTest {
    @Test fun oneUserPastedInstanceNeverEscapesItsScope() {
        val tests = mapOf(
            "https://ianseeds.wood.seedhost.eu/rutorrent/" to
                "https://ianseeds.wood.seedhost.eu/rutorrent/plugins/httprpc/action.php",
            "https://ianseeds.wood.seedhost.eu/rutorrent2/" to
                "https://ianseeds.wood.seedhost.eu/rutorrent2/plugins/httprpc/action.php",
            "https://ianseeds.wood.seedhost.eu/rutorrent3/index.php" to
                "https://ianseeds.wood.seedhost.eu/rutorrent3/plugins/httprpc/action.php",
            "https://wood.seedhost.eu/ianseeds/RPC1" to
                "https://wood.seedhost.eu/ianseeds/RPC1"
        )
        for ((input, expected) in tests)
            assertEquals(input, listOf(expected),
                RemoteSmartConnect.candidates(input, "ianseeds").map { it.endpoint })
    }

    @Test fun providerRootSuggestionsUseDocumentedPathsAndSameOrigin() {
        val choices = mapOf(
            "mybox.usbx.me" to "/RPC2",
            "host.whatbox.ca" to "/xmlrpc",
            "rutorrent.user.appboxes.co" to "/RPC2",
            "wood.seedhost.eu" to "/ianseeds/rutorrent/plugins/httprpc/action.php",
            "custom.example" to "/rutorrent/plugins/httprpc/action.php"
        )
        for ((raw, path) in choices) {
            val list = RemoteSmartConnect.candidates(raw, "ianseeds")
            assertTrue(list.size in 1..6)
            assertEquals("https://$raw$path", list.first().endpoint)
            assertTrue(list.all { it.endpoint.startsWith("https://$raw/") })
            assertEquals(list.size, list.distinctBy { it.endpoint }.size)
        }
    }

    @Test fun preservesExplicitCustomPortAndRawEncodedRpcPath() {
        assertEquals(listOf("https://custom.example:9443/my%20account/RPC%202"),
            RemoteSmartConnect.candidates(
                "https://custom.example:9443/my%20account/RPC%202", "user"
            ).map { it.endpoint })
    }

    @Test fun refusesCredentialsQueriesAndInsecureOrInvalidSchemes() {
        val invalid = listOf(
            "http://wood.seedhost.eu/rutorrent/",
            "ftp://host.example/rutorrent",
            "https://user:pass@host.example/rutorrent/",
            "https://host.example/rutorrent/?token=1",
            "https://host.example/RPC2#secret",
            "https://host.example:invalid/rutorrent/",
            "https://host.example/../../RPC2",
            "   "
        )
        for (input in invalid) {
            try {
                RemoteSmartConnect.candidates(input, "someuser")
                fail("Should reject unsafe endpoint input: $input")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun repeatedAuthFailureStopsBeforeTryingAnotherCandidate() = runBlocking {
        val attempted = mutableListOf<String>()
        try {
            RemoteSmartConnect.connect("wood.seedhost.eu", "ianseeds", "secret") { credentials ->
                attempted += credentials.endpoint
                throw IOException("rTorrent gateway returned HTTP 401")
            }
            fail("Expected failed login")
        } catch (failure: RemoteSmartConnect.ConnectionFailed) {
            assertEquals(RemoteSmartConnect.Category.AUTH, failure.category)
            assertEquals(1, failure.tested)
            assertEquals(1, attempted.size)
            assertFalse(failure.message.orEmpty().contains("secret"))
        }
    }

    @Test fun badCertificateAndUnreachableHostStopImmediately() = runBlocking {
        for ((reason, expected) in listOf(
            SSLHandshakeException("bad certificate") to RemoteSmartConnect.Category.TLS,
            UnknownHostException("missing") to RemoteSmartConnect.Category.NETWORK
        )) {
            var attempts = 0
            try {
                RemoteSmartConnect.connect("custom.example", "user", "pass") {
                    attempts++
                    throw reason
                }
                fail("Expected fail closed")
            } catch (failure: RemoteSmartConnect.ConnectionFailed) {
                assertEquals(expected, failure.category)
                assertEquals(1, attempts)
            }
        }
    }

    @Test fun failedRpcPathsMayFallbackButSuccessRequiresTheProbe() = runBlocking {
        val attempted = mutableListOf<String>()
        val success = RemoteSmartConnect.connect("host.whatbox.ca", "user", "pass") { credentials ->
            attempted += credentials.endpoint
            if (attempted.size == 1) throw IOException("rTorrent gateway returned HTTP 404")
            // In production the injected probe must read a version AND a
            // complete torrent view, not merely accept HTTP 200.
            "verified-version-and-list"
        }
        assertEquals("https://host.whatbox.ca/rutorrent/plugins/httprpc/action.php",
            success.credentials.endpoint)
        assertEquals(2, success.attempts)
        assertEquals("verified-version-and-list", success.response)
    }

    @Test fun boundedGenericHostnamePlanningDoesNotGuessOtherHostnames() {
        val candidates = RemoteSmartConnect.candidates("https://my-server.example/", "user")
        assertTrue(candidates.size <= 6)
        assertTrue(candidates.all { it.endpoint.startsWith("https://my-server.example/") })
        assertFalse(candidates.any { it.endpoint.contains("user:") })
    }
}
