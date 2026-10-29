package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteLifecycleFixtureTest {
    private val hash = "A".repeat(40)

    @Test fun explicitHashStartStopAndReadback() = runBlocking {
        var started = false
        val calls = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(request)?.groupValues?.get(1) ?: ""
            calls.add(method)
            val body = when (method) {
                "d.start" -> { started = true; "<value><int>0</int></value>" }
                "d.stop" -> { started = false; "<value><int>0</int></value>" }
                "d.state", "d.is_active" -> "<value><int>${if (started) 1 else 0}</int></value>"
                else -> "<value><int>0</int></value>"
            }
            val xml = "<methodResponse><params><param>$body</param></params></methodResponse>".toByteArray()
            exchange.sendResponseHeaders(200, xml.size.toLong())
            exchange.responseBody.use { it.write(xml) }
        }
        server.start()
        try {
            val client = RTorrentRemote(RemoteCredentials("http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            assertEquals(LifecycleOutcome.Verified(true), client.setStarted(hash, true))
            assertEquals(LifecycleOutcome.Verified(false), client.setStarted(hash, false))
            assertEquals(listOf("d.open", "d.start", "d.resume", "d.state", "d.is_active",
                "d.stop", "d.close", "d.state", "d.is_active"), calls)
        } finally { server.stop(0) }
    }

    @Test fun lostGatewayAcknowledgementNeverRetriesStart() = runBlocking {
        var applied = false
        val calls = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(xml)?.groupValues?.get(1) ?: ""
            calls.add(method)
            if (method == "d.open") applied = true
            // Simulate a proxy losing the acknowledgement after the engine applied Start.
            exchange.sendResponseHeaders(502, -1)
            exchange.close()
        }
        server.start()
        try {
            val client = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            assertEquals(LifecycleOutcome.Uncertain(true), client.setStarted(hash, true))
            assertTrue("The remote command might already be applied", applied)
            assertEquals("No retry or speculative read after failed ACK", listOf("d.open"), calls)
        } finally { server.stop(0) }
    }

    @Test fun acceptedStartWithNoActiveEngineOrPeersDoesNotImplyDownloading() = runBlocking {
        var startIssued = false
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val request = exchange.requestBody.bufferedReader().readText()
            val method = Regex("<methodName>([^<]+)</methodName>").find(request)?.groupValues?.get(1) ?: ""
            val response = when (method) {
                "d.start" -> { startIssued = true; 0 }
                "d.state" -> if (startIssued) 1 else 0
                "d.is_active", "d.is_open", "d.peers_connected", "d.down.rate" -> 0
                else -> 0
            }
            val xml = "<methodResponse><params><param><value><int>$response</int></value></param></params></methodResponse>".toByteArray()
            exchange.sendResponseHeaders(200, xml.size.toLong())
            exchange.responseBody.use { it.write(xml) }
        }
        server.start()
        try {
            val client = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            assertEquals(LifecycleOutcome.Pending(true, true), client.setStarted(hash, true))
            val activity = client.readActivity(hash)
            assertEquals(false, activity.active)
            assertEquals(false, activity.open)
            assertEquals(0L, activity.connectedPeers)
            assertEquals(0L, activity.downloadBytesPerSecond)
            assertTrue(activity.summary().contains("not active"))
        } finally { server.stop(0) }
    }


    @Test fun stopStateWithoutInactiveEngineIsPendingNotVerified() = runBlocking {
        val methods = mutableListOf<String>()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(request)?.groupValues?.get(1).orEmpty()
            methods.add(method)
            // The server acknowledges Stop and reports state=0, but the
            // transfer engine still has active work. No success claim.
            val value = if (method == "d.is_active") 1 else 0
            val response = "<methodResponse><params><param><value><int>$value</int>" +
                "</value></param></params></methodResponse>"
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            assertEquals(LifecycleOutcome.Pending(false, false),
                remote.setStarted(hash, false))
            assertEquals(listOf("d.stop", "d.close", "d.state", "d.is_active"), methods)
        } finally { server.stop(0) }
    }

    @Test fun stopActivityGetterFaultNeverBecomesVerifiedStopped() = runBlocking {
        val methods = mutableListOf<String>()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(request)?.groupValues?.get(1).orEmpty()
            methods.add(method)
            val response = if (method == "d.is_active") {
                """<methodResponse><fault><value><struct>
                <member><name>faultCode</name><value><int>-32601</int></value></member>
                <member><name>faultString</name><value><string>Method unavailable</string></value></member>
                </struct></value></fault></methodResponse>"""
            } else "<methodResponse><params><param><value><int>0</int>" +
                "</value></param></params></methodResponse>"
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            assertEquals(LifecycleOutcome.Pending(false, false),
                remote.setStarted(hash, false))
            assertEquals(listOf("d.stop", "d.close", "d.state", "d.is_active"), methods)
        } finally { server.stop(0) }
    }

    @Test fun invalidHashCannotIssueCommands() = runBlocking {
        try {
            RTorrentRemote(RemoteCredentials("http://127.0.0.1:1/RPC2", "", ""))
                .setStarted("../not-a-hash", false)
            fail("Expected exact hash validation")
        } catch (_: IllegalArgumentException) { }
    }
}
