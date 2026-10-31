package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Real loopback HTTP transport proofs: only a responseCode observed by
 * RTorrentRemote becomes the typed 404 authorizing a second candidate.
 * No external hostname, real credentials or mutating method is contacted.
 */
class RemoteSmartConnectObservedHttpStatusTest {
    private val version = "<methodResponse><params><param><value>" +
        "<string>0.9.8</string></value></param></params></methodResponse>"

    @Test fun genuineHttp404MayAdvanceToSecondSameOriginCandidate() = runBlocking {
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val received = Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.rawPath
            val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            received += path
            assertEquals("POST", exchange.requestMethod)
            assertTrue(body.contains("<methodName>system.client_version</methodName>"))
            if (path == "/RPC2") {
                val reply = version.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, reply.size.toLong())
                exchange.responseBody.use { it.write(reply) }
            } else {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
        }
        server.start()
        try {
            val host = "http://127.0.0.1:${server.address.port}"
            val verified = RemoteSmartConnect.connect(host, "alice", "private-password") {
                RTorrentRemote(it).version()
            }
            assertEquals("0.9.8", verified.response)
            assertEquals(2, verified.attempts)
            assertEquals(listOf("/rutorrent/plugins/httprpc/action.php", "/RPC2"), received)
            assertEquals(host + "/RPC2", verified.credentials.endpoint)
            assertEquals("alice", verified.credentials.username)
        } finally {
            server.stop(0)
        }
    }

    @Test fun actualHttp404IsTypedButMalformedHttp200StopsDiscovery() = runBlocking {
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val received = Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.rawPath
            received += path
            exchange.requestBody.use { it.readBytes() }
            val reply = "<html>login page</html>".toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            val host = "http://127.0.0.1:${server.address.port}"
            val failure = try {
                RemoteSmartConnect.connect(host, "alice", "private-password") {
                    RTorrentRemote(it).version()
                }
                fail("An HTTP 200 login page is not an rTorrent identity receipt")
                null
            } catch (problem: RemoteSmartConnect.ConnectionFailed) { problem }
            assertEquals(RemoteSmartConnect.Category.PROTOCOL, failure?.category)
            assertEquals(1, failure?.tested)
            assertEquals(listOf("/rutorrent/plugins/httprpc/action.php"), received)
            assertFalse(failure?.message.orEmpty().contains("private-password"))
        } finally {
            server.stop(0)
        }
    }
}
