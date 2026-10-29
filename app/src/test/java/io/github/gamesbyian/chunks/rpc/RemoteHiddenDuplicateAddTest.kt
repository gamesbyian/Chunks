package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Exact-session preflight regression: a server can hide an existing torrent
 * from its main view. None of the three Add transports may then issue a write.
 * All requests stay on a disposable loopback HTTP gateway, without credentials.
 */
class RemoteHiddenDuplicateAddTest {
    private val bytes = ("d4:infod6:lengthi5e4:name8:test.txt12:piece lengthi16384e6:pieces20:" +
        "01234567890123456789ee").toByteArray(Charsets.ISO_8859_1)
    private val hash = TorrentMetainfoReader.preview(bytes).infoHashV1
    private val destination = "/home/test/downloads"
    private val magnet = "magnet:?xt=urn:btih:$hash"

    private enum class Route { MAGNET, TORRENT_BYTES, RUTORRENT_UPLOAD }

    private fun reply(value: String) =
        "<methodResponse><params><param><value>$value</value></param></params></methodResponse>"

    private fun rpcFault(code: Int): String = """<methodResponse><fault><value><struct>
        <member><name>faultCode</name><value><int>$code</int></value></member>
        <member><name>faultString</name><value><string>Fixture failed exact-hash lookup</string></value></member>
        </struct></value></fault></methodResponse>"""

    private class Gateway(private val nameReply: String) : AutoCloseable {
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val uploadCalls: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val endpoint: String
            get() = "http://127.0.0.1:${server.address.port}/rutorrent/"

        init {
            server.createContext("/rutorrent/plugins/httprpc/action.php") { exchange ->
                val xml = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val method = Regex("<methodName>([^<]+)</methodName>")
                    .find(xml)?.groupValues?.get(1).orEmpty()
                calls.add(method)
                val result = when (method) {
                    "d.multicall2" -> "<methodResponse><params><param><value><array><data>" +
                        "</data></array></value></param></params></methodResponse>"
                    "d.name" -> nameReply
                    else -> rpcFault(-32601)
                }.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "text/xml")
                exchange.sendResponseHeaders(200, result.size.toLong())
                exchange.responseBody.use { it.write(result) }
            }
            server.createContext("/rutorrent/php/addtorrent.php") { exchange ->
                uploadCalls.add(1)
                exchange.requestBody.use { it.readBytes() }
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            }
            server.start()
        }
        override fun close() = server.stop(0)
    }

    private suspend fun add(remote: RTorrentRemote, route: Route): RemoteAddOutcome =
        when (route) {
            Route.MAGNET ->
                remote.loadStopped(StoppedRemoteAddPlan.fromMagnet(magnet, destination))
            Route.TORRENT_BYTES ->
                remote.loadStoppedTorrentBytes(bytes, destination)
            Route.RUTORRENT_UPLOAD ->
                remote.loadStoppedViaRuTorrentUpload(bytes, destination)
        }

    @Test fun hiddenSessionDuplicateRefusesEveryAddTransportWithoutMutation() = runBlocking {
        for (route in Route.values()) {
            Gateway(reply("<string>Already exists outside main view</string>")).use { gateway ->
                val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
                assertEquals(RemoteAddOutcome.AlreadyPresent(hash), add(remote, route))
                assertEquals("only read-only identity queries for $route",
                    listOf("d.multicall2", "d.name"), gateway.calls)
                assertEquals("no HTTP upload for $route", 0, gateway.uploadCalls.size)
            }
        }
    }

    @Test fun failedExactSessionPreflightNeverWritesOrGuessesAbsence() = runBlocking {
        for (code in listOf(-32601, -503)) {
            for (route in Route.values()) {
                Gateway(rpcFault(code)).use { gateway ->
                    val remote = RTorrentRemote(RemoteCredentials(gateway.endpoint, "", ""))
                    val received = assertThrows(RpcFault::class.java) {
                        runBlocking { add(remote, route) }
                    }
                    assertEquals(code.toString(), received.code)
                    assertEquals("read-only preflight failed for $route",
                        listOf("d.multicall2", "d.name"), gateway.calls)
                    assertEquals(0, gateway.uploadCalls.size)
                }
            }
        }
    }
}
