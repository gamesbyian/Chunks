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
                "d.state" -> "<value><int>${if (started) 1 else 0}</int></value>"
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
            assertEquals(listOf("d.start", "d.state", "d.stop", "d.state"), calls)
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
