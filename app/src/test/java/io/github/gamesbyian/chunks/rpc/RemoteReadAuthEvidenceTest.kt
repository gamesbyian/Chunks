package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real localhost HTTP/401 boundaries, no protected host or mutation. */
class RemoteReadAuthEvidenceTest {
    private val version = "<methodResponse><params><param><value><string>0.9.8</string>" +
        "</value></param></params></methodResponse>"
    private val emptyList = "<methodResponse><params><param><value><array><data/>" +
        "</array></value></param></params></methodResponse>"

    private class Gateway(
        val challengeRead: Boolean,
        val rejectAfterAuthorization: Boolean = false,
        val malformedSuccess: Boolean = false
    ) : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val address get() = "http://127.0.0.1:${server.address.port}/RPC2"
        init {
            server.createContext("/RPC2") { exchange ->
                val body = exchange.requestBody.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(body)?.groupValues?.get(1).orEmpty()
                val authorization = exchange.requestHeaders.getFirst("Authorization")
                requests.add(method to authorization)
                if (challengeRead && (authorization == null || rejectAfterAuthorization)) {
                    exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"read\"")
                    exchange.sendResponseHeaders(401, -1)
                    exchange.close()
                } else {
                    val reply = if (malformedSuccess) "<html>login page</html>" else
                        if (method == "system.client_version") version else emptyList
                    val bytes = reply.toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            server.start()
        }
        override fun close() = server.stop(0)
    }

    @Test fun anonymousPublicInventoryCannotConfirmSuppliedUsername() = runBlocking {
        Gateway(challengeRead = false).use { server ->
            val remote = RTorrentRemote(RemoteCredentials(server.address, "invented-user", "wrong-secret"))
            assertEquals("0.9.8", remote.version())
            assertTrue(remote.listTransfers("main").isEmpty())
            assertEquals(RemoteReadAuthEvidence.NO_READ_CHALLENGE,
                remote.readAuthenticationEvidence())
            assertTrue(server.requests.all { it.second == null })
        }
    }

    @Test fun sameEndpointChallengedSuccessfulReadRecordsOnlyBoundedEvidence() = runBlocking {
        Gateway(challengeRead = true).use { server ->
            val remote = RTorrentRemote(RemoteCredentials(server.address, "alice", "secret"))
            assertEquals(RemoteReadAuthEvidence.NO_READ_CHALLENGE,
                remote.readAuthenticationEvidence())
            assertEquals("0.9.8", remote.version())
            assertEquals(RemoteReadAuthEvidence.READ_CHALLENGE_ACCEPTED,
                remote.readAuthenticationEvidence())
            assertEquals(2, server.requests.size)
            assertNull(server.requests.first().second)
            assertTrue(server.requests.last().second.orEmpty().startsWith("Basic "))
            assertFalse(server.requests.last().second.orEmpty().contains("secret"))
        }
    }

    @Test fun rejectedChallengeOrMalformedReadDoesNotGrantAuthEvidence() = runBlocking {
        Gateway(challengeRead = true, rejectAfterAuthorization = true).use { server ->
            val remote = RTorrentRemote(RemoteCredentials(server.address, "alice", "wrong"))
            try {
                remote.version()
                fail("A second 401 cannot prove credential acceptance")
            } catch (_: java.io.IOException) { }
            assertEquals(RemoteReadAuthEvidence.NO_READ_CHALLENGE,
                remote.readAuthenticationEvidence())
        }
        Gateway(challengeRead = false, malformedSuccess = true).use { server ->
            val remote = RTorrentRemote(RemoteCredentials(server.address, "alice", "secret"))
            try {
                remote.version()
                fail("HTML cannot count as verified XML-RPC")
            } catch (_: Exception) { }
            assertEquals(RemoteReadAuthEvidence.NO_READ_CHALLENGE,
                remote.readAuthenticationEvidence())
        }
    }
}
