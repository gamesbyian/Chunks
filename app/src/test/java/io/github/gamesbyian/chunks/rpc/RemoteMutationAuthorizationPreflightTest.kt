package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * A mutating XML-RPC POST may have been applied even when its HTTP response
 * says 401. Negotiate credentials through a read-only method, never a write.
 */
class RemoteMutationAuthorizationPreflightTest {
    private val hash = "a".repeat(40)
    private val basic = """Basic realm="Fixture""""
    private val digest = """Digest realm="Fixture", nonce="one", qop="auth", algorithm=SHA-256"""
    private val ok =
        "<methodResponse><params><param><value><i8>0</i8></value></param></params></methodResponse>"
    private data class Sent(val method: String, val authorization: String?)

    private class Gateway(
        val answer: (com.sun.net.httpserver.HttpExchange, String, Int) -> Unit
    ) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val sent: MutableList<Sent> = Collections.synchronizedList(mutableListOf())
        val endpoint get() = "http://127.0.0.1:${server.address.port}/RPC2"
        init {
            server.createContext("/RPC2") { exchange ->
                if (exchange.requestMethod == "HEAD") {
                    exchange.sendResponseHeaders(405, -1)
                    exchange.close()
                    return@createContext
                }
                val xml = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(xml)?.groupValues?.get(1).orEmpty()
                val count = synchronized(sent) {
                    sent.add(Sent(method, exchange.requestHeaders.getFirst("Authorization")))
                    sent.count { it.method == method }
                }
                answer(exchange, method, count)
            }
            server.start()
        }
        override fun close() = server.stop(0)
    }
    private fun reply(exchange: com.sun.net.httpserver.HttpExchange, status: Int,
                      content: String = "", challenge: String? = null) {
        if (challenge != null) exchange.responseHeaders.add("WWW-Authenticate", challenge)
        val body = content.toByteArray(Charsets.UTF_8)
        if (status == 401) exchange.sendResponseHeaders(status, -1)
        else exchange.sendResponseHeaders(status, body.size.toLong())
        if (status == 401) exchange.close()
        else exchange.responseBody.use { it.write(body) }
    }

    @Test fun basicAndDigestMutationAuthorizeBeforeFirstWrite() = runBlocking {
        for (challenge in listOf(basic, digest)) {
            Gateway { e, method, _ -> when (method) {
                "system.client_version" -> reply(e, 401, challenge = challenge)
                "d.start" -> reply(e, 200, ok)
                else -> error("Unexpected RPC $method")
            } }.use { gateway ->
                val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "alice", "secret"))
                assertEquals(0L, remote.call("d.start", listOf(hash)))
                assertEquals(listOf("system.client_version", "d.start"),
                    gateway.sent.map { it.method })
                assertNull(gateway.sent.first().authorization)
                val prefix = if (challenge.startsWith("Digest")) "Digest " else "Basic "
                assertTrue(gateway.sent.last().authorization.orEmpty().startsWith(prefix))
                assertFalse(gateway.sent.last().authorization.orEmpty().contains("secret"))
            }
        }
    }

    @Test fun postApplyAuthenticationChallengeNeverReplaysMutation() = runBlocking {
        for (challenge in listOf(basic, digest)) {
            Gateway { e, method, _ -> when (method) {
                "system.client_version" -> reply(e, 401, challenge = challenge)
                "d.start" -> reply(e, 401, challenge = challenge)
                else -> error("Unexpected RPC $method")
            } }.use { gateway ->
                val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "alice", "secret"))
                try {
                    remote.call("d.start", listOf(hash))
                    fail("A challenged write cannot be confirmed or repeated")
                } catch (failure: IOException) {
                    assertTrue(failure.message.orEmpty().contains("401"))
                }
                assertEquals(listOf("system.client_version", "d.start"),
                    gateway.sent.map { it.method })
                assertNotNull(gateway.sent.last().authorization)
            }
        }
    }

    @Test fun acceptedProtectedReadPreauthorizesFirstWriteWhenVersionIsPublic() = runBlocking {
        for (challenge in listOf(basic, digest)) {
            val expectedPrefix = if (challenge.startsWith("Digest")) "Digest " else "Basic "
            Gateway { exchange, method, _ ->
                val authorization = exchange.requestHeaders.getFirst("Authorization")
                when (method) {
                    // A common split policy: public version, protected
                    // inventory getters and writes, unsupported HEAD.
                    "d.name" -> if (authorization == null)
                        reply(exchange, 401, challenge = challenge)
                    else reply(exchange, 200,
                        "<methodResponse><params><param><value><string>sample</string></value>" +
                            "</param></params></methodResponse>")
                    "system.client_version" -> reply(exchange, 200,
                        "<methodResponse><params><param><value><string>0.9.8</string></value>" +
                            "</param></params></methodResponse>")
                    "d.start" -> if (authorization?.startsWith(expectedPrefix) == true)
                        reply(exchange, 200, ok)
                    else reply(exchange, 401, challenge = challenge)
                    else -> error("Unexpected method $method")
                }
            }.use { gateway ->
                val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "alice", "secret"))
                assertEquals("sample", remote.call("d.name", listOf(hash)))
                assertEquals(RemoteReadAuthEvidence.READ_CHALLENGE_ACCEPTED,
                    remote.readAuthenticationEvidence())
                assertEquals(0L, remote.call("d.start", listOf(hash)))
                assertEquals(listOf("d.name", "d.name", "system.client_version", "d.start"),
                    gateway.sent.map { it.method })
                assertNull("The initial protected read was anonymous", gateway.sent[0].authorization)
                assertTrue(gateway.sent[1].authorization.orEmpty().startsWith(expectedPrefix))
                assertNull("Preflight must stay credential-free", gateway.sent[2].authorization)
                assertTrue("First and only write must be preauthorized",
                    gateway.sent[3].authorization.orEmpty().startsWith(expectedPrefix))
                assertEquals(1, gateway.sent.count { it.method == "d.start" })
            }
        }
    }

    @Test fun cachedChallengeNeverAuthorizesSecondWriteAfterRejectedFirstWrite() = runBlocking {
        Gateway { exchange, method, _ ->
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            when (method) {
                "d.name" -> if (authorization == null)
                    reply(exchange, 401, challenge = basic)
                else reply(exchange, 200,
                    "<methodResponse><params><param><value><string>sample</string></value>" +
                        "</param></params></methodResponse>")
                "system.client_version" -> reply(exchange, 200, ok)
                // Simulate a broken gateway that applies a write before
                // sending 401. The first write MUST NOT be replayed.
                "d.start" -> reply(exchange, 401, challenge = basic)
                else -> error("Unexpected method $method")
            }
        }.use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "alice", "secret"))
            assertEquals("sample", remote.call("d.name", listOf(hash)))
            try {
                remote.call("d.start", listOf(hash))
                fail("A 401 after the single authorized write cannot prove rollback")
            } catch (failure: IOException) {
                assertTrue(failure.message.orEmpty().contains("401"))
            }
            assertEquals(listOf("d.name", "d.name", "system.client_version", "d.start"),
                gateway.sent.map { it.method })
            assertTrue(gateway.sent.last().authorization.orEmpty().startsWith("Basic "))
            assertEquals(1, gateway.sent.count { it.method == "d.start" })
        }
    }

    @Test fun anonymousPreflightCannotAuthorizeASecondWrite() = runBlocking {
        Gateway { e, method, _ -> when (method) {
            "system.client_version" -> reply(e, 200, ok)
            "d.start" -> reply(e, 401, challenge = basic)
            else -> error("Unexpected RPC $method")
        } }.use { gateway ->
            try {
                RTorrentRemote(RemoteCredentials(gateway.endpoint, "alice", "secret"))
                    .call("d.start", listOf(hash))
                fail("401 after the write is uncertain")
            } catch (_: IOException) {
                assertEquals(listOf("system.client_version", "d.start"),
                    gateway.sent.map { it.method })
                assertNull(gateway.sent.last().authorization)
            }
        }
    }

    @Test fun noCredentialsSendsOnlyOneMutationAttempt() = runBlocking {
        Gateway { e, method, _ -> when (method) {
            "d.start" -> reply(e, 401, challenge = basic)
            else -> error("Unexpected RPC $method")
        } }.use { gateway ->
            try {
                RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
                    .call("d.start", listOf(hash))
                fail("Anonymous 401 is not permission to resubmit")
            } catch (_: IOException) {
                assertEquals(listOf("d.start"), gateway.sent.map { it.method })
            }
        }
    }

    @Test fun readOnlyChallengeStillNegotiatesNormally() = runBlocking {
        Gateway { e, method, _ -> when (method) {
            "system.client_version" ->
                if (e.requestHeaders.getFirst("Authorization") == null)
                    reply(e, 401, challenge = basic)
                else reply(e, 200,
                    "<methodResponse><params><param><value><string>0.9.8</string></value></param></params></methodResponse>")
            else -> error("Unexpected RPC $method")
        } }.use { gateway ->
            assertEquals("0.9.8",
                RTorrentRemote(RemoteCredentials(gateway.endpoint, "alice", "secret")).version())
            assertEquals(listOf("system.client_version", "system.client_version"),
                gateway.sent.map { it.method })
        }
    }
}
