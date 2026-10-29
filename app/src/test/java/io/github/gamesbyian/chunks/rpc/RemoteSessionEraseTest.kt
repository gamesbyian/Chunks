package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteSessionEraseTest {
    private val hash = "ab".repeat(20)
    private val directory = "/home/dev/Public Examples"
    private val name = "public-example.ogg"

    private fun value(s: String) = "<value><string>" + s + "</string></value>"
    private fun number(n: Int) = "<value><i8>" + n + "</i8></value>"
    private fun rows(present: Boolean, nameOverride: String): String {
        if (!present) return "<value><array><data></data></array></value>"
        val columns = listOf(value(hash), value(nameOverride), number(5),
            number(0), number(0), value(directory), number(0), number(0), number(0))
        return "<value><array><data><value><array><data>" +
            columns.joinToString("") + "</data></array></value></data></array></value>"
    }

    private fun unknownHashFault() = """<methodResponse><fault><value><struct>
        <member><name>faultCode</name><value><int>-501</int></value></member>
        <member><name>faultString</name><value><string>Could not find info-hash.</string></value></member>
        </struct></value></fault></methodResponse>"""

    private suspend fun withServer(
        initialName: String = name,
        applyErase: Boolean = true,
        action: suspend (RTorrentRemote, MutableList<String>) -> Unit
    ) {
        var present = true
        val methods = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val payload = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(payload)?.groupValues?.get(1).orEmpty()
            methods.add(method)
            if (method == "d.erase" && applyErase) present = false
            val xmlBody = when (method) {
                "d.multicall2" -> rows(present, initialName)
                "d.hash" -> value(hash)
                "d.erase" -> number(0)
                else -> number(0)
            }
            val reply = if (method == "d.hash" && !present) unknownHashFault()
                else "<methodResponse><params><param>" + xmlBody +
                    "</param></params></methodResponse>"
            val xml = reply.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, xml.size.toLong())
            exchange.responseBody.use { it.write(xml) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:" + server.address.port + "/RPC2", "", ""))
            action(remote, methods)
        } finally { server.stop(0) }
    }

    @Test fun erasesOneExactHashAndVerifiesAbsence() = runBlocking {
        withServer { remote, methods ->
            assertEquals(SessionEraseOutcome.VerifiedRemoved(hash),
                remote.eraseFromSession(hash, name, directory))
            assertEquals(listOf("d.multicall2", "d.hash", "d.erase", "d.hash"), methods)
            assertEquals(SessionEraseOutcome.AlreadyAbsent(hash),
                remote.eraseFromSession(hash, name, directory))
            assertEquals(1, methods.count { it == "d.erase" })
        }
    }

    @Test fun staleTorrentNameBlocksMutation() = runBlocking {
        withServer(initialName = "renamed-by-another-user") { remote, methods ->
            try {
                remote.eraseFromSession(hash, name, directory)
                fail("Stale torrent state must be refused")
            } catch (_: IllegalArgumentException) { }
            assertEquals(listOf("d.multicall2"), methods)
        }
    }

    @Test fun acknowledgedEraseWithoutGoneReadbackIsPendingNotVerified() = runBlocking {
        withServer(applyErase = false) { remote, methods ->
            assertEquals(SessionEraseOutcome.PendingRemoval(hash),
                remote.eraseFromSession(hash, name, directory))
            assertEquals(listOf("d.multicall2", "d.hash", "d.erase", "d.hash"), methods)
        }
    }

    @Test fun gatewayFailureAfterRealEraseIsUncertainWithNoBlindRetry() = runBlocking {
        var present = true
        var eraseCalls = 0
        val methods = mutableListOf<String>()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(body)?.groupValues?.get(1).orEmpty()
            methods.add(method)
            if (method == "d.erase") {
                // The engine applied the requested write, but the proxy lost
                // the acknowledgement. Another erase MUST NOT be auto-issued.
                eraseCalls++
                present = false
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            } else {
                val reply = if (method == "d.hash" && !present) unknownHashFault()
                    else "<methodResponse><params><param>" +
                        (if (method == "d.hash") value(hash) else rows(present, name)) +
                        "</param></params></methodResponse>"
                val encoded = reply.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, encoded.size.toLong())
                exchange.responseBody.use { it.write(encoded) }
            }
        }
        server.start()
        try {
            val client = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            assertEquals(SessionEraseOutcome.Uncertain(hash),
                client.eraseFromSession(hash, name, directory))
            assertEquals(1, eraseCalls)
            assertEquals(listOf("d.multicall2", "d.hash", "d.erase"), methods)
            // Only a separately initiated read-only reconciliation is safe.
            assertEquals(SessionEraseOutcome.AlreadyAbsent(hash),
                client.eraseFromSession(hash, name, directory))
            assertEquals(1, eraseCalls)
            assertEquals(listOf("d.multicall2", "d.hash", "d.erase", "d.multicall2", "d.hash"), methods)
        } finally { server.stop(0) }
    }

    @Test fun refusesInvalidHashesBeforeAnyNetwork() = runBlocking {
        val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:1/RPC2", "", ""))
        for (bad in listOf("../", "1234", "z".repeat(40), "ab".repeat(21))) {
            try {
                remote.eraseFromSession(bad, name, directory)
                fail("Invalid torrent hash should be refused")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
