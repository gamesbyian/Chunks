package io.github.gamesbyian.chunks.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Invariant controls `connect-allows-http-downgrade` and `connect-allows-embedded-credentials`:
 * the address a person pastes can never produce a cleartext non-loopback candidate, and a
 * pasted secret never reaches a candidate or a diagnostic.
 */
class RemoteSmartConnectSafetyTest {
    private fun refused(input: String): String {
        try {
            RemoteSmartConnect.candidates(input, "alice")
        } catch (e: IllegalArgumentException) {
            return e.message.orEmpty()
        }
        fail("expected a refusal for the pasted address")
        return ""
    }

    @Test fun plainHttpToARemoteHostIsRefusedNotUpgradedOrAttempted() {
        for (input in listOf("http://seedbox.example/rutorrent/", "http://203.0.113.9/RPC2", "http://wood.seedhost.eu")) {
            assertEquals(input, "A secure HTTPS address is required", refused(input))
        }
    }

    @Test fun plainHttpIsAllowedOnlyOnLoopback() {
        for (input in listOf("http://127.0.0.1:18080/RPC2", "http://localhost:18080/RPC2")) {
            val candidates = RemoteSmartConnect.candidates(input, "alice")
            assertTrue(input, candidates.isNotEmpty() && candidates.all { it.endpoint.startsWith("http://") })
        }
    }

    @Test fun embeddedCredentialsQueryAndFragmentAreRefusedWithoutEchoingTheSecret() {
        for (input in listOf("https://alice:hunter2@wood.seedhost.eu/rutorrent/",
            "https://wood.seedhost.eu/rutorrent/?token=hunter2", "https://wood.seedhost.eu/rutorrent/#hunter2")) {
            val message = refused(input)
            assertFalse("a pasted secret must never reach a diagnostic: $message", message.contains("hunter2"))
            assertEquals("Enter a host/URL without embedded credentials, query or fragment", message)
        }
    }
}
