package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** A combined WWW-Authenticate challenge must never silently choose Basic over Digest. */
class HttpRpcCombinedChallengeTest {
    private val credentials = RemoteCredentials("http://127.0.0.1/RPC2", "alice", "fixture-secret")
    private val combined = """Basic realm="legacy", Digest realm="QA", nonce="challenge", qop="auth", algorithm=SHA-256"""

    @Test fun digestWinsRegardlessOfOrderOrHeaderPacking() {
        val cases = listOf(
            listOf(combined),
            listOf("Digest realm=\"QA\", nonce=\"challenge\", qop=\"auth\", algorithm=SHA-256, Basic realm=\"legacy\""),
            listOf("Basic realm=\"legacy\"", "Digest realm=\"QA\", nonce=\"challenge\", qop=\"auth\", algorithm=SHA-256")
        )
        for (headers in cases) {
            val response = HttpRpcAuthorization.fromChallenge(headers, credentials, "POST", "/RPC2")
            assertTrue("Digest must win over Basic: $headers", response.startsWith("Digest "))
            assertTrue(response.contains("algorithm=SHA-256"))
            assertFalse(response.contains("fixture-secret"))
        }
    }

    @Test fun nonstandardHorizontalTabsCannotDowngradeDigest() {
        // RFC 9110 requires SP here. A nonconforming Digest challenge using
        // HTAB must not disappear behind Basic in a merged header.
        val cases = listOf(
            "Basic realm=\"legacy\", Digest\trealm=\"QA\", nonce=\"n\", qop=\"auth\"",
            "Digest\t realm=\"QA\", nonce=\"n\", qop=\"auth\", Basic\trealm=\"legacy\"",
            "Basic\trealm=\"legacy\", Digest  \trealm=\"QA\", nonce=\"n\", qop=\"auth\""
        )
        for (header in cases) {
            val value = HttpRpcAuthorization.fromChallenge(
                listOf(header), credentials, "POST", "/RPC2")
            assertTrue("Digest must beat Basic even with HTAB: $header", value.startsWith("Digest "))
            assertTrue(value.contains("nonce=\"n\""))
            assertFalse(value.contains("fixture-secret"))
        }
    }

    @Test fun schemePrefixInsideAnotherTokenCannotBecomeDigest() {
        val value = HttpRpcAuthorization.fromChallenge(
            listOf("Basic realm=\"legacy\", Digestive realm=\"not-a-challenge\""),
            credentials, "POST", "/RPC2")
        assertTrue(value.startsWith("Basic "))
    }

    @Test fun commasAndSchemeWordsInsideQuotesAreNotNewChallenges() {
        val response = HttpRpcAuthorization.fromChallenge(
            listOf("""Basic realm="legacy", Digest realm="QA", nonce="one,two", qop="auth,auth-int", opaque="route,Basic realm=trap" """),
            credentials, "POST", "/RPC2")
        assertTrue(response.startsWith("Digest "))
        assertTrue(response.contains("nonce=\"one,two\""))
        assertTrue(response.contains("opaque=\"route,Basic realm=trap\""))
        assertTrue(response.contains("qop=auth"))
    }

    @Test fun unsupportedOrMalformedDigestCannotFallbackToBasic() {
        for (header in listOf(
            """Basic realm="legacy", Digest realm="QA", nonce="n", algorithm=SHA-512""",
            "Digest realm=\"QA\", nonce=\"n\", qop=\"auth-int\", Basic realm=\"legacy\"",
            "Basic realm=\"unterminated, Digest realm=\"QA\", nonce=\"n\"",
            "Basic realm=\"legacy\", Digest realm=\"QA\", nonce=\"n\"\r\nInjected: true"
        )) {
            try {
                HttpRpcAuthorization.fromChallenge(listOf(header), credentials, "POST", "/RPC2")
                fail("Must fail closed on invalid Digest/header, without exposing Basic")
            } catch (_: IOException) { }
        }
    }

    @Test fun actualRpcTransportChoosesDigestFromSingleMergedHeader() = runBlocking {
        var requests = 0
        var firstHadNoAuthorization = false
        var secondUsedDigest = false
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            requests++
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            if (requests == 1) {
                firstHadNoAuthorization = authorization == null
                exchange.responseHeaders.add("WWW-Authenticate", combined)
                exchange.sendResponseHeaders(401, -1)
            } else {
                secondUsedDigest = authorization?.startsWith("Digest ") == true &&
                    authorization.contains("username=\"alice\"") &&
                    authorization.contains("uri=\"/RPC2\"")
                val reply = """<methodResponse><params><param><value><string>0.9.8</string></value></param></params></methodResponse>"""
                    .toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, reply.size.toLong())
                exchange.responseBody.use { it.write(reply) }
            }
            exchange.close()
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "alice", "fixture-secret"))
            assertEquals("0.9.8", remote.version())
            assertEquals(2, requests)
            assertTrue(firstHadNoAuthorization)
            assertTrue(secondUsedDigest)
        } finally {
            server.stop(0)
        }
    }
}
