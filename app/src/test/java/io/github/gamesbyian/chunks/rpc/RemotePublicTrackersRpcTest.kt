package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemotePublicTrackersRpcTest {
    private val hash = "cc".repeat(20)
    private fun num(n: Long) = "<value><i8>$n</i8></value>"
    private fun str(s: String) = "<value><string>$s</string></value>"

    private fun withServer(handler: (String) -> String, block: suspend (RTorrentRemote, List<String>) -> Unit) =
        runBlocking {
            val methods = mutableListOf<String>()
            val requests = StringBuilder()
            val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
            server.createContext("/RPC2") { exchange ->
                val request = exchange.requestBody.bufferedReader().use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>").find(request)?.groupValues?.get(1).orEmpty()
                synchronized(methods) { methods.add(method); requests.append(request) }
                val responseValue = handler(method)
                // The fault-injection case must exercise a real XML-RPC fault
                // rather than accidentally wrapping it as an ordinary value.
                val response = if (responseValue.startsWith("<methodResponse>")) responseValue
                    else "<methodResponse><params><param>" + responseValue +
                        "</param></params></methodResponse>"
                val body = response.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                block(RTorrentRemote(RemoteCredentials("http://127.0.0.1:${server.address.port}/RPC2", "", "")),
                    methods)
                if ("t.url" in requests && "d.is_private" in methods)
                    assertTrue("t.url only after a public check", methods.indexOf("d.is_private") <
                        methods.indexOf("t.multicall"))
            } finally { server.stop(0) }
        }

    @Test fun privateTransferNeverReadsTrackerUrls() = withServer({ method ->
        when (method) {
            "d.hash" -> str(hash)
            "d.is_private" -> num(1)
            else -> error("Unexpected $method")
        }
    }) { remote, methods ->
        assertSame(RTorrentRemote.PublicTrackers.Private, remote.readPublicTrackers(hash))
        assertFalse(methods.contains("t.multicall"))
        assertFalse(methods.contains("d.tracker_size"))
    }

    @Test fun publicTransferReturnsOnlyEnabledTrackerUrls() = withServer({ method ->
        when (method) {
            "d.hash" -> str(hash)
            "d.is_private" -> num(0)
            "d.tracker_size" -> num(2)
            "t.multicall" -> "<value><array><data>" +
                "<value><array><data>${str("udp://tracker.example:80/announce")}${num(1)}</data></array></value>" +
                "<value><array><data>${str("http://off.example/announce")}${num(0)}</data></array></value>" +
                "</data></array></value>"
            else -> error("Unexpected $method")
        }
    }) { remote, methods ->
        assertEquals(RTorrentRemote.PublicTrackers.Urls(listOf("udp://tracker.example:80/announce")),
            remote.readPublicTrackers(hash))
        assertEquals("identity guarded before and after", 2, methods.count { it == "d.hash" })
        assertEquals("privacy must be checked before fetching and before returning URLs", 2,
            methods.count { it == "d.is_private" })
        assertEquals(listOf("d.hash", "d.is_private", "d.tracker_size",
            "t.multicall", "d.is_private", "d.hash"), methods)
    }

    @Test fun newlyPrivateTorrentMustNeverReturnPreviouslyFetchedTrackerUrls() {
        var privacyReads = 0
        withServer({ method ->
            when (method) {
                "d.hash" -> str(hash)
                "d.is_private" -> {
                    privacyReads++
                    num(if (privacyReads == 1) 0 else 1)
                }
                "d.tracker_size" -> num(1)
                "t.multicall" -> "<value><array><data>" +
                    "<value><array><data>${str("https://private.example/announce?passkey=SECRET")}${num(1)}</data></array></value>" +
                    "</data></array></value>"
                else -> error("Unexpected $method")
            }
        }) { remote, methods ->
            assertSame(RTorrentRemote.PublicTrackers.Private, remote.readPublicTrackers(hash))
            assertEquals(2, privacyReads)
            assertEquals(listOf("d.hash", "d.is_private", "d.tracker_size",
                "t.multicall", "d.is_private"), methods)
        }
    }

    @Test fun finalPrivacyGetterFaultNeverReleasesTrackerUrls() {
        var privacyReads = 0
        withServer({ method ->
            when (method) {
                "d.hash" -> str(hash)
                "d.is_private" -> {
                    privacyReads++
                    if (privacyReads == 1) num(0)
                    else """<methodResponse><fault><value><struct>
                        <member><name>faultCode</name><value><int>-503</int></value></member>
                        <member><name>faultString</name><value><string>Unable to verify privacy</string></value></member>
                        </struct></value></fault></methodResponse>"""
                }
                "d.tracker_size" -> num(1)
                "t.multicall" -> "<value><array><data>" +
                    "<value><array><data>${str("udp://public.example/announce")}${num(1)}</data></array></value>" +
                    "</data></array></value>"
                else -> error("Unexpected $method")
            }
        }) { remote, methods ->
            val received = assertThrows(RpcFault::class.java) {
                runBlocking { remote.readPublicTrackers(hash) }
            }
            assertEquals("-503", received.code)
            assertEquals(2, privacyReads)
            assertEquals(listOf("d.hash", "d.is_private", "d.tracker_size",
                "t.multicall", "d.is_private"), methods)
        }
    }

    @Test fun initiallyPrivateTorrentStillReturnsWithoutReadingAnyTrackerUrl() {
        withServer({ method ->
            when (method) {
                "d.hash" -> str(hash)
                "d.is_private" -> num(1)
                else -> error("Read-only private transfer must not fetch $method")
            }
        }) { remote, methods ->
            assertSame(RTorrentRemote.PublicTrackers.Private, remote.readPublicTrackers(hash))
            assertEquals(listOf("d.hash", "d.is_private"), methods)
        }
    }
}
