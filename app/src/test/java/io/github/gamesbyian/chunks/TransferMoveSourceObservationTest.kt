package io.github.gamesbyian.chunks

import com.sun.net.httpserver.HttpServer
import io.github.gamesbyian.chunks.model.TransferMove.Observation
import io.github.gamesbyian.chunks.rpc.RTorrentRemote
import io.github.gamesbyian.chunks.rpc.RemoteCredentials
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The source of a Move is the server's exact session, never the filtered main view. */
class TransferMoveSourceObservationTest {
    private val hash = "ab".repeat(20)

    private fun fault(code: Int, explanation: String) = """
        <methodResponse><fault><value><struct>
        <member><name>faultCode</name><value><int>$code</int></value></member>
        <member><name>faultString</name><value><string>$explanation</string></value></member>
        </struct></value></fault></methodResponse>
    """.trimIndent()

    private fun readOnlyReply(name: String) =
        "<methodResponse><params><param><value><string>$name</string></value></param></params></methodResponse>"

    private suspend fun withLoopback(
        reply: String,
        block: suspend (RTorrentRemote, List<String>) -> Unit
    ) {
        val methods = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0
        )
        server.createContext("/RPC2") { request ->
            val body = request.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(body)?.groupValues?.get(1).orEmpty()
            methods.add(method)
            // Deliberately omit all main-view data. Only the exact keyed
            // session getter is valid evidence; any list call is a test error.
            val xml = if (method == "d.name") reply else fault(-32601, "No main view")
            val bytes = xml.toByteArray(Charsets.UTF_8)
            request.sendResponseHeaders(200, bytes.size.toLong())
            request.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            block(client, methods.toList().also { /* caller reads after RPC */ })
        } finally {
            server.stop(0)
        }
    }

    @Test fun hiddenFromMainViewStillExistsInExactSession() = runBlocking {
        withLoopback(readOnlyReply("Invisible but present")) { remote, _ ->
            assertEquals(Observation.Present, TransferMoveObservations.server(remote, hash))
        }
    }

    @Test fun onlySpecificUnknownHashFaultCanProveSourceAbsent() = runBlocking {
        withLoopback(fault(-501, "Could not find info-hash.")) { remote, _ ->
            assertEquals(Observation.Absent, TransferMoveObservations.server(remote, hash))
        }
    }

    @Test fun unrelatedRpcFaultCannotBeMisreportedAsSourceRemoved() = runBlocking {
        withLoopback(fault(-32601, "Method unavailable")) { remote, _ ->
            assertEquals(Observation.Unknown, TransferMoveObservations.server(remote, hash))
        }
    }
}
