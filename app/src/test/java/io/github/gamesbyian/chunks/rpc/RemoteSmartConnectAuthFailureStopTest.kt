package io.github.gamesbyian.chunks.rpc

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Credentials and challenge failures are terminal, not invitations to guess more mounts. */
class RemoteSmartConnectAuthFailureStopTest {
    @Test fun digestAndIdentityProblemsStopAfterOneCandidate() = runBlocking {
        val cases = listOf(
            "Invalid RPC authentication identity",
            "Missing Digest realm",
            "Missing Digest nonce",
            "Unsupported Digest qop",
            "Unsupported Digest algorithm",
            "Unsupported Digest character set",
            "RPC authentication required"
        )
        val input = "https://unlisted.example"
        assertTrue(RemoteSmartConnect.candidates(input, "alice").size > 1)
        for (reason in cases) {
            var requests = 0
            try {
                RemoteSmartConnect.connect(
                    input = input, username = "alice", password = "secret",
                    probe = {
                        requests++
                        throw IOException(reason)
                    }
                )
                fail("Authentication problem must halt discovery")
            } catch (error: RemoteSmartConnect.ConnectionFailed) {
                assertEquals(RemoteSmartConnect.Category.AUTH, error.category)
                assertEquals(1, error.tested)
                assertEquals(1, error.attempts.size)
                assertFalse(error.message.orEmpty().contains("secret"))
            }
            assertEquals(1, requests)
        }
    }

    @Test fun unknownRpcEndpointFailureStillAllowsSafeBoundedAlternatives() = runBlocking {
        var tries = 0
        try {
            RemoteSmartConnect.connect(
                input = "https://unlisted.example", username = "alice", password = "secret",
                probe = {
                    tries++
                    throw IOException("rTorrent gateway returned HTTP 404")
                }
            )
            fail("No endpoint should be verified")
        } catch (error: RemoteSmartConnect.ConnectionFailed) {
            assertEquals(RemoteSmartConnect.Category.ENDPOINT, error.category)
            assertEquals(RemoteSmartConnect.candidates("https://unlisted.example", "alice").size, tries)
        }
    }
}
