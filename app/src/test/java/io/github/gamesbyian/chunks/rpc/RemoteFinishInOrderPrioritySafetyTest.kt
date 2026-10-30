package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import io.github.gamesbyian.chunks.engine.FinishInOrderPlanner
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** No outside network or rTorrent mutation is needed to prove the boundary. */
class RemoteFinishInOrderPrioritySafetyTest {
    private val hash = "ab".repeat(20)

    @Test fun onlyRtorrentThreeDocumentedPrioritiesCanBecomePlannerState() {
        assertEquals(0, RemoteFinishInOrderPort.toPlanner(0))
        assertEquals(FinishInOrderPlanner.NORMAL, RemoteFinishInOrderPort.toPlanner(1))
        assertEquals(FinishInOrderPlanner.HIGH, RemoteFinishInOrderPort.toPlanner(2))
        for (invalid in listOf(-1, 3, 7, 255, Int.MIN_VALUE, Int.MAX_VALUE)) {
            try {
                RemoteFinishInOrderPort.toPlanner(invalid)
                fail("Invalid remote priority $invalid was converted into a trusted baseline")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun onlyPlannerPrioritiesInTheKnownDomainCanBecomeRpcCommands() {
        assertEquals(0, RemoteFinishInOrderPort.toRemote(0))
        assertEquals(1, RemoteFinishInOrderPort.toRemote(FinishInOrderPlanner.LOW))
        assertEquals(1, RemoteFinishInOrderPort.toRemote(FinishInOrderPlanner.NORMAL))
        assertEquals(2, RemoteFinishInOrderPort.toRemote(FinishInOrderPlanner.HIGH))
        for (invalid in listOf(-1, FinishInOrderPlanner.HIGH + 1, 99, Int.MIN_VALUE, Int.MAX_VALUE)) {
            try {
                RemoteFinishInOrderPort.toRemote(invalid)
                fail("Invalid planner priority $invalid was converted to a valid rTorrent command")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun unknownPriorityFromRpcCannotActivateRollingScheduler() = runBlocking {
        val calls = mutableListOf<String>()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(request)?.groupValues?.get(1).orEmpty()
            calls += method
            val content = when (method) {
                "d.hash" -> "<value><string>$hash</string></value>"
                "d.size_chunks" -> "<value><i8>1</i8></value>"
                "d.bitfield" -> "<value><string>0</string></value>"
                "f.multicall" -> "<value><array><data><value><array><data>" +
                    "<value><string>episode.mkv</string></value>" +
                    "<value><i8>4096</i8></value>" +
                    "<value><i8>3</i8></value>" +
                    "<value><i8>0</i8></value>" +
                    "<value><i8>1</i8></value>" +
                    "</data></array></value></data></array></value>"
                else -> throw AssertionError("Unexpected RPC method $method")
            }
            val bytes = "<methodResponse><params><param>$content</param></params></methodResponse>"
                .toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            val port = RemoteFinishInOrderPort(remote) { _, _ -> true }
            assertNull("Unknown rTorrent priority cannot be accepted as Normal", port.observe(hash))
            assertTrue(calls.contains("f.multicall"))
            assertFalse(calls.any { it == "f.priority.set" || it == "d.update_priorities" })
        } finally {
            server.stop(0)
        }
    }
}
