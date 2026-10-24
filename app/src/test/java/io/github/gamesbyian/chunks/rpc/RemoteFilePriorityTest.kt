package io.github.gamesbyian.chunks.rpc

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteFilePriorityTest {
    private val hash = "ab".repeat(20)
    private val target = "second/video.ogg"
    private fun value(s: String) = "<value><string>$s</string></value>"
    private fun number(n: Int) = "<value><i8>$n</i8></value>"
    private fun files(secondPriority: Int): String {
        val a = listOf(value("first.txt"), number(5), number(1))
        val b = listOf(value(target), number(42), number(secondPriority))
        fun row(fields: List<String>) = "<value><array><data>" +
            fields.joinToString("") + "</data></array></value>"
        return "<value><array><data>" + row(a) + row(b) + "</data></array></value>"
    }

    @Test fun validatesExactFileBeforeAnyWriteAndReadsBackRealPriority() = runBlocking {
        var priority = 1
        val methods = mutableListOf<String>()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(body)?.groupValues?.get(1).orEmpty()
            methods.add(method)
            val result = when (method) {
                "f.multicall" -> files(priority)
                "f.priority.set" -> {
                    assertTrue(body.contains(hash + ":f1"))
                    assertFalse("Index belongs in hash:fINDEX, not a second numeric argument", body.contains("<i8>1</i8>"))
                    assertTrue(body.contains("<i8>2</i8>"))
                    priority = 2
                    number(0)
                }
                "d.update_priorities" -> number(0)
                else -> throw AssertionError("Unexpected RPC $method")
            }
            val xml = "<methodResponse><params><param>$result</param></params></methodResponse>"
                .toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, xml.size.toLong())
            exchange.responseBody.use { it.write(xml) }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            try {
                remote.setFilePriority(hash, 1, "wrong-path", 42, 1, 2)
                fail("Stale identity must refuse writes")
            } catch (_: IllegalArgumentException) { }
            assertEquals(listOf("f.multicall"), methods)
            assertEquals(FilePriorityOutcome.Verified(hash, 1, 2),
                remote.setFilePriority(hash, 1, target, 42, 1, 2))
            assertEquals(listOf("f.multicall", "f.multicall", "f.priority.set",
                "d.update_priorities", "f.multicall"), methods)
            assertEquals(FilePriorityOutcome.Unchanged(hash, 1, 2),
                remote.setFilePriority(hash, 1, target, 42, 2, 2))
            assertEquals(1, methods.count { it == "f.priority.set" })
        } finally { server.stop(0) }
    }

    @Test fun lostSetterAcknowledgementIsUncertainAndNeverRetries() = runBlocking {
        var changed = false
        val methods = mutableListOf<String>()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/RPC2") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            val method = Regex("<methodName>([^<]+)</methodName>")
                .find(body)?.groupValues?.get(1).orEmpty()
            methods.add(method)
            if (method == "f.priority.set") {
                changed = true
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            } else {
                val result = if (method == "f.multicall")
                    files(if (changed) 2 else 1) else number(0)
                val xml = "<methodResponse><params><param>$result</param></params></methodResponse>"
                    .toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, xml.size.toLong())
                exchange.responseBody.use { it.write(xml) }
            }
        }
        server.start()
        try {
            val remote = RTorrentRemote(RemoteCredentials(
                "http://127.0.0.1:${server.address.port}/RPC2", "", ""))
            assertEquals(FilePriorityOutcome.Uncertain(hash, 1),
                remote.setFilePriority(hash, 1, target, 42, 1, 2))
            assertTrue(changed)
            assertEquals(listOf("f.multicall", "f.priority.set"), methods)
            assertEquals(2, remote.listFiles(hash)[1].priority)
            assertEquals(1, methods.count { it == "f.priority.set" })
            assertFalse(methods.contains("d.update_priorities"))
        } finally { server.stop(0) }
    }

    @Test fun invalidPrioritiesAndIndicesCannotReachServer() = runBlocking {
        val remote = RTorrentRemote(RemoteCredentials("http://127.0.0.1:1/RPC2", "", ""))
        for (priority in listOf(-1, 3, Int.MAX_VALUE)) {
            try {
                remote.setFilePriority(hash, 0, "test.ogg", 1, 1, priority)
                fail("Invalid priority must refuse before network")
            } catch (_: IllegalArgumentException) { }
        }
        for (index in listOf(-1, 12000)) {
            try {
                remote.setFilePriority(hash, index, "test.ogg", 1, 1, 2)
                fail("Invalid file index must refuse before network")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
