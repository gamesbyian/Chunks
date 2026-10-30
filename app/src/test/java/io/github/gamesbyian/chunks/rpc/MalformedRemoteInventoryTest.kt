package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** A malformed server array must never release a complete-inventory readback gate. */
class MalformedRemoteInventoryTest {
    @Test fun malformedInventoryDoesNotBecomeSuccessfulEmptyListOrTriggerFallback() = runBlocking {
        for (array in listOf(
            "<array/>",
            "<array><data><unexpected/></data></array>",
            "<array><data/><data><value><string>hidden transfer</string></value></data></array>"
        )) {
            val methods = mutableListOf<String>()
            val server = HttpServer.create(
                InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
            server.createContext("/RPC2") { exchange ->
                val request = exchange.requestBody.bufferedReader().use { it.readText() }
                methods += Regex("<methodName>([^<]+)</methodName>").find(request)!!.groupValues[1]
                val reply = ("<methodResponse><params><param><value>$array</value>" +
                    "</param></params></methodResponse>").toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, reply.size.toLong())
                exchange.responseBody.use { it.write(reply) }
            }
            server.start()
            try {
                val remote = RTorrentRemote(RemoteCredentials(
                    "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
                try {
                    remote.listTransfers()
                    fail("Malformed XML-RPC array must fail the whole snapshot")
                } catch (_: IllegalArgumentException) {
                    // A malformed reply is neither an optional-field fault nor a paging signal.
                }
                assertEquals(listOf("d.multicall2"), methods)
            } finally {
                server.stop(0)
            }
        }
    }
}
