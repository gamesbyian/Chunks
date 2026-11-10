package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Real loopback HTTP 401 responses, not a synthetic exception injected into
 * a Compose poll. No credentials/challenge material is logged or persisted.
 */
class RTorrentTypedReadAuthDenialTest {
    private data class Request(val method: String, val authorized: Boolean)

    private fun withHttp(
        challenge: String,
        username: String = "reader",
        status: Int = 401,
        acceptBasic: Boolean = false,
        test: suspend (RTorrentRemote, List<Request>) -> Unit
    ) = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(xml)
                ?.groupValues?.get(1).orEmpty()
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            val accepted = acceptBasic && authorization?.startsWith("Basic ") == true
            requests += Request(method, authorization != null)
            if (accepted) {
                val response = (
                    "<methodResponse><params><param><value><string>0.9.8</string></value>" +
                        "</param></params></methodResponse>"
                ).toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/xml")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            } else {
                if (challenge.isNotEmpty())
                    exchange.responseHeaders.add("WWW-Authenticate", challenge)
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:" + server.address.port + "/RPC2",
                username, "secret-do-not-log"))
            test(remote, requests)
        } finally {
            server.stop(0)
        }
    }

    private suspend fun requireStatus401(remote: RTorrentRemote) {
        try {
            remote.version()
            fail("A rejected read must never become an authenticated 200")
        } catch (error: RpcHttpStatusException) {
            assertEquals(401, error.status)
            assertFalse("No credential or challenge data may escape",
                error.message.orEmpty().contains("secret-do-not-log"))
        }
    }

    @Test fun unsupportedDigestChallengeRemainsTyped401AndIsNotRepeated() =
        withHttp("Digest realm=\"private\", nonce=\"abc\", algorithm=SHA-512") {
                remote, requests ->
            requireStatus401(remote)
            assertEquals(1, requests.size)
            assertEquals("system.client_version", requests.single().method)
            assertFalse(requests.single().authorized)
            assertEquals(RemoteReadAuthEvidence.NO_READ_CHALLENGE,
                remote.readAuthenticationEvidence())
        }

    @Test fun malformedChallengeAndInvalidBasicPrincipalRemainTyped401() {
        withHttp("Digest realm=\"unterminated, nonce=\"abc\"") {
                remote, requests ->
            requireStatus401(remote)
            assertEquals(1, requests.size)
        }
        withHttp("Basic realm=\"private\"", username = "ambiguous:principal") {
                remote, requests ->
            requireStatus401(remote)
            assertEquals(1, requests.size)
            assertTrue(requests.none { it.authorized })
        }
    }

    @Test fun validBasicChallengeStillAllowsOneAuthorizedReadRetry() =
        withHttp("Basic realm=\"read-only\"", acceptBasic = true) {
                remote, requests ->
            assertEquals("0.9.8", remote.version())
            assertEquals(2, requests.size)
            assertFalse(requests[0].authorized)
            assertTrue(requests[1].authorized)
            assertEquals(RemoteReadAuthEvidence.READ_CHALLENGE_ACCEPTED,
                remote.readAuthenticationEvidence())
        }

    @Test fun genuine503RemainsTyped503WithoutAuthenticationReplay() =
        withHttp("Basic realm=\"unused\"", status = 503) { remote, requests ->
            try {
                remote.version()
                fail("HTTP 503 cannot produce a valid read")
            } catch (error: RpcHttpStatusException) {
                assertEquals(503, error.status)
            }
            assertEquals(1, requests.size)
            assertFalse(requests.single().authorized)
        }

    @Test fun unusableChallengeInWritePreflightNeverDispatchesMutation() =
        withHttp("Digest realm=\"private\", nonce=\"abc\", algorithm=SHA-512") {
                remote, requests ->
            // This is a new *exact-hash read* rejection, before any mutation.
            // Typed HTTP 401 must be retained; Uncertain is reserved for an
            // operation which may actually have written to the engine.
            try {
                remote.setStarted("ab".repeat(20), true)
                fail("A rejected exact session hash cannot authorize Start")
            } catch (denied: RpcHttpStatusException) {
                assertEquals(401, denied.status)
            }
            assertEquals("Only the read-only exact-hash preflight may reach the wire",
                listOf("d.hash"), requests.map { it.method })
            assertTrue(requests.none { it.authorized })
        }
}
