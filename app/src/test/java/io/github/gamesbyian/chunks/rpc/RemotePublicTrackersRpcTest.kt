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
                val body = ("<methodResponse><params><param>" + handler(method) +
                    "</param></params></methodResponse>").toByteArray(Charsets.UTF_8)
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
    }
}
