package io.github.gamesbyian.chunks.rpc

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Basic account boundaries and Digest header construction must share one safe identity. */
class HttpRpcAuthorizationIdentitySafetyTest {
    private val basic = """Basic realm="Fixture""""
    private val digest = """Digest realm="Fixture", nonce="nonce", qop="auth", algorithm=SHA-256"""

    @Test fun ambiguousOrControlIdentityIsRefusedBeforeEitherAuthorizationHeader() {
        val invalid = listOf(
            "operator:other", "operator\r\nX-Header: injected",
            "operator\nInjected", "operator\rInjected",
            "operator\tadmin", "operator\u0000admin",
            "operator\u007fadmin", "operator\u0085admin",
            "operator\u2028admin", "operator\u2029admin",
            "x".repeat(257)
        )
        for (username in invalid) {
            for (challenge in listOf(basic, digest)) {
                try {
                    HttpRpcAuthorization.fromChallenge(
                        listOf(challenge), RemoteCredentials("https://example.test/RPC2", username, "secret"),
                        "POST", "/RPC2"
                    )
                    fail("Unsafe authentication identity was accepted")
                } catch (failure: IOException) {
                    assertEquals("Invalid RPC authentication identity", failure.message)
                    assertFalse(failure.message.orEmpty().contains(username))
                }
            }
        }
    }

    @Test fun hostileServerChallengeControlsNeverBecomeAuthorizationHeaders() {
        val suffixes = listOf('\u007f', '\u0085', '\u009f', '\u2028', '\u2029')
        for (bad in suffixes) {
            val challenges = listOf(
                """Basic realm="malicious@BAD@"""",
                """Digest realm="malicious@BAD@", nonce="once", qop="auth", algorithm=SHA-256""",
                """Digest realm="safe", nonce="malicious@BAD@", qop="auth", algorithm=SHA-256"""
            ).map { it.replace("@BAD@", bad.toString()) }
            for (challenge in challenges) {
                try {
                    HttpRpcAuthorization.fromChallenge(
                        listOf(challenge), RemoteCredentials("https://example.test/RPC2", "alice", "secret"),
                        "POST", "/RPC2"
                    )
                    fail("Unsafe challenge control accepted")
                } catch (failure: IOException) {
                    assertTrue(failure.message.orEmpty().contains("authentication challenge", ignoreCase = true))
                    assertFalse(failure.message.orEmpty().contains("malicious"))
                }
            }
        }
    }

    @Test fun ordinaryAccountAndColonContainingPasswordRemainUsable() {
        val identity = RemoteCredentials("https://example.test/RPC2", "alice.smith", "secret:with:colons")
        val b = HttpRpcAuthorization.fromChallenge(listOf(basic), identity, "POST", "/RPC2")
        assertTrue(b.startsWith("Basic "))
        assertEquals("alice.smith:secret:with:colons",
            String(java.util.Base64.getDecoder().decode(b.removePrefix("Basic ")), Charsets.UTF_8))
        val d = HttpRpcAuthorization.fromChallenge(listOf(digest), identity, "POST", "/RPC2")
        assertTrue(d.startsWith("Digest "))
        assertTrue(d.contains("username=\"alice.smith\""))
        assertFalse(d.contains("secret:with:colons"))
    }
}
