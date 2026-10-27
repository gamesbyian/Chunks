package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteTrafficSampleRpcTest {
    private val hash = "ab".repeat(20)
    private fun str(s: String) = "<value><string>$s</string></value>"
    private fun num(n: Long) = "<value><i8>$n</i8></value>"

    @Test fun exactHashGuardsBracketReadOnlyCumulativeGetters() = runBlocking {
        val methods = mutableListOf<String>()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(xml)
                ?.groupValues?.get(1).orEmpty()
            synchronized(methods) { methods.add(method) }
            val result = when (method) {
                "d.hash" -> str(hash)
                "d.down.total" -> num(1100)
                "d.completed_bytes" -> num(900)
                "d.size_bytes" -> num(10000)
                "d.is_active" -> num(1)
                "d.peers_connected" -> num(2)
                "d.down.rate" -> num(0)
                else -> throw AssertionError("Unexpected RPC method $method")
            }
            val response = "<methodResponse><params><param>$result</param></params></methodResponse>"
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            val observed = remote.readTrafficSample(hash)
            assertEquals(hash, observed.hash)
            assertEquals(1100L, observed.receivedBytes)
            assertEquals(900L, observed.verifiedBytes)
            assertEquals(10000L, observed.expectedBytes)
            assertTrue(observed.active == true)
            assertEquals(2L, observed.peers)
            assertEquals(listOf("d.hash", "d.down.total", "d.completed_bytes",
                "d.size_bytes", "d.is_active", "d.peers_connected", "d.down.rate", "d.hash"), methods)
        } finally { server.stop(0) }
    }

    @Test fun postReadIdentityChangeCannotBeMisreportedAsDownload() = runBlocking {
        var guard = 0
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val xml = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>").find(xml)
                ?.groupValues?.get(1).orEmpty()
            val result = if (method == "d.hash") {
                guard++
                str(if (guard == 1) hash else "cd".repeat(20))
            } else num(50)
            val response = "<methodResponse><params><param>$result</param></params></methodResponse>"
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            try {
                remote.readTrafficSample(hash)
                fail("A changed server identity cannot produce a sample")
            } catch (_: IllegalStateException) { }
            assertEquals(2, guard)
        } finally { server.stop(0) }
    }
}
