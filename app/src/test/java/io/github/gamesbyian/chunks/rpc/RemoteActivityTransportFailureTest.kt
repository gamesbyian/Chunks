package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * The installed remote details screen must see authentication and transport
 * failures, instead of silently interpreting them as unsupported statistics.
 * Disposable loopback HTTP only; no live accounts or mutation calls.
 */
class RemoteActivityTransportFailureTest {
    private val hash = "ab".repeat(20)

    private class Gateway(private val replyStatus: Int?, private val replyFault: Int? = null) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val endpoint: String get() = "http://127.0.0.1:${server.address.port}/RPC2"

        init {
            server.createContext("/RPC2") { exchange ->
                val request = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(request)?.groupValues?.get(1).orEmpty()
                methods.add(method)
                if (replyStatus != null) {
                    if (replyStatus == 401)
                        exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"Fixture\"")
                    exchange.sendResponseHeaders(replyStatus, -1)
                    exchange.close()
                } else {
                    val xml = """<methodResponse><fault><value><struct>
                        <member><name>faultCode</name><value><int>${replyFault ?: -32601}</int></value></member>
                        <member><name>faultString</name><value><string>${if (replyFault == -501) "Could not find info-hash." else "Method not found"}</string></value></member>
                        </struct></value></fault></methodResponse>"""
                    val bytes = xml.toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            server.start()
        }
        override fun close() = server.stop(0)
    }

    @Test fun credentialedUnauthorizedActivityStopsAtFirstGetter() = runBlocking {
        Gateway(401).use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "alice", "invalid"))
            val thrown = assertThrows(RpcHttpStatusException::class.java) {
                runBlocking { remote.readActivity(hash) }
            }
            assertEquals(401, thrown.status)
            // The read-only Basic challenge is retryable ONCE; no further
            // optional getters may conceal the rejected login.
            assertEquals(listOf("d.is_active", "d.is_active"), gateway.methods)
        }
    }

    @Test fun unavailableGatewayStopsAfterOneReadInsteadOfFour() = runBlocking {
        Gateway(503).use { gateway ->
            val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
            val thrown = assertThrows(RpcHttpStatusException::class.java) {
                runBlocking { remote.readActivity(hash) }
            }
            assertEquals(503, thrown.status)
            assertEquals(listOf("d.is_active"), gateway.methods)
        }
    }

    @Test fun unsupportedOptionalMethodsStillReturnUnknownDiagnostics() = runBlocking {
        Gateway(null).use { gateway ->
            val result = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
                .readActivity(hash)
            assertNull(result.active)
            assertNull(result.open)
            assertNull(result.connectedPeers)
            assertNull(result.downloadBytesPerSecond)
            assertEquals(listOf("d.is_active", "d.is_open", "d.peers_connected", "d.down.rate"),
                gateway.methods)
        }
    }

    @Test fun vanishedExactHashCannotMasqueradeAsUnsupportedStatistic() = runBlocking {
        Gateway(null, -501).use { gateway ->
            val failure = assertThrows(RpcFault::class.java) {
                runBlocking {
                    RTorrentRemote(RemoteCredentials(gateway.endpoint, "", "")).readActivity(hash)
                }
            }
            assertTrue(failure.isUnknownInfoHash())
            assertEquals(listOf("d.is_active"), gateway.methods)
        }
    }
}
