package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.InetAddress
import java.util.Base64
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Synthetic loopback HTTP RPC gateway. No server credentials, real torrents,
 * or destructive network commands are used in public CI.
 */
class RTorrentRemoteFixtureTest {
    private data class Reply(val code: Int, val xml: String)

    private class Fixture(handler: (String, String?) -> Reply) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val methods: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val endpoint: String get() = "http://127.0.0.1:${server.address.port}/RPC2"

        init {
            server.createContext("/RPC2") { exchange ->
                val rpcBody = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                methods.add(rpcBody)
                val reply = handler(rpcBody, exchange.requestHeaders.getFirst("Authorization"))
                val bytes = reply.xml.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "text/xml")
                exchange.sendResponseHeaders(reply.code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }

    private fun result(value: String): String =
        "<methodResponse><params><param><value>$value</value></param></params></methodResponse>"

    private fun fault(code: Int, message: String): String =
        """<methodResponse><fault><value><struct>
           <member><name>faultCode</name><value><int>$code</int></value></member>
           <member><name>faultString</name><value><string>$message</string></value></member>
        </struct></value></fault></methodResponse>"""

    @Test fun readOnlyListingThroughAuthenticatedGateway() = runBlocking {
        val expectedAuth = "Basic " + Base64.getEncoder()
            .encodeToString("demo:fixture-secret".toByteArray(Charsets.UTF_8))
        var sawAuth = false
        Fixture { request, authorization ->
            sawAuth = authorization == expectedAuth
            when {
                request.contains("<methodName>system.client_version</methodName>") ->
                    Reply(200, result("<string>0.9.8</string>"))
                request.contains("<methodName>d.multicall2</methodName>") ->
                    Reply(200, result("""<array><data><value><array><data>
                      <value><string>ABCDEF</string></value>
                      <value><string>Star Trek</string></value>
                      <value><i8>100</i8></value>
                      <value><i8>70</i8></value>
                      <value><int>1</int></value>
                      <value><string>/test/tv</string></value>
                    </data></array></value></data></array>"""))
                else -> Reply(200, fault(404, "Unexpected RPC method"))
            }
        }.use { fixture ->
            val remote = RTorrentRemote(RemoteCredentials(fixture.endpoint, "demo", "fixture-secret"))
            assertEquals("0.9.8", remote.version())
            val listed = remote.listTransfers()
            assertEquals(1, listed.size)
            assertEquals("ABCDEF", listed.single().hash)
            assertEquals("/test/tv", listed.single().directory)
            assertEquals(0.7f, listed.single().fractionComplete, 0.0001f)
            assertTrue(sawAuth)
            assertTrue(fixture.methods.all { it.contains("system.client_version") || it.contains("d.multicall2") })
        }
    }

    @Test fun methodProbeDifferentiatesUnavailableEnumeration() = runBlocking {
        Fixture { request, _ ->
            when {
                request.contains("system.client_version") -> Reply(200, result("<string>0.16</string>"))
                request.contains("system.listMethods") -> Reply(200, fault(-32601, "Method not found"))
                else -> Reply(200, fault(-32601, "Unknown"))
            }
        }.use { fixture ->
            val probe = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", "")).probe()
            assertEquals("0.16", probe.version)
            assertNull(probe.advertisedMethods)
            assertNull(probe.multicallAdvertised)
        }
    }

    @Test fun methodProbeReturnsReportedCapabilities() = runBlocking {
        Fixture { request, _ ->
            when {
                request.contains("system.client_version") -> Reply(200, result("<string>0.16</string>"))
                request.contains("system.listMethods") ->
                    Reply(200, result("""<array><data>
                      <value><string>system.client_version</string></value>
                      <value><string>d.multicall2</string></value>
                    </data></array>"""))
                else -> Reply(200, fault(404, "Unexpected"))
            }
        }.use { fixture ->
            val probe = RTorrentRemote(RemoteCredentials(fixture.endpoint, "", "")).probe()
            assertEquals(true, probe.multicallAdvertised)
            assertEquals(2, probe.advertisedMethods?.size)
        }
    }

    @Test fun redirectIsRejectedRatherThanSendingCredentialsElsewhere() = runBlocking {
        Fixture { _, _ -> Reply(302, "Moved") }.use { fixture ->
            try {
                RTorrentRemote(RemoteCredentials(fixture.endpoint, "user", "password")).version()
                fail("Redirect must not be followed")
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains("302"))
            }
        }
    }

    @Test fun plainHttpToNonLoopbackIsRefusedBeforeConnection() {
        try {
            RTorrentRemote(RemoteCredentials("http://example.org/RPC2", "", ""))
            fail("Remote HTTP without TLS must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("HTTPS"))
        }
    }
}
