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
            "operator:other", "operator\\r\\nX-Header: injected",
            "operator\\nInjected", "operator\\rInjected",
            "operator\\tadmin", "operator\\u0000admin",
            "operator\\u007fadmin", "operator\\u0085admin",
            "operator\\u2028admin", "operator\\u2029admin",
            "x".repeat(257)
        ).map { it.replace("\\r", "\r").replace("\\n", "\n")
            .replace("\\t", "\t").replace("\\u0000", "\u0000")
            .replace("\\u007f", "\u007f").replace("\\u0085", "\u0085")
            .replace("\\u2028", "\u2028").replace("\\u2029", "\u2029") }
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

    @Test fun ordinaryAccountAndColonContainingPasswordRemainUsable() {
        val identity = RemoteCredentials("https://example.test/RPC2", "alice.smith", "secret:with:colons")
        val b = HttpRpcAuthorization.fromChallenge(listOf(basic), identity, "POST", "/RPC2")
        assertTrue(b.startsWith("Basic "))
        assertEquals("alice.smith:secret:with:colons",
            String(java.util.Base64.getDecoder().decode(b.removePrefix("Basic ")), Charsets.UTF_8))
        val d = HttpRpcAuthorization.fromChallenge(listOf(digest), identity, "POST", "/RPC2")
        assertTrue(d.startsWith("Digest "))
        assertTrue(d.contains("username=\\\"alice.smith\\\""))
        assertFalse(d.contains("secret:with:colons"))
    }
}
