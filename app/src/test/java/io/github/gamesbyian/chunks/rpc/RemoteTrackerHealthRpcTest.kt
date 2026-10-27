package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteTrackerHealthRpcTest {
    private val hash = "cc".repeat(20)
    private fun num(n: Long) = "<value><i8>$n</i8></value>"
    private fun str(s: String) = "<value><string>$s</string></value>"
    private fun array(rows: List<List<Long>>): String =
        "<value><array><data>" + rows.joinToString("") { row ->
            "<value><array><data>" + row.joinToString("") { num(it) } +
                "</data></array></value>"
        } + "</data></array></value>"
    private fun fault() = """<methodResponse><fault><value><struct>
        <member><name>faultCode</name><value><int>-32601</int></value></member>
        <member><name>faultString</name><value><string>Unsupported getter</string></value></member>
        </struct></value></fault></methodResponse>"""

    private fun withServer(
        handler: (String, String) -> String,
        block: suspend (RTorrentRemote, MutableList<String>) -> Unit
    ) = runBlocking {
        val methods = mutableListOf<String>()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(request)
                ?.groupValues?.get(1).orEmpty()
            synchronized(methods) { methods.add(method) }
            val raw = handler(method, request)
            val envelope = if (raw.startsWith("<methodResponse>")) raw else
                "<methodResponse><params><param>$raw</param></params></methodResponse>"
            val body = envelope.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            block(client, methods)
        } finally { server.stop(0) }
    }

    @Test fun fixedGetterListNeverReadsTrackerUrlsOrPasskeys() = withServer({ method, request ->
        when (method) {
            "d.hash" -> str(hash)
            "d.tracker_size" -> num(2)
            "t.multicall" -> {
                assertFalse(request.contains("t.url"))
                assertFalse(request.contains("t.id"))
                assertFalse(request.contains("t.message"))
                array(listOf(listOf(1L, 3L, 0L, 6L), listOf(0L, 0L, 4L, 0L)))
            }
            else -> throw AssertionError("Unexpected RPC getter")
        }
    }) { remote, methods ->
        val observed = remote.readTrackerHealth(hash)
        assertNotNull(observed)
        assertEquals(2, observed!!.total)
        assertEquals(1, observed.enabled)
        assertEquals(3L, observed.successfulAnnounces)
        assertEquals(4L, observed.failedAnnounces)
        assertEquals(6L, observed.maxAnnouncedPeers)
        assertEquals(listOf("d.hash", "d.tracker_size", "t.multicall", "d.hash"), methods)
    }

    @Test fun gatewayWithoutRecentPeerGetterFallsBackToThreeReadOnlyFields() =
        withServer({ method, request ->
            when (method) {
                "d.hash" -> str(hash)
                "d.tracker_size" -> num(1)
                "t.multicall" -> if (request.contains("t.latest_sum_peers=")) fault()
                    else array(listOf(listOf(1L, 0L, 2L)))
                else -> throw AssertionError("Unexpected RPC getter")
            }
        }) { remote, methods ->
            val observed = remote.readTrackerHealth(hash)
            assertEquals(1, observed?.total)
            assertNull(observed?.maxAnnouncedPeers)
            assertEquals(2L, observed?.failedAnnounces)
            assertEquals(listOf("d.hash", "d.tracker_size", "t.multicall",
                "t.multicall", "d.hash"), methods)
        }

    @Test fun wrongHashAfterReadingTrackerRowsRefusesResult() = withServer({ method, _ ->
        when (method) {
            "d.hash" -> str(if (++guard == 1) hash else "dd".repeat(20))
            "d.tracker_size" -> num(0)
            else -> throw AssertionError("Unexpected RPC getter")
        }
    }) { remote, _ ->
        try {
            remote.readTrackerHealth(hash)
            fail("Changed infohash should block tracker report")
        } catch (_: IllegalStateException) { }
    }

    private var guard = 0
}
