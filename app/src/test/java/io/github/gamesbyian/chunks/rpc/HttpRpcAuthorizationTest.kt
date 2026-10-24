package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HttpRpcAuthorizationTest {
    private val credentials = RemoteCredentials("http://127.0.0.1/RPC2", "testuser", "testpass")
    private fun hashed(s: String, algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun digestSha256ChallengeProducesValidRfc7616Response() {
        val header = HttpRpcAuthorization.fromChallenge(
            listOf("""Digest realm="Chunks QA", nonce="noncelive", qop="auth,auth-int", algorithm=SHA-256, opaque="stamp" """),
            credentials, "POST", "/RPC2")
        assertTrue(header.startsWith("Digest "))
        val cnonce = Regex("""cnonce="([a-f0-9]+)"""").find(header)!!.groupValues[1]
        val actual = Regex("""response="([a-f0-9]+)"""").find(header)!!.groupValues[1]
        val ha1 = hashed("testuser:Chunks QA:testpass", "SHA-256")
        val ha2 = hashed("POST:/RPC2", "SHA-256")
        assertEquals(hashed("$ha1:noncelive:00000001:$cnonce:auth:$ha2", "SHA-256"), actual)
        assertTrue(header.contains("opaque=\"stamp\""))
        assertFalse(header.contains("testpass"))
    }

    @Test fun basicChallengeUsesExpectedCredentialsOnlyAfterChallenge() {
        val header = HttpRpcAuthorization.fromChallenge(
            listOf("""Basic realm="QA seedbox""""), credentials, "POST", "/RPC2")
        assertEquals("Basic " + Base64.getEncoder().encodeToString(
            "testuser:testpass".toByteArray(Charsets.UTF_8)), header)
    }

    @Test fun digestMalformedAndUnsupportedChallengesFailClosed() {
        for (challenge in listOf(
            """Digest realm="x", nonce="n", qop="auth-int" """,
            """Digest realm="x", nonce="n", algorithm=SHA-512""",
            """Digest realm="x", nonce="n", algorithm=MD5, userhash=true""",
            """Digest realm="x", nonce="n", realm="duplicated" """,
            "Digest realm=\"bad\r\nInjected: 1\", nonce=\"n\"",
            """Bearer token="" """
        )) {
            try {
                HttpRpcAuthorization.fromChallenge(listOf(challenge), credentials, "POST", "/RPC2")
                fail("Challenge must be rejected")
            } catch (_: IOException) { }
        }
    }

    private fun start(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            handler(exchange)
        }
        server.start()
        return server
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, code: Int, body: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (code == 401 || code == 302) {
            exchange.sendResponseHeaders(code, -1)
            exchange.close()
        } else {
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
    private val versionResponse =
        """<?xml version="1.0"?><methodResponse><params><param><value><string>0.9.8</string></value></param></params></methodResponse>"""

    @Test fun remoteRpcVersionNegotiatesDigestAndNeverPreemptivelySendsSecret() = runBlocking {
        var firstUnauthenticated = false
        var secondAuthenticated = false
        val server = start { e ->
            val auth = e.requestHeaders.getFirst("Authorization")
            if (auth == null) {
                firstUnauthenticated = true
                e.responseHeaders.add("WWW-Authenticate", """Digest realm="QA", nonce="nonce", qop="auth", algorithm=MD5""")
                respond(e, 401)
            } else {
                secondAuthenticated = auth.startsWith("Digest ") && auth.contains("username=\"testuser\"")
                respond(e, 200, versionResponse)
            }
        }
        try {
            val r = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "testuser", "testpass"))
            assertEquals("0.9.8", r.version())
            assertTrue(firstUnauthenticated)
            assertTrue(secondAuthenticated)
        } finally { server.stop(0) }
    }

    @Test fun remoteRpcVersionNegotiatesBasicOnlyWhenPrompted() = runBlocking {
        var firstUnauthenticated = false
        var secondAuthenticated = false
        val server = start { e ->
            val auth = e.requestHeaders.getFirst("Authorization")
            if (auth == null) {
                firstUnauthenticated = true
                e.responseHeaders.add("WWW-Authenticate", """Basic realm="QA" """)
                respond(e, 401)
            } else {
                secondAuthenticated = auth == "Basic " + Base64.getEncoder().encodeToString(
                    "testuser:testpass".toByteArray(Charsets.UTF_8))
                respond(e, 200, versionResponse)
            }
        }
        try {
            assertEquals("0.9.8", RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "testuser", "testpass")).version())
            assertTrue(firstUnauthenticated)
            assertTrue(secondAuthenticated)
        } finally { server.stop(0) }
    }

    @Test fun rpcRejectsRedirectWithoutForwardingCredentials() = runBlocking {
        var received = 0
        val server = start { e ->
            received++
            e.responseHeaders.add("Location", "https://other.example/RPC2")
            respond(e, 302)
        }
        try {
            try {
                RTorrentRemote(RemoteCredentials(
                    "http://127.0.0.1:${server.address.port}/RPC2", "testuser", "testpass")).version()
                fail("Must reject redirects")
            } catch (_: IOException) { }
            assertEquals(1, received)
        } finally { server.stop(0) }
    }
}
